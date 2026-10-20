const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')
function evaluate(source, filename, dependencies = {}, globals = {}) {
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
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
const quantityFile = path.join(__dirname, '../src/utils/mesQuantity.ts')
const quantities = evaluate(fs.readFileSync(quantityFile, 'utf8'), quantityFile)
const sourceFile = path.join(__dirname, '../src/views/mes/wm/returnsales/sourceQuantity.ts')
const sourceQuantity = evaluate(fs.readFileSync(sourceFile, 'utf8'), sourceFile, {
  '@/utils/mesQuantity': quantities
})
const policyFile = path.join(__dirname, '../src/views/mes/wm/qualityPolicy.ts')
const policy = evaluate(fs.readFileSync(policyFile, 'utf8'), policyFile)
function setup(t, relative, props, dependencies = {}) {
  const filename = path.join(__dirname, '../src/views/mes', relative)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const messages = [],
    events = []
  const component = evaluate(
    compileScript(descriptor, { id: 'return-source' }).content,
    filename,
    {
      vue,
      '@/utils/dict': {},
      '@/utils/mesQuantity': quantities,
      './sourceQuantity': sourceQuantity,
      '../qualityPolicy': policy,
      '@/views/mes/wm/barcode/components': {},
      '@/api/mes/wm/returnsales/line': {
        WmReturnSalesLineApi: { getReturnSalesLinePage: async () => ({ list: [], total: 0 }) }
      },
      ...dependencies
    },
    {
      ...vue,
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({ warning: (value) => messages.push(value), success: () => {} }),
      onMounted: () => {},
      onBeforeUnmount: () => {}
    }
  ).default
  const scope = vue.effectScope(),
    reactiveProps = vue.reactive(props)
  const state = scope.run(() =>
    component.setup(reactiveProps, {
      expose: () => {},
      emit: (name, value) => events.push([name, value])
    })
  )
  t.after(() => scope.stop())
  return { state, props: reactiveProps, messages, events }
}
test('a return line cannot be saved without an actual original outbound reference', async (t) => {
  const saved = []
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesLineList.vue',
    { returnId: 3, formType: 'update' },
    {
      '@/api/mes/wm/returnsales/line': {
        WmReturnSalesLineApi: {
          createReturnSalesLine: async (value) => saved.push(value),
          getReturnSalesLinePage: async () => ({ list: [], total: 0 })
        }
      }
    }
  )
  state.formRef.value = { validate: async () => true }
  state.lineFormType.value = 'create'
  state.formData.value.itemId = 20
  state.formData.value.quantity = '1.000001'
  await state.submitForm()
  assert.equal(saved.length, 0)
})

const flush = async () => {
  await Promise.resolve()
  await vue.nextTick()
  await Promise.resolve()
  await vue.nextTick()
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}
const source = (id = 101, quota = '6.000000') => ({
  id,
  originTransactionId: id,
  salesId: 8,
  salesCode: 'XS-8',
  salesLineId: 80,
  itemId: 20,
  itemCode: 'FG',
  itemName: '成品',
  batchId: 30,
  batchCode: 'B1',
  itemSpecification: null,
  unitMeasureId: 1,
  unitMeasureName: '件',
  materialStockId: 9,
  warehouseId: 1,
  warehouseName: '成品仓',
  locationId: 2,
  locationName: '区',
  areaId: 3,
  areaName: '位',
  outboundAt: '2026-10-01T12:00:00+08:00',
  outboundQuantity: '10',
  returnedQuantity: '4',
  reservedQuantity: '0',
  availableQuantity: quota,
  editableAvailableQuantity: quota,
  sourceReliable: true
})
const lineDependencies = (api = {}) => ({
  '@/api/mes/wm/returnsales/line': {
    WmReturnSalesLineApi: {
      getReturnSalesLinePage: async () => ({ list: [], total: 0 }),
      getSourceOptions: async () => ({ list: [source()], total: 1 }),
      ...api
    }
  }
})

