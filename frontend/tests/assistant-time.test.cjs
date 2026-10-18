const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const ts = require('typescript')
const compiled = ts.transpileModule(
  fs.readFileSync(path.join(__dirname, '../src/views/mes/assistant/assistantTime.ts'), 'utf8'),
  {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
      esModuleInterop: true
    }
  }
).outputText
const loaded = { exports: {} }
new Function('require', 'module', 'exports', compiled)(require, loaded, loaded.exports)
const { formatAssistantTime } = loaded.exports

test('session and message timestamps render without throwing', () => {
  const timestamp = new Date(2026, 9, 18, 15, 20, 45).getTime()
  assert.equal(formatAssistantTime(timestamp), '2026-10-18 15:20')
  assert.notEqual(formatAssistantTime(0), '')
})

test('legacy date strings keep the same minute precision', () => {
  for (const value of ['2026-10-18T15:20:45', '2026-10-18 15:20:45']) {
    assert.equal(formatAssistantTime(value), '2026-10-18 15:20')
  }
})

test('missing or malformed dates cannot break the entire assistant render', () => {
  for (const value of [null, undefined, '', '  ', 'bad-date', NaN, Infinity, {}, [], true]) {
    assert.equal(formatAssistantTime(value), '')
  }
})
