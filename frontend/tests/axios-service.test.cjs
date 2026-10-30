const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function setup(data, options = {}) {
  const filename = path.join(__dirname, '../src/config/axios/service.ts')
  const source = fs
    .readFileSync(filename, 'utf8')
    .replace('import.meta.env.VITE_APP_TENANT_ENABLE', "'true'")
  const notifications = []
  let accessToken = options.accessToken
  const dependencies = {
    axios: options.axios || require('axios'),
    'element-plus': {
      ElMessage: { error: (message) => notifications.push(message) },
      ElMessageBox: {},
      ElNotification: { error: ({ title }) => notifications.push(title) }
    },
    '@/config/axios/config': {
      config: { result_code: 0, base_url: 'http://unit.test', request_timeout: 1000 }
    },
    '@/utils/auth': {
      getAccessToken: () => accessToken,
      getRefreshToken: () => options.refreshToken,
      setToken: (value) => {
        accessToken = value.accessToken
        options.onSetToken?.(value)
      },
      getTenantId: () => 1,
      getVisitTenantId: () => undefined
    },
    './errorCode': { __esModule: true, default: { 403: '没有权限访问', default: '未知业务错误' } },
    '@/router': {},
    '@/hooks/web/useCache': {},
    '@/utils/encrypt': { ApiEncrypt: { getEncryptHeader: () => 'X-Test-Encrypted' } },
    '@/utils/fileMediaSession': {
      ensureFileMediaSession: options.ensureMedia || (async () => {}),
      setFileMediaSessionTokenRefresh: (handler) => options.onRegisterMediaRefresh?.(handler)
    }
  }
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
        esModuleInterop: true
      }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || require(name),
      useI18n: () => ({ t: (key) => key })
    },
    { filename }
  )
  const request = () =>
    module.exports.service.get('/mes/insight/incident', {
      adapter: async (config) => ({
        data: options.responseData ? options.responseData(config) : data,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
        request: { responseType: '' }
      })
    })
  return { request, notifications }
}

test('business validation preserves the backend reason for page error handling', async () => {
  const { request, notifications } = setup({ code: 400, msg: '请先指定负责人' })
  await assert.rejects(request, { name: 'Error', message: '请先指定负责人' })
  assert.deepEqual(notifications, ['请先指定负责人'])
})

test('version conflict and permission failures keep their actionable messages', async () => {
  for (const data of [{ code: 409, msg: '记录已被其他用户更新，请刷新后重试' }, { code: 403 }]) {
    const { request, notifications } = setup(data)
    const message = data.msg || '没有权限访问'
    await assert.rejects(request, { name: 'Error', message })
    assert.deepEqual(notifications, [message])
  }
})

test('successful business responses are returned intact', async () => {
  const data = { code: 0, data: { id: 5, status: 'IN_PROGRESS' } }
  const { request, notifications } = setup(data)
  assert.deepEqual(await request(), data)
  assert.deepEqual(notifications, [])
})

test('token refresh waits for media issuance before replaying queued or newly-started business requests', async () => {
  const axios = require('axios')
  let finishRefresh
  let finishMedia
  let signalMedia
  const mediaStarted = new Promise((resolve) => {
    signalMedia = resolve
  })
  const mediaReady = new Promise((resolve) => {
    finishMedia = resolve
  })
  const refreshReady = new Promise((resolve) => {
    finishRefresh = resolve
  })
  const events = []
  const h = setup(undefined, {
    accessToken: 'old-login',
    refreshToken: 'refresh-login',
    axios: {
      create: axios.create.bind(axios),
      defaults: { headers: { common: {} } },
      post: () => refreshReady
    },
    ensureMedia: async (token) => {
      if (token === 'new-login') {
        events.push('media-start')
        signalMedia()
        await mediaReady
        events.push('media-ready')
      }
    },
    responseData: (config) => {
      const token = config.headers.Authorization
      events.push(`business:${token}`)
      return token === 'Bearer old-login' ? { code: 401 } : { code: 0, data: { saved: true } }
    }
  })
  const first = h.request()
  await new Promise(setImmediate)
  const queued = h.request()
  await new Promise(setImmediate)
  finishRefresh({ data: { data: { accessToken: 'new-login', refreshToken: 'new-refresh' } } })
  await mediaStarted
  const newlyStarted = h.request()
  await new Promise(setImmediate)
  assert.ok(!events.includes('business:Bearer new-login'))
  finishMedia()
  await Promise.all([first, queued, newlyStarted])
  assert.equal(events.filter((event) => event === 'business:Bearer new-login').length, 3)
  assert.ok(events.indexOf('media-ready') < events.indexOf('business:Bearer new-login'))
})

test('idle media and concurrent business 401s share one validated refresh', async () => {
  const axios = require('axios')
  let renew, complete
  let calls = 0
  const ready = new Promise((resolve) => { complete = resolve })
  const h = setup(undefined, {
    accessToken: 'old-login', refreshToken: 'refresh-login',
    onRegisterMediaRefresh: (handler) => { renew = handler },
    axios: { create: axios.create.bind(axios), post: () => { calls++; return ready } },
    responseData: (config) => config.headers.Authorization === 'Bearer old-login'
      ? { code: 401 } : { code: 0, data: true }
  })
  const media = renew()
  const business = h.request()
  await new Promise(setImmediate)
  complete({ data: { code: 0, data: { accessToken: 'new-login', refreshToken: 'new-refresh' } } })
  await Promise.all([media, business])
  assert.equal(calls, 1)
})

test('a business-error refresh response never sets invalid credentials and allows a later retry', async () => {
  const axios = require('axios')
  let renew, calls = 0, saved = 0
  setup(undefined, {
    accessToken: 'old', refreshToken: 'refresh', onSetToken: () => saved++,
    onRegisterMediaRefresh: (handler) => { renew = handler },
    axios: { create: axios.create.bind(axios), post: async () => {
      calls++
      return { data: { code: 401, msg: '刷新令牌已过期' } }
    } }
  })
  await assert.rejects(renew(), /登录续期失败/)
  await assert.rejects(renew(), /登录续期失败/)
  assert.equal(saved, 0)
  assert.equal(calls, 2)
})
