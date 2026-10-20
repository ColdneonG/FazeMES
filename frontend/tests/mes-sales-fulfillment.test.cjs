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
const fulfillmentFile = path.join(__dirname, '../src/views/mes/wm/salesnotice/fulfillment.ts')
const fulfillment = evaluate(fs.readFileSync(fulfillmentFile, 'utf8'), fulfillmentFile, {
  '@/utils/mesQuantity': quantities
})
const flush = async () => {
  await Promise.resolve()
  await vue.nextTick()
  await Promise.resolve()
  await vue.nextTick()
}
function setup(t, name, props, dependencies = {}) {
  const filename = path.join(__dirname, '../src/views/mes/wm', name)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'sales-fulfillment' }).content
  const messages = []
  const events = []
  const component = evaluate(
    source,
    filename,
    {
      vue,
      '@/utils/dict': {},
      '@/utils/mesQuantity': quantities,
      '../fulfillment': fulfillment,
      '@/views/mes/wm/salesnotice/fulfillment': fulfillment,
      ...dependencies
    },
    {
      ...vue,
      useAttrs: () => ({}),
      onMounted: () => {},
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({ warning: (value) => messages.push(value), success: () => {} })
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
  return { state, messages, events }
}
const line = (id = 1, available = '6.000000') => ({
  id,
  noticeId: 10,
  itemId: 20,
  itemCode: 'FG',
  itemName: '成品',
  quantity: 10,
  noticeQuantity: '10.000000',
  shippedQuantity: '4.000000',
  reservedQuantity: '0.000000',
  availableQuantity: available,
  editableAvailableQuantity: available,
  fulfillmentReliable: true,
  oqcCheckFlag: true,
  batchCode: 'BATCH-A'
})

test('choosing a partially shipped notice defaults to its remaining quota', (t) => {
  const { state } = setup(
    t,
    'productsales/ProductSalesLineList.vue',
    { salesId: 3, noticeId: 10, formType: 'update' },
    {
      '@/api/mes/wm/productsales/line': { WmProductSalesLineApi: {} }
    }
  )
  state.handleNoticeLineChange(line())
  assert.equal(state.formData.value.quantity, '6.000000')
})

test('decimal quota comparisons reject one micro-unit over the limit without number rounding', () => {
  const quota = line(1, '99999999999999.123456')
  assert.equal(fulfillment.salesQuantityError('99999999999999.123456', quota), '')
  assert.match(fulfillment.salesQuantityError('99999999999999.123457', quota), /超过/)
  for (const bad of ['0', '-1', '1e2', '0.0000001', '']) {
    assert.notEqual(fulfillment.salesQuantityError(bad, quota), '')
  }
  assert.equal(fulfillment.canSelectNoticeLine(line(1, '0.000000')), false)
  assert.equal(fulfillment.canSelectNoticeLine({ ...quota, fulfillmentReliable: false }), false)
  assert.equal(fulfillment.canSelectNoticeLine({ ...quota, fulfillmentReliable: undefined }), false)
})

test('edit preserves exact own quantity and uses server editable quota without adding it again', async (t) => {
  const saved = {
    ...line(1, '99999999999999.123456'),
    id: 9,
    noticeLineId: 1,
    quantity: '99999999999999.123456',
    salesId: 3
  }
  const calls = []
  const { state } = setup(
    t,
    'productsales/ProductSalesLineList.vue',
    { salesId: 3, noticeId: 10, formType: 'update' },
    {
      '@/api/mes/wm/productsales/line': {
        WmProductSalesLineApi: {
          getProductSalesLine: async () => saved,
          updateProductSalesLine: async (value) => calls.push(value),
          getProductSalesLinePage: async () => ({ list: [], total: 0 })
        }
      }
    }
  )
  await state.openForm('update', 9)
  state.formRef.value = { validate: async () => true }
  assert.equal(state.formData.value.quantity, saved.quantity)
  await state.submitForm()
  assert.equal(calls[0].quantity, saved.quantity)
  assert.equal(calls[0].noticeLineId, 1)
  assert.equal(calls[0].salesId, 3)
  assert.equal('editableAvailableQuantity' in calls[0], false)
  assert.equal('shippedQuantity' in calls[0], false)
})

test('submit blocks over-quota and unreliable history even with a permissive form stub', async (t) => {
  const calls = []
  const { state, messages } = setup(
    t,
    'productsales/ProductSalesLineList.vue',
    { salesId: 3, noticeId: 10, formType: 'update' },
    {
      '@/api/mes/wm/productsales/line': {
        WmProductSalesLineApi: { createProductSalesLine: async (value) => calls.push(value) }
      }
    }
  )
  state.formRef.value = { validate: async () => true }
  state.handleNoticeLineChange(line())
  state.formData.value.quantity = '6.000001'
  await state.submitForm()
  assert.equal(calls.length, 0)
  assert.match(messages.at(-1), /超过/)
  state.handleNoticeLineChange({
    ...line(),
    fulfillmentReliable: false,
    fulfillmentWarning: '原出库流水缺失'
  })
  state.formData.value.quantity = '1'
  await state.submitForm()
  assert.equal(calls.length, 0)
  assert.equal(messages.at(-1), '原出库流水缺失')
})

test('radio, row click, double click, checkbox and preselection all reject unavailable rows', async (t) => {
  const zero = line(1, '0.000000')
  const bad = { ...line(2), fulfillmentReliable: false }
  const good = line(3)
  const props = vue.reactive({ noticeId: 10, editingSalesLineId: 8, multiple: false })
  const { state, events } = setup(
    t,
    'salesnotice/components/WmSalesNoticeLineSelectDialog.vue',
    props,
    {
      '@/api/mes/wm/salesnotice/line': {
        WmSalesNoticeLineApi: {
          getSalesNoticeLinePage: async () => ({ list: [zero, bad, good], total: 3 }),
          getSalesNoticeLine: async () => {
            throw new Error('should not refresh an unavailable selection')
          }
        }
      }
    }
  )
  const toggled = []
  state.tableRef.value = {
    clearSelection: () => {},
    toggleRowSelection: (row) => toggled.push(row.id)
  }
  await state.open([1, 2])
  assert.equal(state.currentRadioRow.value, undefined)
  for (const row of [zero, bad]) {
    state.handleRowClick(row)
    state.handleRadioChange(row)
    state.handleRowDblClick(row)
  }
  assert.equal(state.currentRadioRow.value, undefined)
  assert.equal(events.length, 0)
  props.multiple = true
  await state.open([1, 2, 3])
  assert.deepEqual(toggled, [3])
  state.handleSelectionChange([zero, bad, good])
  assert.deepEqual(
    Array.from(state.selectedRows.value, (row) => row.id),
    [3]
  )
  state.handleRowDblClick(zero)
  assert.deepEqual(toggled, [3])
})

test('confirmation refreshes every selected row and refuses concurrently exhausted quota', async (t) => {
  const good = line()
  const calls = []
  let refreshed = line(1, '0.000000')
  const { state, events, messages } = setup(
    t,
    'salesnotice/components/WmSalesNoticeLineSelectDialog.vue',
    { noticeId: 10, editingSalesLineId: 8, multiple: false },
    {
      '@/api/mes/wm/salesnotice/line': {
        WmSalesNoticeLineApi: {
          getSalesNoticeLinePage: async () => ({ list: [good], total: 1 }),
          getSalesNoticeLine: async (...args) => {
            calls.push(args)
            return refreshed
          }
        }
      }
    }
  )
  await state.open()
  state.handleRowClick(good)
  await state.confirmSelect()
  assert.equal(events.length, 0)
  assert.equal(state.dialogVisible.value, true)
  assert.match(messages.at(-1), /已变化/)
  assert.deepEqual(calls, [[1, 8]])
  refreshed = line(1, '2.500001')
  state.handleRowClick(good)
  await state.confirmSelect()
  assert.equal(events[0][1][0].editableAvailableQuantity, '2.500001')
  assert.equal(state.dialogVisible.value, false)
})

test('stale page responses cannot leak selections across notice context', async (t) => {
  const pending = []
  const props = vue.reactive({ noticeId: 10, multiple: false })
  const { state, events } = setup(
    t,
    'salesnotice/components/WmSalesNoticeLineSelectDialog.vue',
    props,
    {
      '@/api/mes/wm/salesnotice/line': {
        WmSalesNoticeLineApi: {
          getSalesNoticeLinePage: () => new Promise((resolve) => pending.push(resolve)),
          getSalesNoticeLine: async () => line()
        }
      }
    }
  )
  const first = state.open()
  await flush()
  props.noticeId = 11
  await flush()
  pending[1]({ list: [{ ...line(2), noticeId: 11 }], total: 1 })
  await vue.nextTick()
  pending[0]({ list: [line()], total: 1 })
  await first
  assert.equal(state.list.value[0].noticeId, 11)
  assert.equal(events.length, 0)
})

test('a pending confirmation cannot select a row after changing the notice', async (t) => {
  let resolveConfirmation
  const props = vue.reactive({ noticeId: 10, multiple: false })
  const { state, events } = setup(
    t,
    'salesnotice/components/WmSalesNoticeLineSelectDialog.vue',
    props,
    {
      '@/api/mes/wm/salesnotice/line': {
        WmSalesNoticeLineApi: {
          getSalesNoticeLinePage: async () => ({
            list: [{ ...line(), noticeId: props.noticeId }],
            total: 1
          }),
          getSalesNoticeLine: () =>
            new Promise((resolve) => {
              resolveConfirmation = resolve
            })
        }
      }
    }
  )
  await state.open()
  state.handleRowClick(state.list.value[0])
  const confirmation = state.confirmSelect()
  props.noticeId = 11
  await flush()
  resolveConfirmation(line())
  await confirmation
  assert.equal(events.length, 0)
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.list.value[0].noticeId, 11)
})

