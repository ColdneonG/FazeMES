const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const clone = (value) => JSON.parse(JSON.stringify(value))
function evaluate(source, filename, aliases = {}, globals = {}) {
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) => aliases[name] || (name.endsWith('.vue') ? {} : require(name)),
      ...globals
    },
    { filename }
  )
  return module.exports
}
const constantsFile = path.join(__dirname, '../src/views/mes/utils/constants.ts')
const constants = evaluate(fs.readFileSync(constantsFile, 'utf8'), constantsFile)
const { MesProWorkOrderStatusEnum: Status, MesProWorkOrderTypeEnum: Type } = constants
const order = (overrides = {}) => ({
  id: 41,
  code: 'WO-FLOW-41',
  name: '教学产品生产',
  type: Type.SELF,
  status: Status.PREPARE,
  orderSourceType: constants.MesProWorkOrderSourceTypeEnum.STORE,
  productId: 9,
  quantity: '10.000001',
  requestDate: '2026-10-26',
  ...overrides
})

function setup(t, relative = 'WorkOrderForm.vue', { api = {}, confirm, push } = {}) {
  const filename = path.join(__dirname, '../src/views/mes/pro/workorder', relative)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const events = [],
    destinations = [],
    successes = []
  const component = evaluate(
    compileScript(descriptor, { id: 'workorder-flow' }).content,
    filename,
    {
      vue,
      '@/utils/mesQuantity': { quantityRule: () => ({}) },
      '@/utils/dict': {},
      '@/utils/formatTime': {},
      '@/utils/download': {},
      '@/utils/tree': { handleTree: (rows) => rows },
      '@/views/mes/cost/navigation': {},
      '@/views/mes/utils/constants': constants,
      '@/views/mes/wm/barcode/components': {},
      '@/api/mes/md/autocode/record': { AutoCodeRecordApi: {} },
      '@/api/mes/pro/workorder': {
        ProWorkOrderApi: {
          getWorkOrder: async () => order(),
          createWorkOrder: async () => 41,
          updateWorkOrder: async () => {},
          confirmWorkOrder: async () => {},
          ...api
        }
      }
    },
    {
      ...vue,
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({
        confirm: confirm || (async () => {}),
        success: (value) => successes.push(value),
        info: () => {}
      }),
      useRouter: () => ({
        push: async (destination) => {
          destinations.push(clone(destination))
          if (push) await push(destination)
        }
      }),
      onMounted: () => {}
    }
  ).default
  const scope = vue.effectScope()
  const state = scope.run(() =>
    component.setup(
      {},
      {
        expose: () => {},
        emit: (name, value) => events.push([name, value])
      }
    )
  )
  t.after(() => scope.stop())
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  return { state, events, destinations, successes }
}

test('saving creates a draft and requires an explicit confirmation before production navigation', async (t) => {
  const calls = []
  const { state, destinations } = setup(t, 'WorkOrderForm.vue', {
    api: {
      createWorkOrder: async (data) => {
        calls.push(['create', clone(data)])
        return 41
      },
      confirmWorkOrder: async () => calls.push(['confirm'])
    }
  })
  await state.open('create')
  state.formData.value = order({ id: undefined, status: undefined })
  await state.submitForm()
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'create')
  assert.equal(state.formData.value.id, 41)
  assert.equal(state.formData.value.status, Status.PREPARE)
  assert.equal(state.formType.value, 'update')
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.canContinueProduction.value, false)
  await state.openProductionPage('/mes/pro/task')
  assert.equal(destinations.length, 0)
  await state.handleConfirm()
  assert.equal(calls[1][0], 'confirm')
  assert.equal(state.canContinueProduction.value, true)
})

test('confirmation keeps the confirmed order open as readonly details without automatic navigation', async (t) => {
  const confirmed = []
  const { state, events, destinations } = setup(t, 'WorkOrderForm.vue', {
    api: { confirmWorkOrder: async (id) => confirmed.push(id) }
  })
  await state.open('confirm', 41)
  await state.handleConfirm()
  assert.deepEqual(confirmed, [41])
  assert.equal(state.formData.value.status, Status.CONFIRMED)
  assert.equal(state.formType.value, 'detail')
  assert.equal(state.isDetail.value, true)
  assert.equal(state.isEditable.value, false)
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.canContinueProduction.value, true)
  assert.equal(destinations.length, 0)
  assert.equal(events.length, 1)
  assert.equal(events[0][0], 'success')
})

