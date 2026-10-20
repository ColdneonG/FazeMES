const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { compileScript, compileTemplate, parse } = require('vue/compiler-sfc')
const { renderToString } = require('vue/server-renderer')

function evaluate(source, filename, dependencies, globals = {}) {
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(
    compiled,
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || (name.endsWith('.vue') ? {} : require(name)),
      ...globals
    },
    { filename }
  )
  return module.exports
}

const policyFile = path.join(__dirname, '../src/views/mes/wm/qualityPolicy.ts')
const policy = evaluate(fs.readFileSync(policyFile, 'utf8'), policyFile, {})
const quantityFile = path.join(__dirname, '../src/utils/mesQuantity.ts')
const quantities = evaluate(fs.readFileSync(quantityFile, 'utf8'), quantityFile, {})

function setupComponent(relativePath, props, dependencies = {}, messages = []) {
  const filename = path.join(__dirname, '../src/views/mes/wm', relativePath)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'quality-regression' }).content
  const mounted = []
  const events = []
  const component = evaluate(
    source,
    filename,
    {
      '../qualityPolicy': policy,
      '../../qualityPolicy': policy,
      '@/utils/mesQuantity': quantities,
      vue,
      ...dependencies
    },
    {
      ...vue,
      onMounted: (callback) => mounted.push(callback),
      onBeforeUnmount: () => {},
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({ warning: (value) => messages.push(value), success: () => {} })
    }
  ).default
  const state = component.setup(props, {
    expose: () => {},
    emit: (...event) => events.push(event)
  })
  return { state, mounted, events }
}

test('same-batch good stock remains selectable while quarantine and historical holds are blocked', () => {
  const good = { batchCode: 'same-batch', warehouseCode: 'FINISHED', frozen: false }
  assert.equal(policy.stockQualityBlockReason(good), '')
  assert.match(
    policy.stockQualityBlockReason({ ...good, warehouseCode: policy.QUALITY_QUARANTINE_WAREHOUSE }),
    /隔离/
  )
  assert.match(policy.stockQualityBlockReason({ ...good, qualityHold: true }), /普通解冻不能解除/)
  assert.equal(
    policy.stockQualityBlockReason({
      ...good,
      qualityHold: true,
      qualityHoldReason: '退货单 R001 待核对'
    }),
    '退货单 R001 待核对'
  )
  assert.match(policy.stockQualityBlockReason({ ...good, frozen: true }), /冻结/)
})

test('reserved warehouse recognition matches backend case-insensitive comparison without trimming', () => {
  assert.equal(policy.isQualityQuarantineWarehouse('quality_quarantine_warehouse'), true)
  assert.equal(policy.matchesQuarantineFilter('quality_quarantine_warehouse', 'only'), true)
  assert.equal(policy.matchesQuarantineFilter('quality_quarantine_warehouse', 'exclude'), false)
  assert.match(
    policy.stockQualityBlockReason({ warehouseCode: 'quality_quarantine_warehouse' }),
    /隔离/
  )
  assert.equal(policy.isQualityQuarantineWarehouse(' QUALITY_QUARANTINE_WAREHOUSE'), false)
  assert.equal(policy.isQualityQuarantineWarehouse(undefined), false)
})

test('outbound queries exclude quarantine and holds while inventory inspection includes both', () => {
  assert.equal(policy.stockSelectionFilters(true).quarantineFilter, 'exclude')
  assert.equal(policy.stockSelectionFilters(true).qualityHold, false)
  assert.equal(policy.stockSelectionFilters(true).frozen, false)
  assert.equal(policy.stockSelectionFilters(false).quarantineFilter, 'all')
  assert.equal(policy.stockSelectionFilters(false).qualityHold, undefined)
  assert.equal(policy.stockSelectionFilters(false).frozen, undefined)
})

