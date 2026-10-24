const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function setup(data) {
  const filename = path.join(__dirname, '../src/config/axios/service.ts')
  const source = fs
    .readFileSync(filename, 'utf8')
    .replace('import.meta.env.VITE_APP_TENANT_ENABLE', "'true'")
  const notifications = []
  const dependencies = {
    'element-plus': {
      ElMessage: { error: (message) => notifications.push(message) },
      ElMessageBox: {},
      ElNotification: { error: ({ title }) => notifications.push(title) }
    },
    '@/config/axios/config': {
      config: { result_code: 0, base_url: 'http://unit.test', request_timeout: 1000 }
    },
    '@/utils/auth': {
      getAccessToken: () => undefined,
      getTenantId: () => 1,
      getVisitTenantId: () => undefined
    },
    './errorCode': { __esModule: true, default: { 403: '没有权限访问', default: '未知业务错误' } },
    '@/router': {},
    '@/hooks/web/useCache': {},
    '@/utils/encrypt': { ApiEncrypt: { getEncryptHeader: () => 'X-Test-Encrypted' } }
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
        data,
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
