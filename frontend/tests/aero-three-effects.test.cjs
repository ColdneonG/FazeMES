const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(
  path.join(__dirname, '../src/views/mes/dashboard/effects/useAeroThreeEffects.ts'),
  'utf8'
)
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText

// This dependency-free harness tests interaction/lifetime contracts. The renderer
// double records allocations, frames and disposal; it does not implement graphics.
function harness({ reduced = false, webgl = true } = {}) {
  class Events {
    listeners = new Map()

    addEventListener(name, callback) {
      if (!this.listeners.has(name)) this.listeners.set(name, new Set())
      this.listeners.get(name).add(callback)
    }

    removeEventListener(name, callback) {
      this.listeners.get(name)?.delete(callback)
    }

    emit(name, details = {}) {
      const event = {
        target: this,
        pointerType: 'mouse',
        buttons: 0,
        clientX: 160,
        clientY: 90,
        relatedTarget: null,
        preventDefault() {},
        ...details
      }
      for (const callback of this.listeners.get(name) || []) callback(event)
    }

    get listenerCount() {
      return [...this.listeners.values()].reduce((sum, entries) => sum + entries.size, 0)
    }
  }

  class Element extends Events {
    parent = null
    children = []
    attributes = new Map()
    classes = new Set()
    properties = new Map()
    connected = false
    clientWidth = 320
    clientHeight = 180
    hidden = false
    style = {
      setProperty: (name, value) => this.properties.set(name, value),
      removeProperty: (name) => this.properties.delete(name)
    }
    classList = {
      add: (name) => this.classes.add(name),
      remove: (name) => this.classes.delete(name),
      contains: (name) => this.classes.has(name)
    }

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
      if (this.parent) this.parent.children = this.parent.children.filter((child) => child !== this)
      this.parent = null
      this.connected = false
    }

    contains(node) {
      for (let current = node; current; current = current.parent) {
        if (current === this) return true
      }
      return false
    }

    closest(selector) {
      const selectors = selector.split(',').map((part) => part.trim())
      for (let current = this; current; current = current.parent) {
        if (
          selectors.some((part) =>
            part.startsWith('.')
              ? current.classes.has(part.slice(1))
              : current.attributes.has(part.slice(1, -1))
          )
        ) {
          return current
        }
      }
      return null
    }

    getBoundingClientRect() {
      return { left: 0, top: 0, width: this.clientWidth, height: this.clientHeight }
    }

    getContext() {
      return webgl ? { getExtension: () => ({ loseContext() {} }) } : null
    }
  }

  const frames = new Map()
  let frameId = 0
  const media = new Map()
  const window = new Events()
  window.devicePixelRatio = 2
  window.visualViewport = new Events()
  window.requestAnimationFrame = (callback) => {
    const id = ++frameId
    frames.set(id, callback)
    return id
  }
  window.cancelAnimationFrame = (id) => frames.delete(id)
  window.matchMedia = (query) => {
    if (!media.has(query)) {
      const entry = new Events()
      entry.matches = query.includes('reduced-motion') ? reduced : true
      media.set(query, entry)
    }
    return media.get(query)
  }
  const document = new Events()
  document.hidden = false
  document.createElement = () => new Element()
  const observers = []
  class ResizeObserver {
    observed = new Set()

    constructor(callback) {
      this.callback = callback
      observers.push(this)
    }

    observe(element) {
      this.observed.add(element)
    }

    unobserve(element) {
      this.observed.delete(element)
    }

    disconnect() {
      this.observed.clear()
    }
  }

  const allocations = []
  const renderers = []
  const rotation = () => ({ x: 0, y: 0, z: 0 })
  class Resource {
    disposed = false

    constructor() {
      allocations.push(this)
    }

    dispose() {
      this.disposed = true
    }

    setAttribute() {}
  }
  class Group {
    rotation = rotation()
    children = []

    add(...nodes) {
      this.children.push(...nodes)
    }
  }
  class Renderer {
    renders = 0
    sizes = []
    disposed = false
    contextReleased = false

    constructor({ canvas }) {
      this.domElement = canvas
      renderers.push(this)
    }

    setPixelRatio(value) {
      this.pixelRatio = value
    }

    setClearColor() {}

    setSize(width, height) {
      this.sizes.push([width, height])
    }

    render() {
      this.renders += 1
    }

    dispose() {
      this.disposed = true
    }

    forceContextLoss() {
      this.contextReleased = true
    }
  }
  const THREE = {
    WebGLRenderer: Renderer,
    Scene: Group,
    Group,
    PerspectiveCamera: class {
      position = { z: 0 }

      updateProjectionMatrix() {}
    },
    BufferGeometry: Resource,
    BufferAttribute: Resource,
    PointsMaterial: Resource,
    RingGeometry: Resource,
    MeshBasicMaterial: Resource,
    Points: Group,
    Mesh: Group,
    AdditiveBlending: 2,
    DoubleSide: 2
  }
  const module = { exports: {} }
  vm.runInNewContext(compiled, {
    exports: module.exports,
    module,
    require: (name) => (name === 'three' ? THREE : {}),
    window,
    document,
    Element,
    Node: Element,
    ResizeObserver
  })
  const root = new Element()
  root.connected = true
  const addCard = () => {
    const card = new Element()
    card.classes.add('screen-panel')
    root.appendChild(card)
    return card
  }
  const first = addCard()
  const controller = module.exports.createAeroThreeEffects(root)
  return {
    root,
    first,
    addCard,
    controller,
    window,
    document,
    observers,
    frames,
    renderers,
    allocations,
    reducedMedia: [...media.values()][0],
    pointer: (card, details) => root.emit('pointermove', { target: card, ...details }),
    flushFrame: (time) => {
      const pending = [...frames.values()]
      frames.clear()
      pending.forEach((callback) => callback(time))
    }
  }
}

