const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const compiled = ts.transpileModule(
  fs.readFileSync(path.join(__dirname, '../src/views/mes/face/faceCamera.ts'), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }
).outputText
function harness() {
  const requests = [],
    states = [],
    draw = []
  const video = { srcObject: null, videoWidth: 1280, videoHeight: 720, play: async () => {} }
  let exported = { exports: {} }
  const context = {
    module: exported,
    exports: exported.exports,
    Error,
    navigator: {
      mediaDevices: {
        getUserMedia: () => new Promise((resolve, reject) => requests.push({ resolve, reject }))
      }
    },
    document: {
      createElement: () => ({
        width: 0,
        height: 0,
        getContext: () => ({ drawImage: (...v) => draw.push(v) }),
        toDataURL: (type, quality) => type + ':' + quality
      })
    }
  }
  vm.runInNewContext(compiled, context)
  const camera = exported.exports.createFaceCamera(
    () => video,
    (value) => states.push(value)
  )
  const stream = () => ({
    stops: 0,
    getTracks() {
      return [{ stop: () => this.stops++ }]
    }
  })
  return { camera, requests, states, video, draw, stream }
}
test('camera is requested only on explicit start and capture stays in memory at bounded resolution', async () => {
  const h = harness()
  assert.equal(h.requests.length, 0)
  assert.throws(() => h.camera.capture(), /尚未就绪/)
  const pending = h.camera.start(),
    stream = h.stream()
  assert.equal(h.requests.length, 1)
  h.requests[0].resolve(stream)
  await pending
  assert.equal(h.states.at(-1).ready, true)
  assert.equal(h.camera.capture(), 'image/jpeg:0.88')
  assert.equal(h.draw[0][3], 640)
  assert.equal(h.draw[0][4], 360)
  h.camera.stop()
  assert.equal(stream.stops, 1)
  assert.equal(h.video.srcObject, null)
})
test('closing or unmounting while permission is pending stops the late stream', async () => {
  for (const action of ['stop', 'dispose']) {
    const h = harness(),
      stream = h.stream(),
      pending = h.camera.start()
    h.camera[action]()
    h.requests[0].resolve(stream)
    await pending
    assert.equal(stream.stops, 1)
    assert.equal(h.video.srcObject, null)
    assert.equal(
      h.states.some((s) => s.ready),
      false
    )
  }
})
test('superseded camera requests cannot replace or stop the current stream', async () => {
  const h = harness(),
    first = h.camera.start(),
    second = h.camera.start(),
    old = h.stream(),
    current = h.stream()
  h.requests[1].resolve(current)
  await second
  h.requests[0].resolve(old)
  await first
  assert.equal(old.stops, 1)
  assert.equal(current.stops, 0)
  assert.equal(h.video.srcObject, current)
  h.camera.dispose()
  assert.equal(current.stops, 1)
})
test('camera playback and permission failures clear streams and expose a usable error state', async () => {
  const h = harness(),
    stream = h.stream()
  h.video.play = async () => {
    throw new Error('blocked')
  }
  const p = h.camera.start()
  h.requests[0].resolve(stream)
  await p
  assert.equal(stream.stops, 1)
  assert.equal(h.video.srcObject, null)
  assert.equal(h.states.at(-1).ready, false)
  const retry = h.camera.start()
  h.requests[1].reject(new Error('denied'))
  await retry
  assert.match(h.states.at(-1).message, /无法打开/)
})
