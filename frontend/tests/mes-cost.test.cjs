const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function load(relativePath, dependencies = {}) {
  const filename = path.join(__dirname, '../src', relativePath)
  const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(
    compiled,
    {
      module,
      exports: module.exports,
      Blob,
      require: (name) => dependencies[name] || require(name)
    },
    { filename }
  )
  return module.exports
}
const downloads = []
const format = load('views/mes/cost/format.ts', {
  '@/utils/download': { json: (blob, name) => downloads.push({ blob, name }) }
})

test('missing cost stays unavailable while an explicit zero remains a valid amount', () => {
  for (const value of [null, undefined, '', 'NaN', 'Infinity']) {
    assert.equal(format.money(value), '待核定')
  }
  for (const value of [0, '0', '0.000000']) assert.equal(format.money(value), '¥0')
  assert.equal(format.margin(null), '待核定')
  assert.equal(format.margin('0'), '0%')
})

test('server decimal text displays without losing six-place purchase precision', () => {
  assert.equal(format.money('999999999999.999999'), '¥999,999,999,999.999999')
  assert.equal(format.money('0.000001'), '¥0.000001')
  assert.equal(format.money('-17200.120000'), '¥-17,200.12')
  assert.equal(format.optionalAmount('999999999999.999999'), '999999999999.999999')
  assert.equal(format.money('1E-8'), '¥0.00000001')
  assert.equal(format.money('12.34E+3'), '¥12,340')
})

test('decimal entry enforces the same size and sign constraints as the cost API', () => {
  function error(value, required = false, integer = 12, scale = 6) {
    let result
    format.decimalRule(integer, scale, required).validator(null, value, (problem) => {
      result = problem
    })
    return result
  }
  assert.equal(error('999999999999.999999'), undefined)
  assert.equal(error('0', true), undefined)
  assert.equal(error('', false), undefined)
  for (const value of ['-1', '1e6', 'Infinity', '1.1234567', '1000000000000', ' 12 ', '', null]) {
    assert.ok(error(value, true), `must reject ${value}`)
  }
  assert.ok(error('1.12345', true, 8, 4))
  assert.equal(error('0.0001', true, 8, 4), undefined)
})

test('JSON export preserves exact server amounts and labels currency and tax basis', async () => {
  const input = { totalCost: '999999999999.999999', purchasePrice: null, status: 'INCOMPLETE' }
  format.exportCostSnapshot('Broky/成本', input)
  const exported = downloads.at(-1)
  const body = JSON.parse(await exported.blob.text())
  assert.equal(exported.name, 'Broky_成本.json')
  assert.equal(body.currency, 'CNY')
  assert.equal(body.taxBasis, 'EXCLUSIVE')
  assert.deepEqual(body.data, input)
})

test('cost API retains the idempotency key and exact decimal payload on transport retry', async () => {
  const calls = []
  const api = load('api/mes/cost/index.ts', {
    '@/config/axios': {
      post: async (options) => {
        calls.push(options)
        return true
      }
    }
  }).CostApi
  const data = {
    requestId: 'same-uuid',
    workorderId: 123,
    processId: 456,
    hours: '2.1234',
    hourlyRate: '999999999999.999999',
    overheadAmount: '0',
    remark: '核算依据'
  }
  await api.addEffort(data)
  await api.addEffort(data)
  assert.equal(calls.length, 2)
  assert.equal(calls[0].url, '/mes/cost/workorder/effort')
  assert.equal(calls[0].data.requestId, calls[1].data.requestId)
  assert.equal(calls[0].data.hourlyRate, '999999999999.999999')
})
