const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function harness(register) {
  const calls = []
  const filename = path.join(__dirname, '../src/components/UploadFile/src/useUpload.ts')
  const source = fs.readFileSync(filename, 'utf8').replaceAll('import.meta.env.', 'env.')
  const info = {
    configId: 29,
    path: 'tenant/1/uploads/new.jpg',
    uploadUrl: 'https://storage.test/put',
    url: 'https://storage.test/new.jpg',
    uploadTicket: 'server-issued-one-use-ticket'
  }
  const dependencies = {
    '@/api/infra/file': {
      getFilePresignedUrl: async () => info,
      createFile: async (value) => {
        calls.push(value)
        return register(value)
      }
    },
    axios: { put: async () => ({ status: 200 }) }
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
      env: { VITE_BASE_URL: '', VITE_API_URL: '/admin-api', VITE_UPLOAD_TYPE: 'client' },
      require: (name) => dependencies[name] || require(name)
    },
    { filename }
  )
  const upload = () =>
    module.exports.useUpload('images').httpRequest({
      file: { name: 'new.jpg', type: 'image/jpeg', size: 3 },
      filename: 'new.jpg'
    })
  return { upload, calls, info }
}

test('S3 success waits for server registration and forwards its exact one-use upload ticket', async () => {
  let finish
  const registered = new Promise((resolve) => {
    finish = resolve
  })
  const h = harness(() => registered)
  let completed = false
  const uploading = h.upload().then((result) => {
    completed = true
    return result
  })
  await new Promise(setImmediate)
  assert.equal(h.calls.length, 1)
  assert.equal(h.calls[0].uploadTicket, h.info.uploadTicket)
  assert.equal(h.calls[0].path, h.info.path)
  assert.equal(completed, false)
  finish(100)
  assert.equal((await uploading).data, h.info.url)
})

test('an expired/replayed upload ticket never publishes a success URL after storage PUT', async () => {
  const h = harness(async () => {
    throw new Error('上传凭据已过期')
  })
  await assert.rejects(h.upload(), /上传凭据已过期/)
})
