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
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022
      }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || (name.endsWith('.vue') ? {} : require(name)),
      Error,
      ...globals
    },
    { filename }
  )
  return module.exports
}

test('model management exposes actual persisted budget and audit requests', async () => {
  const filename = path.join(__dirname, '../src/api/mes/assistant/index.ts')
  const calls = []
  const request = Object.fromEntries(
    ['get', 'put', 'post'].map((method) => [
      method,
      async (data) => {
        calls.push({ method, ...data })
        return { configured: false }
      }
    ])
  )
  const { AssistantApi } = evaluate(fs.readFileSync(filename, 'utf8'), filename, {
    '@/config/axios': { __esModule: true, default: request }
  })
  assert.equal(
    typeof AssistantApi.usagePolicy,
    'function',
    'saved model credentials must have a separate budget policy'
  )
  await AssistantApi.usagePolicy()
  await AssistantApi.usageSummary()
  await AssistantApi.usagePage({ pageNo: 2, pageSize: 20, purpose: 'JEV_TRIAGE' })
  await AssistantApi.saveUsagePolicy({ expectedVersion: 3, reason: 'explicit admin change' })
  await AssistantApi.reconcileUsage({
    usageId: 'request-id',
    expectedVersion: 2,
    action: 'CLOSE_AS_UNKNOWN',
    reason: 'reviewed evidence'
  })
  assert.deepEqual(
    calls.map((c) => [c.method, c.url]),
    [
      ['get', '/mes/assistant/usage-policy'],
      ['get', '/mes/assistant/usage-summary'],
      ['get', '/mes/assistant/usage-page'],
      ['put', '/mes/assistant/usage-policy'],
      ['post', '/mes/assistant/usage-reconcile']
    ]
  )
  assert.equal(calls[2].params.purpose, 'JEV_TRIAGE')
  assert.equal(calls[4].data.expectedVersion, 2)
})

const helperFile = path.join(__dirname, '../src/views/mes/assistant/usagePolicy.ts')
const helpers = evaluate(fs.readFileSync(helperFile, 'utf8'), helperFile)
const clone = (value) => JSON.parse(JSON.stringify(value))
const limits = () => ({
  dayCalls: '9007199254740993',
  monthCalls: '9223372036854775807',
  dayTokens: '100000',
  monthTokens: '1000000',
  dayAmount: null,
  monthAmount: null,
  maxConcurrency: 2,
  callsPerMinute: 10
})
const policy = (version = 4) => ({
  configured: true,
  version,
  enabled: true,
  tokenScope: 'CHAT',
  currency: 'CNY',
  timezone: 'Asia/Shanghai',
  tenantLimits: limits(),
  defaultUserLimits: limits(),
  rates: [],
  coverageStart: null,
  historicalUsageKnown: false,
  updatedAt: null
})
const usageRow = () => ({
  id: 'uuid-1',
  version: 5,
  canReconcile: true,
  channel: 'CHAT',
  actualTotalTokens: null
})
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}
const flush = async () => {
  await Promise.resolve()
  await vue.nextTick()
  await Promise.resolve()
  await vue.nextTick()
}
function setup(t, file, api = {}, permission = vue.ref(true)) {
  const filename = path.join(__dirname, '../src/views/mes/assistant', file)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const events = [],
    exposed = {}
  const component = evaluate(compileScript(descriptor, { id: 'usage' }).content, filename, {
    vue: { ...vue, onMounted: () => {}, onBeforeUnmount: () => {} },
    '@/api/mes/assistant': { AssistantApi: api },
    './usagePolicy': helpers,
    '@/utils/permission': { checkPermi: () => permission.value }
  }).default
  const scope = vue.effectScope()
  const state = scope.run(() =>
    component.setup(
      {},
      { expose: (value) => Object.assign(exposed, value), emit: (...args) => events.push(args) }
    )
  )
  t.after(() => scope.stop())
  return { state, events, permission, exposed }
}

test('budget retains exact BIGINT and eight decimal amounts and strips injected fields', () => {
  const form = helpers.policyForm(policy())
  form.tenantLimits.dayAmount = '1234567890123456.12345678'
  form.defaultUserLimits.monthAmount = '0'
  form.reason = '明确预算'
  form.tenantId = 88
  form.userId = 77
  form.tenantLimits.arbitraryLimit = 'unlimited'
  const request = helpers.policyRequest(form)
  assert.equal(request.tenantLimits.dayCalls, '9007199254740993')
  assert.equal(request.tenantLimits.monthCalls, '9223372036854775807')
  assert.equal(request.tenantLimits.dayAmount, '1234567890123456.12345678')
  assert.equal(request.defaultUserLimits.monthAmount, '0')
  assert.equal(request.tenantId, undefined)
  assert.equal(request.tenantLimits.arbitraryLimit, undefined)
})

test('budget validation rejects negative, exponent, fractional, overflow and zero integer quotas', () => {
  for (const invalid of ['0', '-1', '1e9', '1.0', '9223372036854775808', '01', ' 1']) {
    const form = helpers.policyForm(policy())
    form.reason = 'change'
    form.tenantLimits.dayCalls = invalid
    assert.throws(() => helpers.policyRequest(form), /正整数/)
  }
  for (const invalid of ['-1', '1e3', '.5', '1.123456789', '10000000000000000'])
    assert.throws(() => helpers.money(invalid), /金额/)
})