test('choosing an origin uses remaining quota, replacing it resets quantity, and refreshing edit retains own quantity', (t) => {
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesLineList.vue',
    { returnId: 3, formType: 'update' },
    lineDependencies()
  )
  state.handleSourceChange(source())
  assert.equal(state.formData.value.quantity, '6.000000')
  state.formData.value.quantity = '0.000001'
  state.handleSourceChange(source())
  assert.equal(state.formData.value.quantity, '0.000001')
  state.handleSourceChange(source(102, '2'))
  assert.equal(state.formData.value.quantity, '2')
  state.handleSourceChange(undefined)
  assert.equal(state.formData.value.originTransactionId, undefined)
  assert.equal(state.formData.value.quantity, '')
})

test('return quota checks retain micro precision, known zero and unknown historical evidence', () => {
  const maximum = source(101, '99999999999999.123456')
  assert.equal(sourceQuantity.returnQuantityError('99999999999999.123456', maximum), '')
  assert.match(sourceQuantity.returnQuantityError('99999999999999.123457', maximum), /超过/)
  for (const value of ['0', '-1', '1e2', '0.0000001', ''])
    assert.notEqual(sourceQuantity.returnQuantityError(value, maximum), '')
  assert.equal(sourceQuantity.canSelectReturnSource(source(101, '0')), false)
  assert.equal(sourceQuantity.canSelectReturnSource({ ...maximum, sourceReliable: false }), false)
  assert.equal(
    sourceQuantity.canSelectReturnSource({ ...maximum, editableAvailableQuantity: null }),
    false
  )
  assert.equal(sourceQuantity.returnBalanceText(null), '待核对')
  assert.equal(sourceQuantity.returnBalanceText('0'), '0')
})

test('saving refreshes one exact origin and sends only explicit source and decimal quantity fields', async (t) => {
  const saved = [],
    queries = []
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesLineList.vue',
    { returnId: 3, formType: 'update' },
    lineDependencies({
      getSourceOptions: async (query) => {
        queries.push(query)
        return { list: [source()], total: 1 }
      },
      createReturnSalesLine: async (data) => saved.push(data)
    })
  )
  state.formRef.value = { validate: async () => true }
  state.lineFormType.value = 'create'
  state.handleSourceChange(source())
  state.formData.value.quantity = '0.000001'
  await state.submitForm()
  assert.equal(queries[0].originTransactionId, 101)
  assert.equal(queries[0].returnId, 3)
  assert.equal(saved[0].quantity, '0.000001')
  assert.equal(saved[0].originTransactionId, 101)
  assert.equal(saved[0].returnId, 3)
  for (const key of [
    'availableQuantity',
    'returnedQuantity',
    'itemCode',
    'unitMeasureName',
    'salesId',
    'batchId'
  ])
    assert.equal(Object.hasOwn(saved[0], key), false)
})

test('a shrunken quota blocks saving and refreshes the displayed balance', async (t) => {
  const saved = []
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesLineList.vue',
    { returnId: 3, formType: 'update' },
    lineDependencies({
      getSourceOptions: async () => ({ list: [source(101, '0')], total: 1 }),
      createReturnSalesLine: async (data) => saved.push(data)
    })
  )
  state.formRef.value = { validate: async () => true }
  state.lineFormType.value = 'create'
  state.handleSourceChange(source())
  await state.submitForm()
  assert.equal(saved.length, 0)
  assert.equal(state.source.value.editableAvailableQuantity, '0')
})

test('editing uses the server own-line quota without adding the old reservation twice', async (t) => {
  const saved = [],
    queries = []
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesLineList.vue',
    { returnId: 3, formType: 'update' },
    lineDependencies({
      getReturnSalesLine: async () => ({
        id: 77,
        returnId: 3,
        originTransactionId: 101,
        itemId: 20,
        batchId: 30,
        quantity: '2.000001',
        rqcCheckFlag: true
      }),
      getSourceOptions: async (query) => {
        queries.push(query)
        return { list: [{ ...source(101, '7'), availableQuantity: '4.999999' }], total: 1 }
      },
      updateReturnSalesLine: async (data) => saved.push(data)
    })
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  await state.openForm('update', 77)
  assert.equal(state.formData.value.quantity, '2.000001')
  state.formData.value.quantity = '7.000001'
  await state.submitForm()
  assert.equal(saved.length, 0)
  state.formData.value.quantity = '7'
  await state.submitForm()
  assert.equal(saved[0].quantity, '7')
  assert.equal(queries.at(-1).lineId, 77)
})