test('selector refreshes cached ID on edit context changes and discards old responses', async (t) => {
  const pending = []
  const calls = []
  const props = vue.reactive({ modelValue: 1, noticeId: 10, editingSalesLineId: 8 })
  const { state } = setup(t, 'salesnotice/components/WmSalesNoticeLineSelect.vue', props, {
    '@element-plus/icons-vue': {},
    '@/api/mes/wm/salesnotice/line': {
      WmSalesNoticeLineApi: {
        getSalesNoticeLine: (...args) => {
          calls.push(args)
          return new Promise((resolve) => pending.push(resolve))
        }
      }
    }
  })
  props.editingSalesLineId = 9
  await vue.nextTick()
  pending[1](line(1, '10.000000'))
  await vue.nextTick()
  pending[0](line(1, '6.000000'))
  await vue.nextTick()
  assert.equal(state.selectedItem.value.editableAvailableQuantity, '10.000000')
  assert.deepEqual(calls, [
    [1, 8],
    [1, 9]
  ])
  const again = state.resolveItemById(1)
  pending[2](line(1, '4.000000'))
  await again
  assert.equal(state.selectedItem.value.editableAvailableQuantity, '4.000000')
})

test('picking stock keeps the line batch filter and leaves quantity for explicit entry', async (t) => {
  const { state } = setup(
    t,
    'productsales/ProductSalesDetailForm.vue',
    { salesId: 3 },
    {
      '@/api/mes/wm/productsales/detail': { WmProductSalesDetailApi: {} }
    }
  )
  await state.open('create', 4, 20, undefined, 7)
  state.handleStockChange({ id: 2, quantity: 100, batchId: 7, batchCode: 'A' })
  assert.equal(state.formData.value.quantity, '')
  state.formData.value.quantity = '0.000001'
  state.handleStockChange(undefined)
  assert.equal(state.formData.value.quantity, '')
  assert.equal(state.formData.value.batchId, 7)
})