test('warehouse selection switches to quarantine after failed inspection and clears ordinary warehouse', async () => {
  const warehouses = [
    { id: 1, code: 'FINISHED', name: '成品仓' },
    { id: 2, code: policy.QUALITY_QUARANTINE_WAREHOUSE, name: '质量隔离仓' }
  ]
  const props = vue.reactive({ modelValue: 1, quarantineFilter: 'all' })
  const { state, mounted, events } = setupComponent(
    'warehouse/components/WmWarehouseSelect.vue',
    props,
    {
      '@/api/mes/wm/warehouse': {
        WmWarehouseApi: { getWarehouseSimpleList: async () => warehouses }
      }
    }
  )
  await mounted[0]()
  assert.equal(state.filteredList.value.length, 2)
  props.quarantineFilter = 'only'
  await vue.nextTick()
  assert.equal(state.filteredList.value.length, 1)
  assert.equal(state.filteredList.value[0].id, 2)
  assert.ok(events.some(([name, value]) => name === 'update:modelValue' && value === undefined))
  state.handleFilter('FINISHED')
  assert.equal(state.filteredList.value.length, 0)
  state.handleFilter('隔离')
  assert.equal(state.filteredList.value[0].id, 2)
})

test('stock selector enforces outbound query filters and refuses blocked rows even in stale results', async () => {
  const calls = []
  const messages = []
  const held = { id: 4, warehouseCode: 'FINISHED', qualityHold: true, frozen: false }
  const props = vue.reactive({ multiple: true, outboundOnly: true, virtualFilter: 'all' })
  const { state, events } = setupComponent(
    'materialstock/components/WmMaterialStockSelectDialog.vue',
    props,
    {
      '@/api/mes/wm/materialstock': {
        WmMaterialStockApi: {
          getMaterialStockPage: async (params) => {
            calls.push(params)
            return { list: [held], total: 1 }
          }
        }
      },
      '@/utils/formatTime': { dateFormatter2: () => '' }
    },
    messages
  )
  state.queryParams.frozen = true
  await state.getList()
  assert.equal(calls[0].qualityHold, false)
  assert.equal(calls[0].quarantineFilter, 'exclude')
  assert.equal(calls[0].frozen, false)
  assert.equal(state.canSelectStock(held), false)
  state.selectedRows.value = [held]
  state.confirmSelect()
  assert.equal(events.filter(([name]) => name === 'selected').length, 0)
  assert.match(messages.at(-1), /管理员核对/)
  props.outboundOnly = false
  await state.getList()
  assert.equal(calls[1].qualityHold, undefined)
  assert.equal(calls[1].quarantineFilter, 'all')
  assert.equal(calls[1].frozen, undefined)
  assert.equal(state.canSelectStock(held), true)
  state.confirmSelect()
  assert.equal(events.filter(([name]) => name === 'selected').length, 1)
})

test('ordinary unfreeze on a historical quality hold is stopped before the update API', async () => {
  let updates = 0
  const messages = []
  const { state } = setupComponent(
    'materialstock/index.vue',
    {},
    {
      '@/api/mes/wm/materialstock': {
        WmMaterialStockApi: {
          updateMaterialStockFrozen: async () => {
            updates++
          }
        }
      },
      '@/utils/formatTime': { dateFormatter2: () => '' },
      '@/utils/download': {}
    },
    messages
  )
  const stock = {
    id: 3,
    frozen: false,
    qualityHold: true,
    qualityHoldReason: '历史不良混入，待核对'
  }
  await state.handleFrozenChange(stock)
  assert.equal(updates, 0)
  assert.equal(stock.frozen, true, 'the optimistic unfreeze is reverted')
  assert.equal(messages[0], stock.qualityHoldReason)
})

test('quality evidence can include unlocated inventory and zero quantity, with clean scope when reopened', async () => {
  const calls = []
  const unresolved = { id: 1, materialStockId: null, observedQuantity: 0, status: 'PENDING_REVIEW' }
  const { state } = setupComponent(
    'materialstock/components/StockQualityHoldDialog.vue',
    {},
    {
      '@/api/mes/wm/materialstock': {
        WmMaterialStockApi: {
          getQualityHoldPage: async (params) => {
            calls.push(params)
            return { list: [unresolved], total: 11 }
          }
        }
      },
      '@/utils/formatTime': { dateFormatter2: () => '' }
    }
  )
  await state.open(123)
  assert.equal(calls[0].materialStockId, 123)
  state.queryParams.pageNo = 2
  await state.getList()
  assert.equal(calls[1].pageNo, 2)
  assert.equal(calls[1].materialStockId, 123)
  await state.open()
  assert.equal(calls[2].materialStockId, undefined)
  assert.equal(calls[2].pageNo, 1)
  assert.equal(state.list.value[0].materialStockId, null)
  assert.equal(state.list.value[0].observedQuantity, 0)
  assert.equal(state.total.value, 11)
})

