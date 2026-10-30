const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function harness(fetchImpl) {
  const requests = []
  const timers = new Map()
  let nextTimer = 0
  const filename = path.join(__dirname, '../src/utils/fileMediaSession.ts')
  const source = fs.readFileSync(filename, 'utf8').replaceAll('import.meta.env.', 'env.')
  const module = { exports: {} }
  const ok = () => ({
    ok: true,
    json: async () => ({ code: 0, data: new Date(Date.now() + 600_000).toISOString() })
  })
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      AbortSignal,
      Date,
      env: { VITE_BASE_URL: '', VITE_API_URL: '/admin-api' },
      setTimeout: (handler, delay) => {
        const id = ++nextTimer
        timers.set(id, { handler, delay })
        return id
      },
      clearTimeout: (id) => timers.delete(id),
      fetch: async (url, options) => {
        requests.push({ url, options })
        return fetchImpl ? fetchImpl(url, options, ok) : ok()
      }
    },
    { filename }
  )
  return { ...module.exports, requests, timers }
}

test('media authentication uses a credentialed header request and keeps login secrets out of URLs', async () => {
  const h = harness()
  await h.ensureFileMediaSession('private-login-token', 1)
  await h.ensureFileMediaSession('private-login-token', 1)
  assert.equal(h.requests.length, 1)
  const { url, options } = h.requests[0]
  assert.equal(url, '/admin-api/infra/file/media-session')
  assert.equal(options.credentials, 'include')
  assert.equal(options.headers.Authorization, 'Bearer private-login-token')
  assert.equal(options.headers['tenant-id'], '1')
  assert.equal(h.timers.size, 1)
})

test('login token refresh reissues the media session before returning to cached file URLs', async () => {
  const h = harness()
  await h.ensureFileMediaSession('old-login', 1)
  await h.ensureFileMediaSession('refreshed-login', 1)
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[1].options.headers.Authorization, 'Bearer refreshed-login')
  assert.equal(h.timers.size, 1)
})

test('logout waits for a pending issuance and then clears its cookie instead of reviving it', async () => {
  let resolveIssue
  const h = harness((_url, options, ok) =>
    options.method === 'POST'
      ? new Promise((resolve) => {
          resolveIssue = () => resolve(ok())
        })
      : ok()
  )
  const pending = h.ensureFileMediaSession('old-login', 1)
  await new Promise(setImmediate)
  h.clearFileMediaSession()
  resolveIssue()
  await pending
  await new Promise(setImmediate)
  assert.deepEqual(
    h.requests.map((r) => r.options.method),
    ['POST', 'DELETE']
  )
  assert.equal(h.timers.size, 0)
})

test('an account switch serializes cookie writes and replaces the old tenant context', async () => {
  let release
  let number = 0
  const h = harness((_url, _options, ok) =>
    ++number === 1
      ? new Promise((resolve) => {
          release = () => resolve(ok())
        })
      : ok()
  )
  const first = h.ensureFileMediaSession('first-login', 1)
  await new Promise(setImmediate)
  const second = h.ensureFileMediaSession('second-login', 2)
  await new Promise(setImmediate)
  assert.equal(h.requests.length, 1)
  release()
  await Promise.all([first, second])
  assert.equal(h.requests[1].options.headers['tenant-id'], '2')
  assert.equal(h.requests[1].options.headers.Authorization, 'Bearer second-login')
  assert.equal(h.timers.size, 1)
})

test('failed media authentication stays private and schedules a bounded retry', async () => {
  const h = harness(() => ({ ok: true, json: async () => ({ code: 401 }) }))
  await assert.rejects(h.ensureFileMediaSession('login', 1), /附件会话需要续期/)
  assert.equal(h.requests.length, 1)
  assert.equal([...h.timers.values()][0].delay, 30_000)
  assert.ok(h.requests.every((r) => !r.url.includes('login')))
})

test('idle media renewal refreshes an expired access token and issues the cookie with the new token', async () => {
  let expired = false
  let refreshCount = 0
  const h = harness((_url, options, ok) =>
    expired && options.headers.Authorization === 'Bearer old'
      ? { ok: true, json: async () => ({ code: 401 }) }
      : ok()
  )
  h.setFileMediaSessionTokenRefresh(async () => {
    refreshCount++
    return { accessToken: 'renewed', tenantId: 1 }
  })
  await h.ensureFileMediaSession('old', 1)
  expired = true
  // Advance the cookie's local validity clock to its renewal window.
  const originalNow = Date.now
  Date.now = () => originalNow() + 600_000
  try {
    const timer = [...h.timers.values()][0]
    timer.handler()
    await new Promise(setImmediate)
    await new Promise(setImmediate)
    assert.equal(refreshCount, 1)
    assert.equal(h.requests.at(-1).options.headers.Authorization, 'Bearer renewed')
  } finally {
    Date.now = originalNow
  }
})
