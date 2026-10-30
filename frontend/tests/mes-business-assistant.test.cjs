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
      require: (name) => dependencies[name] || require(name),
      ...globals
    },
    { filename }
  )
  return module.exports
}

test('business ask sends conversation version and clarification as top-level request fields', async () => {
  const filename = path.join(__dirname, '../src/api/mes/insight/index.ts')
  const calls = []
  const { InsightApi } = evaluate(fs.readFileSync(filename, 'utf8'), filename, {
    '@/config/axios': { __esModule: true, default: { post: (request) => calls.push(request) } }
  })
  await InsightApi.ask({
    question: '按仓库',
    useModel: false,
    conversationId: 9,
    version: 4,
    clarification: { token: 'owner-version-token', choiceId: 'opaque-choice' }
  })
  assert.equal(calls[0].data.question, '按仓库')
  assert.equal(calls[0].data.conversationId, 9)
  assert.equal(calls[0].data.version, 4)
  assert.equal(calls[0].data.clarification.choiceId, 'opaque-choice')
})

const businessFile = path.join(__dirname, '../src/api/mes/insight/business.ts')
const business = evaluate(fs.readFileSync(businessFile, 'utf8'), businessFile)
const evidenceFile = path.join(__dirname, '../src/views/mes/insight/businessEvidence.ts')
const evidenceHelpers = evaluate(fs.readFileSync(evidenceFile, 'utf8'), evidenceFile)
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
const summary = (id = 9, version = 4) => ({
  id,
  version,
  title: `会话${id}`,
  turnCount: version,
  createdAt: '2026-10-20T10:00:00+08:00',
  updatedAt: '2026-10-20T10:00:00+08:00'
})
const answer = (id = 9, version = 5) => ({
  conversationId: id,
  version,
  turnId: 100 + version,
  status: 'ANSWERED',
  mode: 'RULES',
  topic: 'scope',
  answer: '规则答复',
  evidence: {},
  suggestions: [],
  clarification: null,
  generatedAt: '2026-10-20T10:00:00+08:00'
})
const turn = (id = 9, version = 4) => ({
  id: 100 + version,
  version,
  question: '查2026年10月库存',
  createdAt: '2026-10-20T10:00:00+08:00',
  answer: answer(id, version)
})
const history = (id = 9, version = 4) => ({
  conversation: summary(id, version),
  turns: [turn(id, version)],
  hasMore: false,
  nextBeforeTurnId: null
})
function setup(t, filename, props, api = {}, allowed) {
  filename = path.join(__dirname, '../src/views/mes/insight', filename)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'business-assistant' }).content
  const events = []
  const defaults = {
    conversationPage: async () => ({ list: [], total: 0 }),
    conversation: async (id) => history(id),
    createConversation: async () => summary(20, 0),
    deleteConversation: async () => true
  }
  const component = evaluate(
    source,
    filename,
    {
      vue,
      '@/api/mes/insight': { InsightApi: { ...defaults, ...api } },
      '@/api/mes/insight/business': business,
      '@/utils/permission': {
        checkPermi: (permissions) =>
          allowed ? permissions.every((value) => allowed.value.has(value)) : true
      },
      './businessEvidence': evidenceHelpers,
      './InventoryEvidence.vue': {},
      './LegacyBusinessEvidence.vue': {},
      './AgentResult.vue': {},
      '@/components/MarkdownView/index.vue': {}
    },
    { ...vue, useRouter: () => ({ push: () => {} }), onBeforeUnmount: () => {} }
  ).default
  const scope = vue.effectScope()
  const reactiveProps = vue.reactive(props)
  const state = scope.run(() =>
    component.setup(reactiveProps, {
      expose: () => {},
      emit: (name, value) => events.push([name, value])
    })
  )
  t.after(() => scope.stop())
  return { state, events, props: reactiveProps }
}

test('business conversation resumes persisted history and sends the exact current version once', async (t) => {
  const calls = [],
    pending = deferred()
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    {
      ask: (data) => {
        calls.push(data)
        return pending.promise
      }
    }
  )
  await state.openConversation(9)
  state.question.value = '那9月份呢？'
  const first = state.send()
  await state.send()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].conversationId, 9)
  assert.equal(calls[0].version, 4)
  assert.equal(calls[0].question, '那9月份呢？')
  pending.resolve(answer())
  await first
  assert.equal(state.conversation.value.version, 5)
  assert.equal(state.turns.value.length, 2)
  assert.equal(state.turns.value[1].answer.version, 5)
})