test('budget saves disabled monetary limits as null and exact zero price only after explicit input', () => {
  const form = helpers.policyForm(policy())
  form.reason = 'change'
  form.rates = [
    {
      channel: 'CHAT',
      provider: 'ollama',
      model: 'qwen3:8b',
      inputPerMillion: '0',
      outputPerMillion: '0',
      perCallReservationAmount: '999'
    }
  ]
  const request = helpers.policyRequest(form)
  assert.equal(request.tenantLimits.dayAmount, null)
  assert.equal(request.rates[0].inputPerMillion, '0')
  assert.equal(request.rates[0].perCallReservationAmount, null)
  form.rates[0].inputPerMillion = ''
  assert.throws(() => helpers.policyRequest(form), /金额/)
})

test('price cards enforce exact model identity, channel and duplicate rules', () => {
  const form = helpers.policyForm(policy())
  form.reason = 'change'
  const rate = {
    channel: 'JEV',
    provider: 'typesafe',
    model: 'Jev-1',
    inputPerMillion: null,
    outputPerMillion: null,
    perCallReservationAmount: '0.00500001'
  }
  form.rates = [rate, { ...rate }]
  assert.throws(() => helpers.policyRequest(form), /只能配置一张/)
  form.rates = [{ ...rate, provider: 'openai' }]
  assert.throws(() => helpers.policyRequest(form), /不匹配/)
  form.rates = [{ ...rate, model: 'wrong model' }]
  assert.throws(() => helpers.policyRequest(form), /准确模型/)
})

test('policy editor clones saved data and unknown amounts never display as zero', () => {
  const original = policy()
  const form = helpers.policyForm(original)
  form.tenantLimits.dayCalls = '5'
  assert.equal(original.tenantLimits.dayCalls, '9007199254740993')
  assert.equal(helpers.measured(null), '待核定')
  assert.equal(helpers.measured('0'), '0')
  assert.equal(
    helpers.measured('1014570924054025338769998985429.07594597'),
    '1014570924054025338769998985429.07594597'
  )
  assert.equal(helpers.limitText(null), '未启用')
  const blank = helpers.policyForm({
    ...original,
    configured: false,
    tenantLimits: null,
    defaultUserLimits: null
  })
  blank.reason = 'change'
  assert.throws(() => helpers.policyRequest(blank), /正整数/)
})

test('close unknown and not dispatched actions do not accidentally send hidden actual values', () => {
  for (const action of ['CLOSE_AS_UNKNOWN', 'CONFIRM_NOT_DISPATCHED']) {
    const data = helpers.reconcileRequest(
      usageRow(),
      action,
      '999',
      '999',
      '999',
      'review evidence'
    )
    assert.equal(data.expectedVersion, 5)
    assert.equal(data.inputTokens, null)
    assert.equal(data.outputTokens, null)
    assert.equal(data.confirmedAmount, null)
  }
  assert.throws(
    () =>
      helpers.reconcileRequest(
        { ...usageRow(), canReconcile: false },
        'CLOSE_AS_UNKNOWN',
        '',
        '',
        '',
        'reason'
      ),
    /不可核定/
  )
  assert.throws(
    () => helpers.reconcileRequest(usageRow(), 'CLOSE_AS_UNKNOWN', '', '', '', ' '),
    /核定依据/
  )
})

test('manual actual requires both CHAT token measurements, preserves zero and supports JEV money evidence', () => {
  assert.throws(
    () =>
      helpers.reconcileRequest(
        usageRow(),
        'CONFIRM_ACTUAL',
        '9223372036854775807',
        '1',
        '',
        'reason'
      ),
    /合计超过/
  )
  assert.throws(
    () => helpers.reconcileRequest(usageRow(), 'CONFIRM_ACTUAL', '1', '', '', 'reason'),
    /Token/
  )
  const zero = helpers.reconcileRequest(
    usageRow(),
    'CONFIRM_ACTUAL',
    '0',
    '0',
    '0',
    'provider proof'
  )
  assert.equal(zero.inputTokens, '0')
  assert.equal(zero.outputTokens, '0')
  assert.equal(zero.confirmedAmount, '0')
  const jev = helpers.reconcileRequest(
    { ...usageRow(), channel: 'JEV' },
    'CONFIRM_ACTUAL',
    '',
    '',
    '0.12345678',
    'invoice'
  )
  assert.equal(jev.inputTokens, null)
  assert.equal(jev.confirmedAmount, '0.12345678')
  assert.throws(
    () =>
      helpers.reconcileRequest(
        { ...usageRow(), channel: 'JEV' },
        'CONFIRM_ACTUAL',
        '',
        '',
        '',
        'reason'
      ),
    /至少一项/
  )
})