test('source refresh arriving after switching return parent cannot post the old line', async (t) => {
  const pending = deferred(),
    saved = []
  const { state, props } = setup(
    t,
    'wm/returnsales/ReturnSalesLineList.vue',
    { returnId: 3, formType: 'update' },
    lineDependencies({
      getSourceOptions: async () => pending.promise,
      createReturnSalesLine: async (data) => saved.push(data)
    })
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  state.lineFormType.value = 'create'
  state.handleSourceChange(source())
  const saving = state.submitForm()
  await flush()
  props.returnId = 4
  await flush()
  pending.resolve({ list: [source()], total: 1 })
  await saving
  assert.equal(saved.length, 0)
  assert.equal(state.dialogVisible.value, false)
})

test('origin selector blocks zero and unreliable rows for click, double-click and confirm', async (t) => {
  const calls = []
  const { state, events } = setup(
    t,
    'wm/returnsales/ReturnSalesSourceSelect.vue',
    { returnId: 3 },
    lineDependencies({
      getSourceOptions: async (query) => {
        calls.push(query)
        return { list: [source(101, '0')], total: 1 }
      }
    })
  )
  await state.openDialog()
  for (const invalid of [source(101, '0'), { ...source(), sourceReliable: false }]) {
    state.selectCurrent(invalid)
    await state.chooseAndConfirm(invalid)
    await state.confirm()
  }
  assert.equal(state.staged.value, undefined)
  assert.equal(events.length, 0)
  assert.equal(calls.length, 1)
})

test('origin selector revalidates before confirming and emits the actual fresh source', async (t) => {
  const calls = []
  const { state, events } = setup(
    t,
    'wm/returnsales/ReturnSalesSourceSelect.vue',
    { returnId: 3, lineId: 77 },
    lineDependencies({
      getSourceOptions: async (query) => {
        calls.push(query)
        return { list: [source(101, query.originTransactionId ? '2.000001' : '6')], total: 1 }
      }
    })
  )
  await state.openDialog()
  state.selectCurrent(source())
  await state.confirm()
  assert.equal(calls.at(-1).lineId, 77)
  assert.equal(calls.at(-1).originTransactionId, 101)
  assert.equal(events.find(([name]) => name === 'change')[1].editableAvailableQuantity, '2.000001')
  assert.equal(events.find(([name]) => name === 'update:modelValue')[1], 101)
})

test('pending origin confirmation cannot select into a different return parent', async (t) => {
  const pending = deferred()
  const { state, props, events } = setup(
    t,
    'wm/returnsales/ReturnSalesSourceSelect.vue',
    { returnId: 3 },
    lineDependencies({
      getSourceOptions: async (query) =>
        query.originTransactionId ? pending.promise : { list: [source()], total: 1 }
    })
  )
  await state.openDialog()
  state.selectCurrent(source())
  const confirming = state.confirm()
  await flush()
  props.returnId = 4
  await flush()
  pending.resolve({ list: [source()], total: 1 })
  await confirming
  assert.equal(
    events.some(([name]) => name === 'update:modelValue'),
    false
  )
})

test('a cached selected origin refreshes when its editing line changes', async (t) => {
  const queries = []
  const { state, props } = setup(
    t,
    'wm/returnsales/ReturnSalesSourceSelect.vue',
    { returnId: 3, lineId: 70, modelValue: 101 },
    lineDependencies({
      getSourceOptions: async (query) => {
        queries.push(query)
        return { list: [source(101, query.lineId === 70 ? '2' : '4')], total: 1 }
      }
    })
  )
  await flush()
  assert.equal(state.selected.value.editableAvailableQuantity, '2')
  props.lineId = 71
  await flush()
  assert.equal(state.selected.value.editableAvailableQuantity, '4')
  assert.equal(queries.at(-1).lineId, 71)
})

const qcDependencies = (api) => ({
  '@/api/mes/qc/rqc': { QcRqcApi: api },
  '@/api/mes/md/autocode/record': { AutoCodeRecordApi: {} },
  '@/views/mes/utils/constants': {
    MesQcTypeEnum: { RQC: 4 },
    MesQcStatusEnum: { DRAFT: 0 },
    MesAutoCodeRuleCode: { QC_RQC_CODE: 'RQC' }
  }
})
const qcSource = (lineId = 99, quantity = '99999999999999.123456') => ({
  sourceDocType: 119,
  sourceDocId: 3,
  sourceLineId: lineId,
  sourceDocCode: 'TH-3',
  clientId: 4,
  itemId: 20,
  batchId: 30,
  batchCode: 'ORIGINAL',
  checkQuantity: quantity,
  originTransactionId: 101
})

test('RQC fetches fresh exact return source using only its own QC context API', async (t) => {
  const saved = [],
    queries = []
  const { state } = setup(
    t,
    'qc/rqc/RqcForm.vue',
    {},
    qcDependencies({
      getSourceContext: async (...args) => {
        queries.push(args)
        return qcSource()
      },
      createRqc: async (data) => {
        saved.push(data)
        return 1
      }
    })
  )
  await state.open('create', undefined, {
    sourceDocType: 119,
    sourceDocId: 3,
    sourceLineId: 99,
    itemId: 888,
    batchCode: 'STALE',
    checkQuantity: 1
  })
  assert.deepEqual(queries, [[119, 3, 99]])
  assert.equal(state.formData.value.checkQuantity, '99999999999999.123456')
  assert.equal(state.formData.value.batchCode, 'ORIGINAL')
  assert.equal(state.formData.value.itemId, 20)
  state.formData.value.qualifiedQuantity = '99999999999999.123456'
  state.formData.value.unqualifiedQuantity = '0'
  state.formRef.value = { validate: async () => true }
  await state.submitForm()
  assert.equal(saved[0].checkQuantity, '99999999999999.123456')
  assert.equal(saved[0].sourceDocCode, 'TH-3')
})

test('RQC blocks unavailable sources and nonconserved micro quantities before sending', async (t) => {
  const saved = []
  const { state } = setup(
    t,
    'qc/rqc/RqcForm.vue',
    {},
    qcDependencies({
      getSourceContext: async () => {
        throw new Error('forbidden')
      },
      createRqc: async (data) => saved.push(data)
    })
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  await state.open('create', undefined, { sourceDocType: 119, sourceDocId: 3, sourceLineId: 99 })
  Object.assign(state.formData.value, {
    checkQuantity: '1',
    qualifiedQuantity: '1',
    unqualifiedQuantity: '0'
  })
  await state.submitForm()
  assert.equal(saved.length, 0)
  await state.open('create')
  Object.assign(state.formData.value, {
    checkQuantity: '0.300001',
    qualifiedQuantity: '0.1',
    unqualifiedQuantity: '0.2'
  })
  await state.submitForm()
  assert.equal(saved.length, 0)
})

test('RQC ignores a late source response when another pending task is opened', async (t) => {
  const old = deferred()
  const { state } = setup(
    t,
    'qc/rqc/RqcForm.vue',
    {},
    qcDependencies({
      getSourceContext: async (_type, _doc, line) =>
        line === 99 ? old.promise : qcSource(100, '2.000001')
    })
  )
  const opening = state.open('create', undefined, {
    sourceDocType: 119,
    sourceDocId: 3,
    sourceLineId: 99
  })
  await state.open('create', undefined, { sourceDocType: 119, sourceDocId: 3, sourceLineId: 100 })
  old.resolve(qcSource())
  await opening
  assert.equal(state.formData.value.sourceLineId, 100)
  assert.equal(state.formData.value.checkQuantity, '2.000001')
})

test('RQC editing preserves exact numbers and clones the update payload while source remains read only', async (t) => {
  const saved = [],
    pending = deferred()
  const { state } = setup(
    t,
    'qc/rqc/RqcForm.vue',
    {},
    qcDependencies({
      getRqc: async () => ({
        ...qcSource(99, '0.300001'),
        id: 1,
        qualifiedQuantity: '0.300001',
        unqualifiedQuantity: '0'
      }),
      updateRqc: async (data) => {
        saved.push(data)
        return pending.promise
      }
    })
  )
  await state.open('update', 1)
  assert.equal(state.isFromPendingTask.value, true)
  state.formRef.value = { validate: async () => true }
  const saving = state.submitForm()
  await flush()
  state.formData.value.qualifiedQuantity = '7'
  pending.resolve(true)
  await saving
  assert.equal(saved[0].qualifiedQuantity, '0.300001')
})

test('return putaway preserves the original batch and micro quantity in the actual request', async (t) => {
  const saved = []
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesDetailForm.vue',
    { returnId: 3 },
    {
      ...lineDependencies({
        getReturnSalesLine: async () => ({
          returnId: 3,
          itemId: 20,
          batchId: 30,
          batchCode: 'ORIGINAL',
          qualityStatus: 1,
          quantity: '0.000002'
        })
      }),
      '@/api/mes/wm/returnsales/detail': {
        WmReturnSalesDetailApi: { createReturnSalesDetail: async (data) => saved.push(data) }
      }
    }
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  await state.open('create', 99)
  state.formData.value.quantity = '0.000001'
  await state.submitForm()
  assert.equal(saved[0].batchId, 30)
  assert.equal(saved[0].batchCode, 'ORIGINAL')
  assert.equal(saved[0].quantity, '0.000001')
  assert.equal(saved[0].lineId, 99)
  assert.equal(saved[0].returnId, 3)
})

test('a late putaway source cannot overwrite another line quality or batch', async (t) => {
  const old = deferred()
  const { state } = setup(
    t,
    'wm/returnsales/ReturnSalesDetailForm.vue',
    { returnId: 3 },
    {
      ...lineDependencies({
        getReturnSalesLine: async (id) =>
          id === 99
            ? old.promise
            : {
                returnId: 3,
                itemId: 21,
                batchId: 31,
                batchCode: 'B2',
                qualityStatus: 2,
                quantity: '2'
              }
      }),
      '@/api/mes/wm/returnsales/detail': { WmReturnSalesDetailApi: {} }
    }
  )
  const opening = state.open('create', 99)
  await state.open('create', 100)
  old.resolve({
    returnId: 3,
    itemId: 20,
    batchId: 30,
    batchCode: 'B1',
    qualityStatus: 1,
    quantity: '1'
  })
  await opening
  assert.equal(state.qualityStatus.value, 2)
  assert.equal(state.formData.value.batchCode, 'B2')
})

const formatFile = path.join(__dirname, '../src/views/mes/cost/format.ts')
const formats = evaluate(fs.readFileSync(formatFile, 'utf8'), formatFile, {
  '@/utils/download': { __esModule: true, default: {} }
})
test('return cost view ignores older documents and preserves actual component strings', async (t) => {
  const old = deferred()
  const { state, props } = setup(
    t,
    'cost/components/ReturnSalesCostPanel.vue',
    { returnId: 3 },
    {
      '@/api/mes/cost': {
        CostApi: {
          getReturnSales: async (id) =>
            id === 3
              ? old.promise
              : {
                  returnId: 4,
                  materialAmount: '0.33333334',
                  laborAmount: '0.00000001',
                  overheadAmount: null,
                  costAmount: null,
                  lines: [],
                  originals: []
                }
        }
      },
      '@/utils/permission': { checkPermi: () => true },
      '../format': formats
    }
  )
  props.returnId = 4
  await flush()
  old.resolve({ returnId: 3, materialAmount: '999', lines: [], originals: [] })
  await flush()
  assert.equal(state.cost.value.returnId, 4)
  assert.equal(state.cost.value.materialAmount, '0.33333334')
  assert.equal(state.cost.value.costAmount, null)
  assert.equal(formats.money(state.cost.value.costAmount), '待核定')
  assert.equal(formats.money(state.cost.value.materialAmount), '¥0.33333334')
})

test('revoking cost permission clears the existing return valuation', async (t) => {
  const allowed = vue.ref(true)
  const { state } = setup(
    t,
    'cost/components/ReturnSalesCostPanel.vue',
    { returnId: 3 },
    {
      '@/api/mes/cost': {
        CostApi: { getReturnSales: async () => ({ returnId: 3, costAmount: '1' }) }
      },
      '@/utils/permission': { checkPermi: () => allowed.value },
      '../format': formats
    }
  )
  await flush()
  assert.equal(state.cost.value.costAmount, '1')
  allowed.value = false
  await flush()
  assert.equal(state.cost.value, undefined)
})