const oqcDependencies = (api, sales = {}) => ({
  '@/api/mes/qc/oqc': {
    QcOqcApi: {
      ...api,
      getSourceContext: sales.getProductSalesLine
        ? async (docId, lineId) => {
            const row = await sales.getProductSalesLine(lineId)
            return {
              sourceDocType: 118,
              sourceDocId: row.salesId,
              sourceLineId: lineId,
              sourceDocCode: 'XS',
              clientId: 4,
              itemId: row.itemId,
              batchCode: row.batchCode,
              outQuantity: row.quantity
            }
          }
        : undefined
    }
  },
  '@/api/mes/md/autocode/record': { AutoCodeRecordApi: {} },
  '@/views/mes/utils/constants': {
    MesQcTypeEnum: { OQC: 3 },
    MesQcStatusEnum: { DRAFT: 0 },
    MesAutoCodeRuleCode: { QC_OQC_CODE: 'OQC' }
  }
})

test('OQC creation reloads the real sales batch and exact quantity for pending desktop/mobile tasks', async (t) => {
  const calls = []
  const value = '99999999999999.123456'
  const { state } = setup(
    t,
    '../qc/oqc/OqcForm.vue',
    { terminal: false },
    oqcDependencies(
      {
        createOqc: async (data) => calls.push(data)
      },
      {
        getProductSalesLine: async (id) => {
          assert.equal(id, 99)
          return { salesId: 3, itemId: 20, batchCode: 'ACTUAL-BATCH', quantity: value }
        }
      }
    )
  )
  await state.open('create', undefined, {
    sourceDocType: 118,
    sourceDocId: 3,
    sourceLineId: 99,
    clientId: 4,
    outQuantity: Number(value)
  })
  await flush()
  assert.equal(state.formData.value.outQuantity, value)
  assert.equal(state.formData.value.batchCode, 'ACTUAL-BATCH')
  assert.equal(state.formData.value.checkQuantity, value)
  state.formData.value.qualifiedQuantity = value
  state.formData.value.unqualifiedQuantity = '0'
  state.formRef.value = { validate: async () => true }
  await state.submitForm()
  assert.equal(calls[0].outQuantity, value)
  assert.equal(calls[0].checkQuantity, value)
  assert.equal(calls[0].sourceDocId, 3)
  assert.equal(calls[0].sourceLineId, 99)
})

