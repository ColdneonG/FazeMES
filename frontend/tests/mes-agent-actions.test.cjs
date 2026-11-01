const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function setup(
  t,
  api = {},
  answer = { proposal: { kind: 'CREATE_INCIDENT', title: '缺料' }, evidence: {}, turnId: 42 },
  allowed
) {
  const filename = path.join(__dirname, '../src/views/mes/insight/AgentResult.vue')
  const parsed = parse(fs.readFileSync(filename, 'utf8'), { filename })
  assert.equal(parsed.errors.length, 0, 'Agent template must parse before component tests run')
  const source = compileScript(parsed.descriptor, {
    id: 'agent-actions'
  }).content
  const module = { exports: {} }
  const exports = module.exports
  const downloads = []
  const routes = []
  const events = []
  const incidentRequests = []
  const deps = {
    vue,
    'vue-router': { useRouter: () => ({ push: (route) => routes.push(route) }) },
    '@/api/mes/insight/agent': { AgentApi: api },
    '@/api/mes/insight': {
      InsightApi: {
        incidents: async () => {
          incidentRequests.push(true)
          return { owners: [{ id: 10, name: '操作者' }] }
        }
      }
    },
    '@/utils/permission': {
      checkPermi: (permissions) =>
        !allowed || permissions.some((permission) => allowed.value.has(permission))
    },
    '@/views/mes/cost/navigation': {
      openCostCenter: (router, context) => router.push({ path: '/mes/md/cost', query: context })
    },
    '@/utils/download': { __esModule: true, default: { excel: (...args) => downloads.push(args) } }
  }
  const formatFile = path.join(__dirname, '../src/views/mes/cost/format.ts')
  const formatModule = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(fs.readFileSync(formatFile, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module: formatModule,
      exports: formatModule.exports,
      require: (name) => deps[name] || require(name)
    },
    { filename: formatFile }
  )
  deps['@/views/mes/cost/format'] = formatModule.exports
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports,
      require: (name) => deps[name] || require(name)
    },
    { filename }
  )
  const scope = vue.effectScope()
  const state = scope.run(() =>
    module.exports.default.setup(vue.reactive({ answer }), {
      expose() {},
      emit: (...args) => events.push(args)
    })
  )
  t.after(() => scope.stop())
  return { state, downloads, routes, events, incidentRequests }
}

test('opening and preparing a proposal cannot execute business; confirmation sends only the reviewed nonce', async (t) => {
  const executions = []
  const { state } = setup(t, {
    prepare: async () => ({
      id: 'plan-1',
      token: 'nonce-1',
      payload: { title: '已核对标题' },
      status: 'PENDING'
    }),
    execute: async (data) => {
      executions.push(data)
      return { businessId: 101, status: 'SUCCEEDED', message: '已执行' }
    }
  })
  await state.openReview()
  assert.equal(executions.length, 0)
  await state.prepare()
  assert.equal(executions.length, 0)
  state.form.title = '预览之后的改动不能偷换执行内容'
  await state.execute()
  assert.equal(JSON.stringify(executions), '[{"id":"plan-1","token":"nonce-1"}]')
  assert.equal(state.receipt.value.businessId, 101)
})

test('an uncertain execution retains the same confirmation for an idempotent retry', async (t) => {
  const calls = []
  let count = 0
  const { state } = setup(t, {
    prepare: async () => ({ id: 'plan-2', token: 'nonce-2', payload: {}, status: 'PENDING' }),
    execute: async (data) => {
      calls.push(data)
      if (count++ === 0) throw new Error('timeout')
      return { businessId: 102, status: 'SUCCEEDED' }
    }
  })
  await state.prepare()
  await state.execute()
  assert.equal(state.uncertain.value, true)
  await state.openReview()
  await state.execute()
  assert.equal(calls[0].id, calls[1].id)
  assert.equal(calls[0].token, calls[1].token)
  assert.equal(state.receipt.value.businessId, 102)
})

test('report downloads use the persisted answer id and do not request any business execution', async (t) => {
  const calls = []
  const { state, downloads } = setup(
    t,
    {
      report: async (turnId) => {
        calls.push(turnId)
        return 'xlsx-bytes'
      }
    },
    {
      turnId: 77,
      evidence: { kind: 'AGENT_TABLE', module: 'WORKORDER', list: [], total: 0 }
    }
  )
  await state.downloadReport()
  assert.equal(JSON.stringify(calls), '[77]')
  assert.equal(downloads[0][1], '生产工单报表-77.xlsx')
})