test('quality evidence renders the exact 20,6 decimal text without JavaScript number rounding', async () => {
  const exactQuantity = '99999999999999.123456'
  const relativePath = 'materialstock/components/StockQualityHoldDialog.vue'
  const { state } = setupComponent(
    relativePath,
    {},
    {
      '@/api/mes/wm/materialstock': {
        WmMaterialStockApi: {
          getQualityHoldPage: async () => ({
            list: [{ id: 1, observedQuantity: exactQuantity, status: 'PENDING_REVIEW' }],
            total: 1
          })
        }
      },
      '@/utils/formatTime': { dateFormatter2: () => '' }
    }
  )
  await state.open()
  const filename = path.join(__dirname, '../src/views/mes/wm', relativePath)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'decimal-proof'
  })
  const { render } = evaluate(template.code, filename, { vue })
  const app = vue.createSSRApp({ setup: () => ({ ...state }), render })
  const slotsOnly = {
    setup:
      (_, { slots }) =>
      () =>
        vue.h('div', slots.default?.())
  }
  app.component('Dialog', slotsOnly)
  app.component('ElButton', slotsOnly)
  app.component('ElTag', slotsOnly)
  app.component('ElAlert', { render: () => vue.h('div') })
  app.component('Pagination', { render: () => vue.h('div') })
  app.component('ElTable', {
    props: { data: { type: Array, required: true } },
    setup: (props, { slots }) => {
      vue.provide(
        'quality-rows',
        vue.computed(() => props.data)
      )
      return () => vue.h('div', slots.default?.())
    }
  })
  app.component('ElTableColumn', {
    props: { prop: { type: String, default: '' } },
    setup: (props, { slots }) => {
      const rows = vue.inject('quality-rows')
      return () =>
        vue.h(
          'div',
          rows.value.map((row) =>
            slots.default ? slots.default({ row }) : String(row[props.prop] ?? '')
          )
        )
    }
  })
  app.directive('loading', {})
  const html = await renderToString(app)
  assert.ok(html.includes(exactQuantity), 'the rendered cell must retain all six decimal places')
  assert.equal(state.list.value[0].observedQuantity, exactQuantity)
})

test('stale quality evidence cannot overwrite a later stock or reopen a closed dialog', async () => {
  const pending = []
  const { state } = setupComponent(
    'materialstock/components/StockQualityHoldDialog.vue',
    {},
    {
      '@/api/mes/wm/materialstock': {
        WmMaterialStockApi: {
          getQualityHoldPage: () => new Promise((resolve) => pending.push(resolve))
        }
      },
      '@/utils/formatTime': { dateFormatter2: () => '' }
    }
  )
  const first = state.open(1)
  const second = state.open(2)
  pending[1]({ list: [{ id: 2 }], total: 1 })
  await second
  pending[0]({ list: [{ id: 1 }], total: 1 })
  await first
  assert.equal(state.list.value[0].id, 2)
  const third = state.open(3)
  state.dialogVisible.value = false
  pending[2]({ list: [{ id: 3 }], total: 1 })
  await third
  assert.equal(state.dialogVisible.value, false)
  assert.equal(state.list.value.length, 0)
  assert.equal(state.loading.value, false)
})

test('failed quality evidence request clears old rows and can be retried without a mutation API', async () => {
  let shouldFail = false
  const { state } = setupComponent(
    'materialstock/components/StockQualityHoldDialog.vue',
    {},
    {
      '@/api/mes/wm/materialstock': {
        WmMaterialStockApi: {
          getQualityHoldPage: async () => {
            if (shouldFail) throw new Error('network')
            return { list: [{ id: 5 }], total: 1 }
          }
        }
      },
      '@/utils/formatTime': { dateFormatter2: () => '' }
    }
  )
  await state.open()
  assert.equal(state.list.value.length, 1)
  shouldFail = true
  await state.getList()
  assert.equal(state.loadFailed.value, true)
  assert.equal(state.list.value.length, 0)
  assert.equal(state.total.value, 0)
  shouldFail = false
  await state.getList()
  assert.equal(state.loadFailed.value, false)
  assert.equal(state.list.value[0].id, 5)
})

