const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const sourceRoot = path.join(__dirname, '../src')
function evaluate(source, filename, dependencies = {}, globals = {}) {
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) =>
        dependencies[name] || (name.startsWith('@/') || name.endsWith('.vue') ? {} : require(name)),
      ...globals
    },
    { filename }
  )
  return module.exports
}
function load(relative, dependencies = {}) {
  const filename = path.join(sourceRoot, relative)
  return evaluate(fs.readFileSync(filename, 'utf8'), filename, dependencies)
}
const quantities = load('utils/mesQuantity.ts')
const sn = load('views/mes/pro/sntrace/snQuantities.ts', { '@/utils/mesQuantity': quantities })
const constants = load('views/mes/utils/constants.ts')
function setup(t, relative, props = {}, dependencies = {}) {
  const filename = path.join(sourceRoot, relative)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const events = []
  const component = evaluate(
    compileScript(descriptor, { id: 'decimal-regression' }).content,
    filename,
    {
      vue,
      '@/utils/mesQuantity': quantities,
      '@/views/mes/utils/constants': constants,
      ...dependencies
    },
    {
      ...vue,
      onMounted: () => {},
      useRouter: () => ({ push: async () => {} }),
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({ success: () => {}, info: () => {}, warning: () => {} })
    }
  ).default
  const scope = vue.effectScope()
  const state = scope.run(() =>
    component.setup(props, {
      expose: () => {},
      emit: (name, value) => events.push([name, value])
    })
  )
  t.after(() => scope.stop())
  return { state, events }
}
function validation(rule, value) {
  let error
  rule.validator({}, value, (failure) => {
    error = failure
  })
  return error
}

test('quantities retain the minimum unit and all DECIMAL(20,6) boundary digits', () => {
  assert.equal(quantities.quantityUnits('0.000001'), 1n)
  assert.equal(
    quantities.quantityText(quantities.quantityUnits('99999999999999.999999')),
    '99999999999999.999999'
  )
  assert.equal(
    quantities.sumQuantities('99999999999999.999998', '0.000001'),
    '99999999999999.999999'
  )
  assert.equal(
    quantities.minQuantity('99999999999999.999998', '99999999999999.999999'),
    '99999999999999.999998'
  )
  for (const invalid of ['100000000000000', '0.0000001', '1e2', 'NaN', '-1', '']) {
    assert.ok(validation(quantities.quantityRule(true), invalid), invalid)
  }
  assert.equal(validation(quantities.quantityRule(true), '0.000001'), undefined)
  assert.ok(validation(quantities.quantityRule(true), '0'))
  assert.equal(validation(quantities.quantityRule(false), '0'), undefined)
})

test('available-stock validation rejects a single micro-unit excess at the largest quantity', () => {
  const rule = quantities.quantityRule(true, '99999999999999.999998')
  assert.ok(validation(rule, '99999999999999.999999'))
  assert.equal(validation(rule, '99999999999999.999998'), undefined)
})

test('quantity input emits the original decimal text without converting to Number', (t) => {
  const { state, events } = setup(t, 'views/mes/components/MesQuantityInput.vue', {
    modelValue: ''
  })
  for (const value of ['0.000001', '1.000001', '99999999999999.999999']) state.update(value)
  assert.deepEqual(events, [
    ['update:modelValue', '0.000001'],
    ['update:modelValue', '1.000001'],
    ['update:modelValue', '99999999999999.999999']
  ])
})

test('work order creation, reopen, and update preserve fractional and large quantities', async (t) => {
  const saved = []
  const record = { id: 1, quantity: '99999999999999.999999' }
  const { state } = setup(
    t,
    'views/mes/pro/workorder/WorkOrderForm.vue',
    {},
    {
      '@/api/mes/pro/workorder': {
        ProWorkOrderApi: {
          createWorkOrder: async (value) => {
            saved.push({ ...value })
            return 1
          },
          getWorkOrder: async () => ({ ...record }),
          updateWorkOrder: async (value) => saved.push({ ...value })
        }
      }
    }
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  state.formData.value.quantity = '0.000001'
  await state.submitForm()
  assert.equal(saved[0].quantity, '0.000001')
  await state.open('update', 1)
  await state.submitForm()
  assert.equal(saved[1].quantity, '99999999999999.999999')
})

test('actual picking keeps the selected inventory quantity exact in the saved payload', async (t) => {
  const saved = []
  const { state } = setup(
    t,
    'views/mes/wm/productissue/ProductIssueDetailForm.vue',
    { issueId: 7 },
    {
      '@/api/mes/wm/productissue/detail': {
        WmProductIssueDetailApi: {
          createProductIssueDetail: async (value) => saved.push(value)
        }
      }
    }
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  await state.open('create', 8, 10)
  state.handleStockChange({ id: 6, quantity: '99999999999999.999999', itemId: 10 })
  await state.submitForm()
  assert.equal(saved[0].quantity, '99999999999999.999999')
  assert.equal(saved[0].lineId, 8)
})

test('process inspection adds scrap quantities as decimals, including values beyond Number precision', (t) => {
  const { state } = setup(t, 'views/mes/qc/ipqc/IpqcForm.vue', { terminal: false })
  Object.assign(state.formData.value, {
    laborScrapQuantity: '99999999999999.999997',
    materialScrapQuantity: '0.000001',
    otherScrapQuantity: '0.000001'
  })
  state.handleScrapChanged()
  assert.equal(state.formData.value.unqualifiedQuantity, '99999999999999.999999')
})

const allocation = (id, input, available, unit = '1') => ({
  consumeLineId: id,
  inputQuantity: input,
  availableQuantity: available,
  unitQuantity: unit
})
test('SN allocation rejects micro-unit over-allocation and exactly preserves merged sources', () => {
  const rows = [
    allocation(1, '99999999999999.999998', '99999999999999.999998', '99999999999999.999999'),
    allocation(1, '0.000001', '0.000001', '99999999999999.999999')
  ]
  assert.equal(sn.snAllocationTotals(rows)[0].valid, true)
  rows[1].inputQuantity = '0.000002'
  assert.equal(sn.snAllocationTotals(rows)[0].valid, false)
  rows[1].inputQuantity = '0.0000001'
  assert.equal(sn.snAllocationTotals(rows)[0].valid, false)
})

test('SN auto-allocation splits one unit exactly and never rounds a shortage into a full share', () => {
  const rows = [allocation(1, '0', '0.999999'), allocation(1, '0', '0.000001')]
  const proposal = sn.distributeSnMaterials(rows)
  assert.deepEqual(Array.from(proposal.quantities), ['0.999999', '0.000001'])
  assert.equal(proposal.complete, true)
  rows[1].availableQuantity = '0'
  assert.equal(sn.distributeSnMaterials(rows).complete, false)
  rows[0].unitQuantity = '0.3333333'
  assert.equal(sn.distributeSnMaterials(rows).complete, false)
})
