const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const THREE = require('three')

const compiled = ts.transpileModule(
  fs.readFileSync(
    path.join(__dirname, '../src/views/mes/dashboard/effects/useAeroAmbientEffects.ts'),
    'utf8'
  ),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }
).outputText
const settle = () => new Promise((resolve) => setImmediate(resolve))

// Use real Three geometries/materials/textures. Only canvas contexts and the GPU
// renderer are replaced, so interaction/lifetime tests do not require a browser.
function harness({ reduced = false, webgl = true, sprites = true, autoCreate = true } = {}) {
  class Events {
    listeners = new Map()

    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set())
      this.listeners.get(type).add(listener)
    }

    removeEventListener(type, listener) {
      this.listeners.get(type)?.delete(listener)
    }

    emit(type) {
      const event = { preventDefault() {} }
      for (const listener of this.listeners.get(type) || []) listener(event)
    }

    get listenerCount() {
      return [...this.listeners.values()].reduce((count, listeners) => count + listeners.size, 0)
    }
  }
  const contexts = []
  class Element extends Events {
    style = {}
    attributes = new Map()
    children = []
    parent = null
    connected = false
    clientWidth = 960
    clientHeight = 540
    scrollHeight = 3000

    get isConnected() {
      return this.parent ? this.parent.isConnected : this.connected
    }

    setAttribute(name, value) {
      this.attributes.set(name, value)
    }

    getAttribute(name) {
      return this.attributes.get(name) ?? null
    }

    removeAttribute(name) {
      this.attributes.delete(name)
    }

    appendChild(child) {
      child.remove()
      child.parent = this
      this.children.push(child)
    }

    remove() {
      if (this.parent) this.parent.children = this.parent.children.filter((node) => node !== this)
      this.parent = null
    }

    getContext(type) {
      if (type === 'webgl2') return webgl ? { getExtension: () => ({ loseContext() {} }) } : null
      if (!sprites) return null
      const context = {
        stops: [],
        createRadialGradient: () => ({
          addColorStop: (at, color) => context.stops.push([at, color])
        }),
        fillRect() {}
      }
      contexts.push(context)
      return context
    }
  }
  const document = new Events()
  document.hidden = false
  document.createElement = () => new Element()
  const media = new Events()
  media.matches = reduced
  const window = new Events()
  window.visualViewport = new Events()
  window.devicePixelRatio = 3
  window.matchMedia = () => media
  const frames = new Map()
  let frameId = 0
  let currentTime = 0
  window.requestAnimationFrame = (callback) => {
    const id = ++frameId
    frames.set(id, callback)
    return id
  }
  window.cancelAnimationFrame = (id) => frames.delete(id)
  const resizes = []
  const intersections = []
  class Observer {
    observed = new Set()

    constructor(callback) {
      this.callback = callback
    }

    observe(element) {
      this.observed.add(element)
    }

    disconnect() {
      this.observed.clear()
    }
  }
  class ResizeObserver extends Observer {
    constructor(callback) {
      super(callback)
      resizes.push(this)
    }
  }
  class IntersectionObserver extends Observer {
    constructor(callback) {
      super(callback)
      intersections.push(this)
    }
  }
  const renderers = []
  class Renderer {
    draws = []
    sizes = []
    disposed = false
    contextReleased = false

    constructor(options) {
      this.options = options
      this.domElement = options.canvas
      renderers.push(this)
    }

    setClearColor(color, alpha) {
      this.clear = [color, alpha]
    }

    setPixelRatio(ratio) {
      this.pixelRatio = ratio
    }

    setSize(width, height) {
      this.sizes.push([width, height])
    }

    render(scene, camera) {
      this.scene = scene
      this.camera = camera
      this.draws.push(currentTime)
    }

    dispose() {
      this.disposed = true
    }

    forceContextLoss() {
      this.contextReleased = true
    }
  }
  const module = { exports: {} }
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    require: (name) => (name === 'three' ? { ...THREE, WebGLRenderer: Renderer } : vue),
    window,
    document,
    ResizeObserver,
    IntersectionObserver
  })
  const createRoot = () => {
    const root = new Element()
    root.connected = true
    return root
  }
  const root = createRoot()
  return {
    root,
    createRoot,
    Element,
    module: module.exports,
    controller: autoCreate ? module.exports.createAeroAmbientEffects(root) : null,
    document,
    window,
    media,
    resizes,
    intersections,
    contexts,
    renderers,
    frames,
    intersect: (visible, index = 0) => intersections[index].callback([{ isIntersecting: visible }]),
    flushFrame: (time) => {
      currentTime = time
      const callbacks = [...frames.values()]
      frames.clear()
      callbacks.forEach((callback) => callback(time))
    }
  }
}