test('late ask response cannot overwrite a different conversation or its busy flag', async (t) => {
  const first = deferred(),
    second = deferred(),
    calls = []
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    {
      ask: (data) => {
        calls.push(data)
        return calls.length === 1 ? first.promise : second.promise
      }
    }
  )
  await state.openConversation(9)
  state.question.value = '按仓库'
  const oldRequest = state.send()
  await state.openConversation(10)
  state.question.value = '按批次'
  const newRequest = state.send()
  first.resolve(answer(9, 5))
  await oldRequest
  assert.equal(state.conversation.value.id, 10)
  assert.equal(state.sending.value, true)
  second.resolve(answer(10, 5))
  await newRequest
  assert.equal(state.turns.value.at(-1).answer.conversationId, 10)
  assert.equal(state.sending.value, false)
})

test('late history cannot replace the latest selected conversation', async (t) => {
  const old = deferred()
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    { conversation: (id) => (id === 9 ? old.promise : Promise.resolve(history(id))) }
  )
  const loading = state.openConversation(9)
  await state.openConversation(10)
  old.resolve(history(9))
  await loading
  assert.equal(state.conversation.value.id, 10)
  assert.equal(state.turns.value[0].answer.conversationId, 10)
})

test('version or permission failure clears visible facts, titles and pending actions before retry', async (t) => {
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    {
      ask: async () => {
        throw new Error('conflict')
      },
      conversationPage: async () => ({ list: [summary()], total: 1 })
    }
  )
  await state.loadList()
  await state.openConversation(9)
  state.question.value = '按批次'
  await state.send()
  assert.equal(state.turns.value.length, 0)
  assert.equal(state.conversations.value.length, 0)
  assert.equal(state.conversation.value, undefined)
  assert.equal(state.recoverId.value, 9)
  state.question.value = '新问题'
  assert.equal(state.canSend.value, false)
  await state.openConversation(9)
  assert.equal(state.recoverId.value, null)
})

test('only latest unexpired clarification can send server-issued token and opaque choice', async (t) => {
  const calls = []
  const data = history()
  data.turns[0].answer.status = 'CLARIFY'
  data.turns[0].answer.clarification = {
    token: 'opaque-token',
    reason: 'YEAR_REQUIRED',
    prompt: '哪一年？',
    expiresAt: '2099-01-01T00:00:00+08:00',
    choices: [{ id: 'choice-x', label: '2026年' }],
    acceptsFreeText: true
  }
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    {
      conversation: async () => data,
      ask: async (value) => {
        calls.push(value)
        return answer()
      }
    }
  )
  await state.openConversation(9)
  const prior = state.turns.value[0]
  await state.choose(prior, 'forged-id')
  assert.equal(calls.length, 0)
  await state.choose(prior, 'choice-x')
  assert.equal(calls[0].clarification.token, 'opaque-token')
  assert.equal(calls[0].clarification.choiceId, 'choice-x')
  assert.equal(calls[0].version, 4)
  assert.equal(calls[0].question, '')
  await state.choose(prior, 'choice-x')
  assert.equal(calls.length, 1)
  const expired = {
    ...state.turns.value.at(-1),
    answer: {
      ...answer(),
      status: 'CLARIFY',
      clarification: {
        ...data.turns[0].answer.clarification,
        expiresAt: '2000-01-01T00:00:00+08:00'
      }
    }
  }
  state.turns.value = [expired]
  assert.equal(state.isActiveClarification(expired), false)
})

test('revoking a relevant permission clears old history and blocks reused context', async (t) => {
  const allowed = vue.ref(
    new Set([
      'mes:insight:query',
      'mes:insight:chat',
      'mes:wm-material-stock:query',
      'mes:cost:query'
    ])
  )
  const { state } = setup(t, 'BusinessAssistant.vue', { active: false }, {}, allowed)
  await state.openConversation(9)
  allowed.value = new Set(['mes:insight:query', 'mes:insight:chat', 'mes:cost:query'])
  await flush()
  assert.equal(state.turns.value.length, 0)
  assert.equal(state.recoverId.value, 9)
})

test('older turns prepend without duplicate IDs and never roll the conversation version back', async (t) => {
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    {
      conversation: async (id, before) =>
        before
          ? {
              conversation: summary(id, 4),
              turns: [turn(id, 2), turn(id, 3)],
              hasMore: false,
              nextBeforeTurnId: null
            }
          : {
              ...history(id),
              turns: [turn(id, 3), turn(id, 4)],
              hasMore: true,
              nextBeforeTurnId: 103
            }
    }
  )
  await state.openConversation(9)
  await state.loadOlder()
  assert.deepEqual(Array.from(state.turns.value.map((value) => value.id)), [102, 103, 104])
  assert.equal(state.conversation.value.version, 4)
  assert.equal(state.hasMore.value, false)
})