test('OQC blocks unavailable source and decimal conservation errors before sending requests', async (t) => {
  const calls = []
  const { state, messages } = setup(
    t,
    '../qc/oqc/OqcForm.vue',
    { terminal: false },
    oqcDependencies(
      {
        createOqc: async (data) => calls.push(data)
      },
      {
        getProductSalesLine: async () => {
          throw new Error('missing source')
        }
      }
    )
  )
  state.formRef.value = { validate: async () => true, resetFields: () => {} }
  await state.open('create', undefined, { sourceDocType: 118, sourceDocId: 3, sourceLineId: 99 })
  assert.match(state.sourceError.value, /无法核对/)
  Object.assign(state.formData.value, {
    outQuantity: '1',
    checkQuantity: '1',
    qualifiedQuantity: '1',
    unqualifiedQuantity: '0'
  })
  await state.submitForm()
  assert.equal(calls.length, 0)
  await state.open('create')
  Object.assign(state.formData.value, {
    outQuantity: '0.3',
    checkQuantity: '0.3',
    qualifiedQuantity: '0.1',
    unqualifiedQuantity: '0.1'
  })
  await state.submitForm()
  assert.equal(calls.length, 0)
  assert.match(messages.at(-1), /合计/)
  state.formData.value.unqualifiedQuantity = '0.2'
  await state.submitForm()
  assert.equal(calls[0].checkQuantity, '0.3')
})

test('OQC ignores an older source response when opening another pending task', async (t) => {
  const pending = []
  const { state } = setup(
    t,
    '../qc/oqc/OqcForm.vue',
    { terminal: false },
    oqcDependencies(
      {},
      {
        getProductSalesLine: () => new Promise((resolve) => pending.push(resolve))
      }
    )
  )
  const first = state.open('create', undefined, {
    sourceDocType: 118,
    sourceDocId: 3,
    sourceLineId: 99
  })
  const second = state.open('create', undefined, {
    sourceDocType: 118,
    sourceDocId: 4,
    sourceLineId: 100
  })
  pending[1]({ salesId: 4, itemId: 21, batchCode: 'NEW', quantity: '0.000001' })
  await second
  pending[0]({ salesId: 3, itemId: 20, batchCode: 'OLD', quantity: '10' })
  await first
  assert.equal(state.formData.value.sourceDocId, 4)
  assert.equal(state.formData.value.batchCode, 'NEW')
  assert.equal(state.formData.value.outQuantity, '0.000001')
  assert.equal(state.sourceError.value, '')
})

test('OQC edit preserves micro quantities and source fields in the actual update payload', async (t) => {
  const calls = []
  const saved = {
    id: 5,
    sourceDocType: 118,
    sourceDocId: 3,
    sourceLineId: 99,
    outQuantity: '0.000001',
    checkQuantity: '0.000001',
    qualifiedQuantity: '0.000001',
    unqualifiedQuantity: '0.000000'
  }
  const { state } = setup(
    t,
    '../qc/oqc/OqcForm.vue',
    { terminal: false },
    oqcDependencies({
      getOqc: async () => ({ ...saved }),
      updateOqc: async (data) => calls.push(data)
    })
  )
  await state.open('update', 5)
  assert.equal(state.isFromPendingTask.value, true)
  state.formRef.value = { validate: async () => true }
  await state.submitForm()
  assert.equal(calls[0].qualifiedQuantity, '0.000001')
  assert.equal(calls[0].sourceLineId, 99)
})