test('upstream two-layer particles use transparent white/blue sprites and draw at no more than 30 fps', async () => {
  const h = harness()
  assert.equal(h.renderers.length, 0, 'allocation waits for the initial viewport observation')
  h.intersect(true)
  await settle()
  const renderer = h.renderers[0]
  assert.equal(renderer.domElement.className, 'aero-ambient-canvas')
  assert.equal(renderer.domElement.getAttribute('aria-hidden'), 'true')
  assert.deepEqual(renderer.clear, [0x171a20, 0], 'existing dark dashboard remains the backdrop')
  assert.equal(renderer.pixelRatio, 1.25)
  assert.deepEqual(
    renderer.sizes.at(-1),
    [960, 540],
    'viewport sizing ignores the long scroll content'
  )
  h.flushFrame(0)
  const [main, accent] = renderer.scene.children
  assert.equal(main.geometry.attributes.position.count, 220)
  assert.equal(accent.geometry.attributes.position.count, 18)
  assert.equal(main.material.opacity, 0.72)
  assert.equal(accent.material.opacity, 0.55)
  assert.equal(h.contexts[0].stops[0][1], 'rgba(255,255,255,0.9)')
  assert.equal(h.contexts[1].stops[0][1], 'rgba(62,106,225,1)')
  const version = main.geometry.attributes.position.version
  h.flushFrame(10)
  assert.equal(
    main.geometry.attributes.position.version,
    version,
    'skipped draws also skip simulation'
  )
  for (let time = 20; time <= 1000; time += 10) {
    h.flushFrame(time)
    assert.equal(h.frames.size, 1, 'only one ambient frame is outstanding')
  }
  assert.ok(renderer.draws.length <= 31)
  assert.ok(
    renderer.draws.slice(1).every((time, index) => time - renderer.draws[index] >= 1000 / 30)
  )
  assert.notEqual(renderer.camera.position.x, 0, 'the original camera sway advances')
  h.controller.dispose()
})

test('particles rise, drift and wrap, while pause/resume freezes elapsed time', async () => {
  const h = harness()
  h.intersect(true)
  await settle()
  h.flushFrame(0)
  const renderer = h.renderers[0]
  const [main, accent] = renderer.scene.children
  const mainValues = main.geometry.attributes.position.array
  const accentValues = accent.geometry.attributes.position.array
  const previous = [mainValues[0], mainValues[1], accentValues[1]]
  h.flushFrame(40)
  assert.ok(mainValues[1] > previous[1])
  assert.ok(accentValues[1] > previous[2])
  assert.notEqual(mainValues[0], previous[0])
  mainValues[1] = accentValues[1] = 13.1
  h.flushFrame(80)
  assert.equal(mainValues[1], -13)
  assert.equal(accentValues[1], -13)
  const yBeforePause = mainValues[1]
  h.controller.pause()
  assert.equal(h.frames.size, 0)
  assert.equal(renderer.domElement.style.visibility, 'hidden')
  h.controller.resume()
  h.flushFrame(600_000)
  assert.ok(
    mainValues[1] - yBeforePause < 0.1,
    'a long inactive period does not jump the simulation'
  )
  assert.equal(h.renderers.length, 1, 'resuming reuses its own context')
  h.controller.dispose()
})

test('background, offscreen, zero-size and reduced-motion states stop all ambient frames', async () => {
  const h = harness()
  h.intersect(true)
  await settle()
  h.document.hidden = true
  h.document.emit('visibilitychange')
  assert.equal(h.frames.size, 0)
  h.document.hidden = false
  h.document.emit('visibilitychange')
  assert.equal(h.frames.size, 1)
  h.intersect(false)
  assert.equal(h.frames.size, 0)
  h.intersect(true)
  h.root.clientWidth = 0
  h.resizes[0].callback()
  assert.equal(h.frames.size, 0)
  h.root.clientWidth = 1280
  h.root.clientHeight = 720
  h.document.emit('fullscreenchange')
  assert.deepEqual(h.renderers[0].sizes.at(-1), [1280, 720])
  h.media.matches = true
  h.media.emit('change')
  assert.equal(h.frames.size, 0)
  assert.equal(h.renderers[0].disposed, true)
  assert.equal(h.root.getAttribute('data-aero-ambient'), 'reduced')
  h.media.matches = false
  h.media.emit('change')
  await settle()
  assert.equal(h.renderers.length, 2)
  assert.equal(h.frames.size, 1)
  h.controller.dispose()
})