test('outsource receipt loads current quality, blocks pending receipt, and resets location on warehouse change', async () => {
  let qualityStatus = 0
  let saves = 0
  const messages = []
  const { state } = setupComponent(
    'outsourcereceipt/OutsourceReceiptDetailForm.vue',
    { receiptId: 9 },
    {
      '@/api/mes/wm/outsourcereceipt/line': {
        WmOutsourceReceiptLineApi: {
          getOutsourceReceiptLine: async () => ({ itemId: 4, batchCode: 'B1', qualityStatus })
        }
      },
      '@/api/mes/wm/outsourcereceipt/detail': {
        WmOutsourceReceiptDetailApi: {
          getOutsourceReceiptDetail: async () => ({
            id: 5,
            itemId: 4,
            warehouseId: 2,
            locationId: 3,
            areaId: 4
          }),
          updateOutsourceReceiptDetail: async () => {
            saves++
          }
        }
      }
    },
    messages
  )
  state.formRef.value = { resetFields: () => {}, validate: async () => true }
  await state.open('update', 1, 4, 5)
  await state.submitForm()
  assert.equal(saves, 0)
  assert.match(messages[0], /待检验/)
  qualityStatus = 2
  await state.open('update', 1, 4, 5)
  assert.equal(state.qualityStatus.value, 2)
  assert.equal(state.formData.value.locationId, 3)
  assert.equal(state.formData.value.areaId, 4)
  await state.submitForm()
  assert.equal(saves, 1)
  state.handleWarehouseChange()
  assert.equal(state.formData.value.locationId, undefined)
  assert.equal(state.formData.value.areaId, undefined)
})

for (const type of ['Sales', 'Issue']) {
  test(`return ${type.toLowerCase()} cannot save pending inspection and reloads current quality before editing`, async () => {
    let qualityStatus = 0
    let saves = 0
    let reads = 0
    const messages = []
    const apiPrefix = `WmReturn${type}`
    const base = type.toLowerCase() === 'sales' ? 'returnsales' : 'returnissue'
    const { state } = setupComponent(
      `${base}/Return${type}DetailForm.vue`,
      { returnId: 9, issueId: 9 },
      {
        [`@/api/mes/wm/${base}/line`]: {
          [`${apiPrefix}LineApi`]: {
            [`getReturn${type}Line`]: async () => {
              reads++
              return { returnId: 9, itemId: 4, qualityStatus, materialStockId: 10, quantity: '5' }
            }
          }
        },
        [`@/api/mes/wm/${base}/detail`]: {
          [`${apiPrefix}DetailApi`]: {
            [`getReturn${type}Detail`]: async () => ({
              id: 5,
              returnId: 9,
              lineId: 1,
              quantity: '5',
              itemId: 4,
              warehouseId: 2,
              locationId: 3,
              areaId: 4
            }),
            [`updateReturn${type}Detail`]: async () => {
              saves++
            }
          }
        },
        '@/api/mes/wm/materialstock': {
          WmMaterialStockApi: { getMaterialStock: async () => ({ quantity: 5 }) }
        }
      },
      messages
    )
    state.formRef.value = { resetFields: () => {}, validate: async () => true }
    await state.open('update', 1, 4, 5)
    await state.submitForm()
    assert.equal(saves, 0)
    assert.match(messages[0], /待检验/)
    qualityStatus = 2
    await state.open('update', 1, 4, 5)
    assert.equal(state.qualityStatus.value, 2)
    assert.equal(
      state.formData.value.locationId,
      3,
      'loading an edit must preserve the chosen location'
    )
    assert.equal(state.formData.value.areaId, 4, 'loading an edit must preserve the chosen area')
    await state.submitForm()
    assert.equal(saves, 1)
    assert.equal(reads, 2)
    state.handleWarehouseChange()
    assert.equal(state.formData.value.locationId, undefined)
    assert.equal(state.formData.value.areaId, undefined)
  })
}