const settleImports = () => new Promise((resolve) => setImmediate(resolve))

test('one canvas follows late-rendered cards, resizes, and stops rendering outside a card', async () => {
  const h = harness()
  assert.equal(h.renderers.length, 0, 'idle dashboards do not allocate a WebGL context')
  assert.equal(h.frames.size, 0)
  h.pointer(h.first)
  await settleImports()
  assert.equal(h.renderers.length, 1)
  const renderer = h.renderers[0]
  assert.equal(renderer.pixelRatio, 1.5, 'DPR is capped on large displays')
  assert.equal(renderer.domElement.parent, h.first)
  assert.equal(h.frames.size, 1)
  h.flushFrame(16)
  assert.equal(renderer.renders, 1)
  assert.equal(h.frames.size, 1, 'rendering keeps exactly one outstanding frame')

  const second = h.addCard()
  h.pointer(second, { clientX: 10, clientY: 10 })
  assert.equal(h.renderers.length, 1, 'dynamic cards share the dashboard renderer')
  assert.equal(renderer.domElement.parent, second)
  assert.equal(h.first.classes.has('three-hover-active'), false)
  assert.equal(h.frames.size, 1)
  second.clientWidth = 640
  h.observers[0].callback()
  assert.deepEqual(renderer.sizes.at(-1), [640, 180], 'active RAF does not suppress resizing')
  second.clientHeight = 300
  h.document.emit('fullscreenchange')
  assert.deepEqual(renderer.sizes.at(-1), [640, 300])
  h.root.emit('pointerleave')
  assert.equal(h.frames.size, 0)
  assert.equal(renderer.domElement.parent, null)
  assert.equal(second.properties.size, 0)
  h.controller.dispose()
})