const businessOperations = [
  { kind: 'CONFIRM_WORKORDER', orderCode: 'WO-001' },
  { kind: 'CANCEL_WORKORDER', orderCode: 'WO-001' },
  { kind: 'FINISH_WORKORDER', orderCode: 'WO-001' },
  { kind: 'SETTLE_WORKORDER', orderCode: 'WO-001' },
  { kind: 'UPDATE_DEVICE_STATUS', deviceCode: 'DV-001', deviceStatus: 3 },
  { kind: 'UPDATE_PURCHASE_PRICE', itemCode: 'BROKY-R16', purchasePrice: '64.000001' }
]
for (const proposal of businessOperations) {
  test(`${proposal.kind} prepares and executes the reviewed operation without requiring incident ownership`, async (t) => {
    const prepared = []
    const executed = []
    const { state, events, incidentRequests } = setup(
      t,
      {
        prepare: async (operation, turn) => {
          prepared.push({ operation, turn })
          return { id: 'business-preview', token: 'business-nonce', payload: operation }
        },
        execute: async (confirmation) => {
          executed.push(confirmation)
          return { kind: proposal.kind, businessId: 123, message: '已完成业务操作' }
        }
      },
      { proposal, evidence: {}, turnId: 57 }
    )
    await state.openReview()
    assert.equal(state.isIncident.value, false)
    assert.equal(incidentRequests.length, 0)
    assert.equal(executed.length, 0)
    await state.prepare()
    assert.equal(prepared[0].turn, 57)
    assert.equal(prepared[0].operation.kind, proposal.kind)
    assert.equal(prepared[0].operation.orderCode, proposal.orderCode)
    assert.equal(prepared[0].operation.deviceCode, proposal.deviceCode)
    assert.equal(prepared[0].operation.deviceStatus, proposal.deviceStatus)
    assert.equal(prepared[0].operation.itemCode, proposal.itemCode)
    assert.equal(prepared[0].operation.purchasePrice, proposal.purchasePrice)
    assert.equal(executed.length, 0)
    state.form.orderCode = 'WO-OTHER'
    state.form.deviceStatus = 1
    await state.execute()
    assert.equal(JSON.stringify(executed), '[{"id":"business-preview","token":"business-nonce"}]')
    assert.equal(state.receipt.value.kind, proposal.kind)
    assert.equal(events[0][0], 'executed')
  })
}

test('each business operation needs all module permissions and stops executing after revocation', async (t) => {
  const permissions = vue.ref(new Set(['mes:pro-work-order:query']))
  const calls = []
  const { state } = setup(
    t,
    {
      prepare: async () => {
        calls.push('prepare')
        return { id: 'preview', token: 'nonce', payload: {} }
      },
      execute: async () => calls.push('execute')
    },
    { proposal: { kind: 'CONFIRM_WORKORDER', orderCode: 'WO-001' }, evidence: {}, turnId: 9 },
    permissions
  )
  assert.equal(state.canExecute.value, false)
  await state.openReview()
  await state.prepare()
  assert.equal(calls.length, 0)
  permissions.value.add('mes:pro-work-order:update')
  assert.equal(state.canExecute.value, true)
  await state.openReview()
  await state.prepare()
  permissions.value.delete('mes:pro-work-order:update')
  await state.execute()
  assert.deepEqual(calls, ['prepare'])
})

test('settlement review preserves exact monetary strings and shows named states', async (t) => {
  const { state } = setup(
    t,
    {},
    {
      proposal: { kind: 'SETTLE_WORKORDER', orderCode: 'WO-001' },
      evidence: {},
      turnId: 9
    }
  )
  state.preview.value = {
    payload: {
      kind: 'SETTLE_WORKORDER',
      orderCode: 'WO-001',
      fromStatus: 2,
      fromStatusName: '已完成',
      status: 'SETTLED',
      statusName: '已结算',
      actualTotalCost: '999999999999.000001',
      actualUnitCost: '0.000001',
      producedQuantity: '1.000001'
    }
  }
  const fields = state.reviewFields.value
  assert.equal(fields.find((field) => field.key === 'actualTotalCost').value, '999999999999.000001')
  assert.equal(fields.find((field) => field.key === 'actualUnitCost').value, '0.000001')
  assert.equal(fields.find((field) => field.key === 'statusName').value, '已结算')
  assert.equal(
    fields.some((field) => field.key === 'status' || field.key === 'fromStatus'),
    false
  )
  assert.equal(
    fields.some((field) => field.label.includes('异常')),
    false
  )
})

test('execution receipts navigate to the corresponding business page including reopened history', async (t) => {
  for (const [kind, destination] of [
    ['CONFIRM_WORKORDER', { name: 'MesProWorkOrder' }],
    ['CANCEL_WORKORDER', { name: 'MesProWorkOrder' }],
    ['FINISH_WORKORDER', { name: 'MesProWorkOrder' }],
    ['UPDATE_DEVICE_STATUS', { name: 'MesDvMachinery' }],
    ['SETTLE_WORKORDER', { path: '/mes/md/cost', query: { workorderId: 123 } }],
    ['UPDATE_PURCHASE_PRICE', { path: '/mes/md/cost', query: { itemId: 123 } }],
    ['MOVE_INCIDENT', '/mes/insight?tab=incidents']
  ]) {
    const { state, routes } = setup(
      t,
      {},
      {
        evidence: {},
        turnId: 9,
        executionReceipt: { kind, businessId: 123, message: '已执行' }
      }
    )
    await state.openReceipt()
    assert.equal(JSON.stringify(routes), JSON.stringify([destination]))
  }
})

