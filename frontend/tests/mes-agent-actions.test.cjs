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
  answer = { proposal: { kind: 'CREATE_INCIDENT', title: '缺料' }, evidence: {}, turnId: 42 }
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
  const deps = {
    vue,
    'vue-router': { useRouter: () => ({ push: () => {} }) },
    '@/api/mes/insight/agent': { AgentApi: api },
    '@/api/mes/insight': {
      InsightApi: { incidents: async () => ({ owners: [{ id: 10, name: '操作者' }] }) }
    },
    '@/utils/permission': { checkPermi: () => true },
    '@/utils/download': { __esModule: true, default: { excel: (...args) => downloads.push(args) } }
  }
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
    module.exports.default.setup(vue.reactive({ answer }), { expose() {}, emit() {} })
  )
  t.after(() => scope.stop())
  return { state, downloads }
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