test('delete uses current version and a late completion cannot clear another conversation', async (t) => {
  const pending = deferred(),
    calls = []
  const { state } = setup(
    t,
    'BusinessAssistant.vue',
    { active: false },
    {
      deleteConversation: (id, version) => {
        calls.push([id, version])
        return pending.promise
      }
    }
  )
  await state.openConversation(9)
  const deleting = state.deleteCurrent()
  await state.openConversation(10)
  pending.resolve(true)
  await deleting
  assert.deepEqual(calls, [[9, 4]])
  assert.equal(state.conversation.value.id, 10)
})

const inventory = () => ({
  kind: 'INVENTORY_LEDGER',
  asOf: '2026-10-20T10:00:00+08:00',
  total: 21,
  pageNo: 1,
  pageSize: 20,
  hasMore: true,
  basis: '全部实物库存',
  complete: true,
  warnings: [],
  period: { year: 2026, month: 9 },
  filters: { groupBy: 'BATCH' },
  list: [{ rowKey: 'item1', openingQuantity: '99999999999999.123456' }],
  unitGroups: [
    {
      unitKey: 'UNIT:1',
      openingQuantity: '99999999999999.123456',
      inboundQuantity: '0.000001',
      outboundQuantity: '0',
      closingQuantity: '99999999999999.123457',
      rowCount: 21
    }
  ]
})

test('snapshot pagination uses turn identity and preserves full-scope exact quantities', async (t) => {
  const original = inventory(),
    calls = []
  const { state } = setup(
    t,
    'InventoryEvidence.vue',
    { turnId: 104, evidence: original },
    {
      conversationEvidence: async (...args) => {
        calls.push(args)
        return {
          ...original,
          pageNo: 2,
          hasMore: false,
          list: [{ rowKey: 'item21', openingQuantity: '0.000001' }]
        }
      }
    }
  )
  await state.pageEvidence(2)
  assert.deepEqual(calls, [[104, 2, 20]])
  assert.equal(state.current.value.asOf, original.asOf)
  assert.equal(state.current.value.unitGroups[0].closingQuantity, '99999999999999.123457')
  assert.equal(state.current.value.unitGroups[0].rowCount, 21)
  assert.equal(state.current.value.list[0].rowKey, 'item21')
})

test('a stale evidence page cannot overwrite a newer turn and mismatch snapshot clears facts', async (t) => {
  const pending = deferred()
  const { state, props, events } = setup(
    t,
    'InventoryEvidence.vue',
    { turnId: 104, evidence: inventory() },
    { conversationEvidence: async () => pending.promise }
  )
  const paging = state.pageEvidence(2)
  props.turnId = 105
  props.evidence = { ...inventory(), asOf: '2026-10-20T11:00:00+08:00' }
  await flush()
  pending.resolve({ ...inventory(), pageNo: 2 })
  await paging
  assert.equal(state.current.value.asOf, '2026-10-20T11:00:00+08:00')
  assert.equal(events.length, 0)
  // A separate malformed response with the same turn must never be displayed.
  const second = setup(
    t,
    'InventoryEvidence.vue',
    { turnId: 106, evidence: inventory() },
    { conversationEvidence: async () => ({ ...inventory(), pageNo: 2, asOf: 'changed' }) }
  )
  await second.state.pageEvidence(2)
  assert.equal(second.state.current.value, undefined)
  assert.equal(second.events[0][0], 'failure')
})

test('quantity display preserves micro-units and unknowns, and source links require both whitelist and permission', () => {
  assert.equal(evidenceHelpers.exactQuantity('99999999999999.123456'), '99999999999999.123456')
  assert.equal(evidenceHelpers.exactQuantity(null), '待核对')
  assert.equal(evidenceHelpers.exactQuantity('0.000000'), '0.000000')
  const source = { bizType: 118, bizId: 9, bizLineId: 10, canOpen: true }
  assert.equal(
    evidenceHelpers.canOpenSource(source, () => true),
    true
  )
  assert.equal(
    evidenceHelpers.canOpenSource(source, () => false),
    false
  )
  assert.equal(
    evidenceHelpers.canOpenSource({ ...source, canOpen: false }, () => true),
    false
  )
  for (const invalid of [
    { ...source, bizType: 999 },
    { ...source, bizId: -1 },
    { ...source, bizId: '9' },
    { ...source, bizId: null }
  ])
    assert.equal(
      evidenceHelpers.canOpenSource(invalid, () => true),
      false
    )
})