test('dragging, touch input, hidden tabs and motion preference changes leave controls stable', async () => {
  const h = harness()
  h.pointer(h.first, { pointerType: 'touch' })
  await settleImports()
  assert.equal(h.renderers.length, 0)
  h.pointer(h.first)
  await settleImports()
  h.root.emit('pointerdown', { target: h.first, buttons: 1 })
  h.pointer(h.first, { buttons: 1 })
  assert.equal(h.first.classes.has('three-hover-active'), false)
  assert.equal(h.frames.size, 0, 'map dragging cancels the compositor')
  h.pointer(h.first)
  h.document.hidden = true
  h.document.emit('visibilitychange')
  assert.equal(h.frames.size, 0)
  assert.equal(h.root.getAttribute('data-aero-motion'), 'paused')
  h.document.hidden = false
  h.document.emit('visibilitychange')
  assert.equal(h.frames.size, 0, 'visibility alone must not reactivate a stale hover')
  h.pointer(h.first)
  h.reducedMedia.matches = true
  h.reducedMedia.emit('change')
  assert.equal(h.root.getAttribute('data-aero-motion'), 'reduced')
  assert.equal(h.frames.size, 0)
  assert.equal(h.renderers[0].disposed, true)
  h.controller.dispose()
})

test('unmount removes listeners, observers, frames, geometry and context, including pending imports', async () => {
  const h = harness()
  h.pointer(h.first)
  await settleImports()
  const renderer = h.renderers[0]
  h.controller.dispose()
  h.controller.dispose()
  assert.equal(h.frames.size, 0)
  assert.equal(renderer.disposed, true)
  assert.equal(renderer.contextReleased, true)
  assert.equal(renderer.domElement.listenerCount, 0)
  // BufferAttribute is not separately disposable in Three.
  assert.equal(h.allocations.filter((resource) => resource.disposed).length, 6)
  assert.equal(h.observers[0].observed.size, 0)
  assert.equal(h.root.listenerCount, 0)
  assert.equal(h.window.listenerCount, 0)
  assert.equal(h.window.visualViewport.listenerCount, 0)
  assert.equal(h.document.listenerCount, 0)
  assert.equal(h.reducedMedia.listenerCount, 0)
  assert.equal(h.root.classes.has('aero-effects-enabled'), false)
  assert.equal(h.root.getAttribute('data-aero-motion'), null)
  h.pointer(h.first)
  assert.equal(h.frames.size, 0)

  const pending = harness()
  pending.pointer(pending.first)
  pending.controller.dispose()
  await settleImports()
  assert.equal(pending.renderers.length, 0, 'an import cannot allocate after unmount')
  assert.equal(pending.root.getAttribute('data-aero-webgl'), null)
})

test('WebGL absence or context loss falls back to CSS without intercepting business actions', async () => {
  const h = harness({ webgl: false })
  h.pointer(h.first)
  await settleImports()
  assert.equal(h.root.getAttribute('data-aero-webgl'), 'unavailable')
  assert.equal(h.first.classes.has('three-hover-active'), true)
  assert.equal(h.frames.size, 0)
  let businessClicks = 0
  h.first.addEventListener('click', () => (businessClicks += 1))
  h.first.emit('click')
  assert.equal(businessClicks, 1)
  h.controller.dispose()

  const lost = harness()
  lost.pointer(lost.first)
  await settleImports()
  lost.renderers[0].domElement.emit('webglcontextlost')
  assert.equal(lost.frames.size, 0)
  assert.equal(lost.root.getAttribute('data-aero-webgl'), 'unavailable')
  assert.equal(lost.renderers[0].disposed, true)
  assert.equal(lost.first.classes.has('three-hover-active'), true)
  lost.controller.dispose()
})

test('reduced motion and disconnected cards never start a renderer', async () => {
  const h = harness({ reduced: true })
  h.pointer(h.first)
  await settleImports()
  assert.equal(h.renderers.length, 0)
  h.controller.dispose()

  const detached = harness()
  detached.pointer(detached.first)
  detached.first.remove()
  await settleImports()
  assert.equal(detached.renderers.length, 0)
  detached.controller.dispose()
})