test('unmount frees every geometry, material, texture, observer, listener and pending import', async () => {
  const h = harness()
  h.intersect(true)
  await settle()
  h.flushFrame(0)
  const renderer = h.renderers[0]
  let disposedResources = 0
  for (const points of renderer.scene.children) {
    for (const resource of [points.geometry, points.material, points.material.map]) {
      resource.addEventListener('dispose', () => (disposedResources += 1))
    }
  }
  h.controller.dispose()
  h.controller.dispose()
  assert.equal(disposedResources, 6)
  assert.equal(renderer.disposed, true)
  assert.equal(renderer.contextReleased, true)
  assert.equal(renderer.domElement.parent, null)
  assert.equal(renderer.domElement.listenerCount, 0)
  assert.equal(h.frames.size, 0)
  assert.equal(h.resizes[0].observed.size, 0)
  assert.equal(h.intersections[0].observed.size, 0)
  assert.equal(h.document.listenerCount, 0)
  assert.equal(h.window.listenerCount, 0)
  assert.equal(h.window.visualViewport.listenerCount, 0)
  assert.equal(h.media.listenerCount, 0)
  assert.equal(h.root.getAttribute('data-aero-ambient'), null)
  const pending = harness()
  pending.intersect(true)
  pending.controller.dispose()
  await settle()
  assert.equal(pending.renderers.length, 0)
  assert.equal(pending.root.getAttribute('data-aero-ambient'), null)
})

test('WebGL, sprite or context failures leave the existing CSS background usable', async () => {
  const absent = harness({ webgl: false })
  absent.intersect(true)
  await settle()
  assert.equal(absent.root.getAttribute('data-aero-ambient'), 'unavailable')
  assert.equal(absent.renderers.length, 0)
  assert.equal(absent.root.children.length, 0)
  absent.controller.dispose()

  const noSprite = harness({ sprites: false })
  noSprite.intersect(true)
  await settle()
  assert.equal(noSprite.root.getAttribute('data-aero-ambient'), 'unavailable')
  assert.equal(noSprite.renderers[0].disposed, true)
  noSprite.controller.dispose()

  const lost = harness()
  lost.intersect(true)
  await settle()
  lost.renderers[0].domElement.emit('webglcontextlost')
  assert.equal(lost.root.getAttribute('data-aero-ambient'), 'unavailable')
  assert.equal(lost.renderers[0].contextReleased, true)
  assert.equal(lost.frames.size, 0)
  lost.controller.dispose()

  const reduced = harness({ reduced: true })
  reduced.intersect(true)
  await settle()
  assert.equal(reduced.renderers.length, 0)
  assert.equal(reduced.frames.size, 0)
  reduced.controller.dispose()
})

test('the Vue composable pauses for KeepAlive and replaces/disposes a changed screen ref', async () => {
  const h = harness({ autoCreate: false })
  const screen = vue.shallowRef(h.root)
  const show = vue.ref(true)
  const Dashboard = {
    setup() {
      h.module.useAeroAmbientEffects(screen)
      return () => null
    }
  }
  const Other = { render: () => null }
  const host = vue.createRenderer({
    createElement: () => new h.Element(),
    createText: () => new h.Element(),
    createComment: () => new h.Element(),
    insert: (child, parent) => parent.appendChild(child),
    remove: (child) => child.remove(),
    setText() {},
    setElementText() {},
    parentNode: (node) => node.parent,
    nextSibling: () => null,
    patchProp() {}
  })
  const app = host.createApp({
    render: () =>
      vue.h(vue.KeepAlive, null, { default: () => vue.h(show.value ? Dashboard : Other) })
  })
  app.mount(h.createRoot())
  h.intersect(true)
  await settle()
  assert.equal(h.frames.size, 1)
  show.value = false
  await vue.nextTick()
  assert.equal(h.frames.size, 0)
  assert.equal(h.root.getAttribute('data-aero-ambient'), 'paused')
  show.value = true
  await vue.nextTick()
  assert.equal(h.frames.size, 1)
  screen.value = h.createRoot()
  await vue.nextTick()
  assert.equal(h.renderers[0].disposed, true)
  h.intersect(true, 1)
  await settle()
  assert.equal(h.renderers.length, 2)
  assert.equal(h.renderers[1].domElement.parent, screen.value)
  app.unmount()
  assert.equal(h.frames.size, 0)
  assert.equal(h.renderers[1].disposed, true)
})