test('purchase price preparation preserves exact decimal strings and accepts a material name', async (t) => {
  const calls = []
  const { state, incidentRequests } = setup(
    t,
    {
      prepare: async (operation) => {
        calls.push(operation)
        return { id: 'price-preview', token: 'price-nonce', payload: operation }
      }
    },
    {
      proposal: {
        kind: 'UPDATE_PURCHASE_PRICE',
        itemName: 'Broky电机采购模块',
        purchasePrice: '999999999999.999999'
      },
      evidence: {},
      turnId: 9
    }
  )
  await state.openReview()
  assert.equal(incidentRequests.length, 0)
  await state.prepare()
  assert.equal(calls[0].itemName, 'Broky电机采购模块')
  assert.equal(calls[0].itemCode, undefined)
  assert.equal(calls[0].purchasePrice, '999999999999.999999')
  assert.equal(typeof calls[0].purchasePrice, 'string')
  state.resetPreview()
  state.form.purchasePrice = '0'
  await state.prepare()
  assert.equal(calls[1].purchasePrice, '0')
})

test('purchase price input rejects numbers and invalid decimal text before requesting a preview', async (t) => {
  const calls = []
  const { state } = setup(
    t,
    { prepare: async (operation) => calls.push(operation) },
    {
      proposal: { kind: 'UPDATE_PURCHASE_PRICE', itemCode: 'BROKY-R16' },
      evidence: {},
      turnId: 9
    }
  )
  await state.openReview()
  for (const value of [
    undefined,
    64,
    '',
    '-1',
    '1e3',
    '1,000',
    '64.0000001',
    '1000000000000',
    ' 64 ',
    '64元'
  ]) {
    state.form.purchasePrice = value
    await state.prepare()
    assert.equal(calls.length, 0)
    assert.notEqual(state.error.value, '')
  }
})

test('purchase price update requires both cost permissions and blocks confirmation after revocation', async (t) => {
  const permissions = vue.ref(new Set(['mes:cost:update']))
  const calls = []
  const { state } = setup(
    t,
    {
      prepare: async () => {
        calls.push('prepare')
        return { id: 'price-preview', token: 'price-nonce', payload: {} }
      },
      execute: async () => calls.push('execute')
    },
    {
      proposal: { kind: 'UPDATE_PURCHASE_PRICE', itemCode: 'BROKY-R16', purchasePrice: '64' },
      evidence: {},
      turnId: 9
    },
    permissions
  )
  assert.equal(state.canExecute.value, false)
  await state.prepare()
  assert.equal(calls.length, 0)
  permissions.value.add('mes:cost:query')
  await state.openReview()
  await state.prepare()
  assert.equal(state.canExecute.value, true)
  permissions.value.delete('mes:cost:update')
  assert.equal(state.canExecute.value, false)
  await state.execute()
  assert.deepEqual(calls, ['prepare'])
})

test('purchase price review shows an unset old price, exact new price, material and tax basis', async (t) => {
  const { state } = setup(
    t,
    {},
    {
      proposal: {
        kind: 'UPDATE_PURCHASE_PRICE',
        itemName: 'Broky电机采购模块',
        purchasePrice: '64'
      },
      evidence: {},
      turnId: 9
    }
  )
  state.preview.value = {
    payload: {
      kind: 'UPDATE_PURCHASE_PRICE',
      itemId: 123,
      itemCode: 'BROKY-R16',
      itemName: 'Broky电机采购模块',
      oldPurchasePrice: null,
      purchasePrice: '64.000001',
      priceVersion: 0,
      currency: 'CNY',
      taxBasis: 'EXCLUSIVE'
    }
  }
  const fields = state.reviewFields.value
  assert.equal(fields.find((field) => field.key === 'oldPurchasePrice').value, '未设置')
  assert.equal(fields.find((field) => field.key === 'purchasePrice').value, '64.000001')
  assert.equal(fields.find((field) => field.key === 'itemCode').value, 'BROKY-R16')
  assert.equal(fields.find((field) => field.key === 'itemName').value, 'Broky电机采购模块')
  assert.equal(fields.find((field) => field.key === 'currency').value, '人民币（CNY）')
  assert.equal(fields.find((field) => field.key === 'taxBasis').value, '不含税')
  state.preview.value.payload.oldPurchasePrice = '0.000000'
  assert.equal(
    state.reviewFields.value.find((field) => field.key === 'oldPurchasePrice').value,
    '0.000000'
  )
  assert.equal(state.receiptLinkLabel.value, '查看物料价格与成本')
})