test('both production links carry the current work-order id and close only after navigation succeeds', async (t) => {
  for (const destination of ['/mes/pro/task', '/mes/wm/product-issue']) {
    let stateDuringNavigation
    const result = setup(t, 'WorkOrderForm.vue', {
      api: { getWorkOrder: async () => order({ id: 77, status: Status.CONFIRMED }) },
      push: async () => {
        stateDuringNavigation = result.state.dialogVisible.value
      }
    })
    await result.state.open('detail', 77)
    await result.state.openProductionPage(destination)
    assert.deepEqual(result.destinations, [{ path: destination, query: { workOrderId: 77 } }])
    assert.equal(stateDuringNavigation, true)
    assert.equal(result.state.dialogVisible.value, false)
  }
})

test('failed confirmation leaves a draft open and cannot expose production actions', async (t) => {
  const { state, events, destinations, successes } = setup(t, 'WorkOrderForm.vue', {
    api: {
      confirmWorkOrder: async () => {
        throw new Error('BOM unavailable')
      }
    }
  })
  await state.open('confirm', 41)
  await state.handleConfirm()
  assert.equal(state.formData.value.status, Status.PREPARE)
  assert.equal(state.formType.value, 'confirm')
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.formLoading.value, false)
  assert.equal(state.canContinueProduction.value, false)
  await state.openProductionPage('/mes/wm/product-issue')
  assert.equal(destinations.length, 0)
  assert.equal(events.length, 0)
  assert.equal(successes.length, 0)
})

test('dirty edits are saved before confirmation and a failed save never confirms', async (t) => {
  const calls = []
  const { state } = setup(t, 'WorkOrderForm.vue', {
    api: {
      updateWorkOrder: async (data) => calls.push(['save', data.name]),
      confirmWorkOrder: async (id) => calls.push(['confirm', id])
    }
  })
  await state.open('update', 41)
  state.formData.value.name = '已修改的产品生产'
  await state.handleConfirm()
  assert.deepEqual(calls, [
    ['save', '已修改的产品生产'],
    ['confirm', 41]
  ])
  const failed = setup(t, 'WorkOrderForm.vue', {
    api: {
      updateWorkOrder: async () => {
        throw new Error('save failed')
      },
      confirmWorkOrder: async () => assert.fail('must not confirm unsaved changes')
    }
  })
  await failed.state.open('update', 41)
  failed.state.formData.value.name = '尚未保存的修改'
  await failed.state.handleConfirm()
  assert.equal(failed.state.formData.value.status, Status.PREPARE)
  assert.equal(failed.state.canContinueProduction.value, false)
})

test('closed orders and non-self-production orders cannot use production shortcuts', async (t) => {
  for (const overrides of [
    { status: Status.FINISHED },
    { status: Status.CANCELED },
    { status: Status.CONFIRMED, type: Type.PURCHASE },
    { status: Status.CONFIRMED, type: Type.OUTSOURCE }
  ]) {
    const { state, destinations } = setup(t, 'WorkOrderForm.vue', {
      api: { getWorkOrder: async () => order(overrides) }
    })
    await state.open('detail', 41)
    assert.equal(state.canContinueProduction.value, false)
    await state.openProductionPage('/mes/pro/task')
    assert.equal(destinations.length, 0)
    assert.equal(state.dialogVisible.value, true)
  }
})

test('a failed route transition leaves the confirmed order available for retry', async (t) => {
  const { state } = setup(t, 'WorkOrderForm.vue', {
    api: { getWorkOrder: async () => order({ status: Status.CONFIRMED }) },
    push: async () => {
      throw new Error('route unavailable')
    }
  })
  await state.open('detail', 41)
  await assert.rejects(state.openProductionPage('/mes/pro/task'), /route unavailable/)
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.canContinueProduction.value, true)
})

test('the list confirmation action opens the selected draft in the shared confirmation form', async (t) => {
  const loaded = []
  const form = setup(t, 'WorkOrderForm.vue', {
    api: {
      getWorkOrder: async (id) => {
        loaded.push(id)
        return order({ id })
      }
    }
  })
  const list = setup(t, 'index.vue')
  let opened
  list.state.formRef.value = {
    open: (...args) => {
      opened = form.state.open(...args)
    }
  }
  list.state.openForm('confirm', 987654)
  await opened
  assert.deepEqual(loaded, [987654])
  assert.equal(form.state.formData.value.id, 987654)
  assert.equal(form.state.isConfirm.value, true)
  assert.equal(form.state.dialogVisible.value, true)
  assert.equal(form.state.canContinueProduction.value, false)
  assert.equal(list.destinations.length, 0)
})