test('usage filters use exact allowed server fields and exclusive date bound', () => {
  assert.throws(
    () =>
      helpers.pageRequest({ from: '', to: '', purpose: 'toString', status: '', userId: '' }, 1, 20),
    /用途/
  )
  const params = helpers.pageRequest(
    {
      from: '2024-02-29',
      to: '2024-03-01',
      purpose: 'JEV_TRIAGE',
      status: 'SETTLED',
      userId: '37',
      tenantId: 999
    },
    2,
    50
  )
  assert.deepEqual(clone(params), {
    pageNo: 2,
    pageSize: 50,
    from: '2024-02-29',
    to: '2024-03-01',
    purpose: 'JEV_TRIAGE',
    status: 'SETTLED',
    userId: 37
  })
  for (const patch of [
    { from: '2026-02-29', to: '2026-03-01' },
    { from: '2024-01-01', to: '2026-01-01' },
    { purpose: 'RAW_SQL' },
    { userId: '9007199254740993' }
  ])
    assert.throws(() =>
      helpers.pageRequest(
        { from: '', to: '', purpose: '', status: '', userId: '', ...patch },
        1,
        20
      )
    )
})

test('policy dialog ignores stale loads and submits the exact displayed optimistic version', async (t) => {
  const first = deferred(),
    second = deferred(),
    calls = []
  let count = 0
  const { state, events } = setup(t, 'UsagePolicyDialog.vue', {
    usagePolicy: () => (++count === 1 ? first.promise : second.promise),
    saveUsagePolicy: async (data) => calls.push(clone(data))
  })
  const old = state.open()
  const current = state.open()
  second.resolve(policy(7))
  await current
  first.resolve(policy(2))
  await old
  assert.equal(state.form.value.expectedVersion, 7)
  assert.equal(calls.length, 0)
  state.form.value.reason = 'approved budget'
  await state.save()
  assert.equal(calls[0].expectedVersion, 7)
  assert.equal(state.visible.value, false)
  assert.deepEqual(clone(events), [['saved']])
})

test('policy conflict remains visible without pretending successful save or clearing usage', async (t) => {
  const { state, events } = setup(t, 'UsagePolicyDialog.vue', {
    usagePolicy: async () => policy(),
    saveUsagePolicy: async () => {
      throw new Error('version conflict')
    }
  })
  await state.open()
  state.form.value.reason = 'change'
  await state.save()
  assert.equal(state.error.value, 'version conflict')
  assert.equal(state.visible.value, true)
  assert.equal(events.length, 0)
})

test('revoked management permission clears late policy data and blocks save', async (t) => {
  const pending = deferred(),
    calls = []
  const { state, permission } = setup(t, 'UsagePolicyDialog.vue', {
    usagePolicy: () => pending.promise,
    saveUsagePolicy: async (data) => calls.push(data)
  })
  const opening = state.open()
  permission.value = false
  await flush()
  pending.resolve(policy())
  await opening
  assert.equal(state.form.value, undefined)
  assert.equal(state.visible.value, false)
  await state.save()
  assert.equal(calls.length, 0)
})

test('reconcile dialog snapshots ID/version, rejects double submission and preserves unknown action', async (t) => {
  const pending = deferred(),
    calls = []
  const { state } = setup(t, 'UsageReconcileDialog.vue', {
    reconcileUsage: (data) => {
      calls.push(clone(data))
      return pending.promise
    }
  })
  const row = usageRow()
  state.open(row)
  row.version = 99
  state.reason.value = 'timed out; verified transport ended'
  const saving = state.save()
  await state.save()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].expectedVersion, 5)
  assert.equal(calls[0].action, 'CLOSE_AS_UNKNOWN')
  assert.equal(calls[0].confirmedAmount, null)
  pending.resolve({})
  await saving
  assert.equal(state.visible.value, false)
})

test('audit panel discards old-page responses and filter changes invalidate selected rows', async (t) => {
  const first = deferred(),
    second = deferred()
  let count = 0
  const { state } = setup(t, 'ModelUsagePanel.vue', {
    usageSummary: async () => ({ generatedAt: 'now' }),
    usagePage: () => (++count === 1 ? first.promise : second.promise)
  })
  const old = state.refresh()
  state.filters.purpose = 'JEV_TRIAGE'
  const current = state.refresh()
  second.resolve({ list: [{ id: 'current' }], total: 1 })
  await current
  first.resolve({ list: [{ id: 'old' }], total: 99 })
  await old
  assert.equal(state.rows.value[0].id, 'current')
  assert.equal(state.total.value, 1)
  state.detail.value = state.rows.value[0]
  state.filters.status = 'SETTLED'
  assert.equal(state.rows.value.length, 0)
  assert.equal(state.detail.value, undefined)
})

test('audit requests cannot restore data after management permission is revoked', async (t) => {
  const pending = deferred()
  const { state, permission } = setup(t, 'ModelUsagePanel.vue', {
    usageSummary: async () => ({}),
    usagePage: () => pending.promise
  })
  const loading = state.refresh()
  permission.value = false
  await flush()
  pending.resolve({ list: [{ id: 'secret' }], total: 1 })
  await loading
  assert.equal(state.rows.value.length, 0)
  assert.equal(state.summary.value, undefined)
})
