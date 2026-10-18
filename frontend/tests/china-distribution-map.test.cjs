const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const componentPath = path.join(
  __dirname,
  '../src/views/mes/dashboard/components/ChinaDistributionMap.vue'
)
const descriptor = parse(fs.readFileSync(componentPath, 'utf8')).descriptor
const componentSource = compileScript(descriptor, { id: 'distribution-map-test' }).content.replace(
  'import.meta.env.BASE_URL',
  "'/'"
)
const compile = (source) =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
const helperModule = { exports: {} }
vm.runInNewContext(
  compile(fs.readFileSync(path.join(path.dirname(componentPath), 'chinaDistribution.ts'), 'utf8')),
  { module: helperModule, exports: helperModule.exports }
)

const settle = async () => {
  await new Promise((resolve) => setImmediate(resolve))
  await vue.nextTick()
}

function mountMap({ reduced = true, hidden = false, initialProps = {} } = {}) {
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
      for (const listener of this.listeners.get(type) || []) listener()
    }
  }
  const document = new Events()
  document.hidden = hidden
  const media = new Events()
  media.matches = reduced
  const window = new Events()
  window.matchMedia = () => media
  const frames = new Map()
  let frameId = 0
  const requestAnimationFrame = (callback) => {
    const id = ++frameId
    frames.set(id, callback)
    return id
  }
  const cancelAnimationFrame = (id) => frames.delete(id)
  const intersections = []
  class Observer {
    observe() {}

    disconnect() {}
  }
  class IntersectionObserver extends Observer {
    constructor(callback) {
      super()
      intersections.push(callback)
    }
  }
  let resolveMap
  const fetchedMap = new Promise((resolve) => (resolveMap = resolve))
  const charts = []
  let registered = false
  const echarts = {
    use() {},
    getMap: () => registered,
    registerMap: () => (registered = true),
    graphic: { LinearGradient: class {} },
    init: (element) => {
      const chart = {
        element,
        options: [],
        disposed: false,
        setOption(option) {
          this.options.push(option)
        },
        resize() {},
        dispose() {
          this.disposed = true
        }
      }
      charts.push(chart)
      return chart
    }
  }
  const componentModule = { exports: {} }
  vm.runInNewContext(compile(componentSource), {
    module: componentModule,
    exports: componentModule.exports,
    require: (name) => {
      if (name === 'vue') return vue
      if (name === 'echarts/core') return echarts
      if (name === './chinaDistribution') return helperModule.exports
      return {}
    },
    document,
    window,
    requestAnimationFrame,
    cancelAnimationFrame,
    ResizeObserver: Observer,
    IntersectionObserver,
    fetch: () => fetchedMap
  })
  const Component = componentModule.exports.default
  Component.render = () =>
    vue.h('div', { ref: 'rootRef' }, [
      vue.h('div', { ref: 'mapRef' }),
      vue.h('div', { ref: 'rankingRef' })
    ])
  const createElement = () => ({ children: [], parent: null, clientWidth: 500, clientHeight: 300 })
  const renderer = vue.createRenderer({
    createElement,
    createText: createElement,
    createComment: createElement,
    insert: (child, parent) => {
      child.parent = parent
      parent.children.push(child)
    },
    remove: (child) => {
      if (child.parent)
        child.parent.children = child.parent.children.filter((item) => item !== child)
    },
    setText() {},
    setElementText() {},
    parentNode: (node) => node.parent,
    nextSibling: () => null,
    patchProp() {}
  })
  const props = vue.reactive(initialProps)
  const app = renderer.createApp({ render: () => vue.h(Component, props) })
  app.mount(createElement())
  const mapChart = () => charts.find((chart) => chart.options.some((option) => option.geo))
  return {
    app,
    props,
    document,
    charts,
    frames,
    mapChart,
    intersect: (visible) => intersections[0]([{ isIntersecting: visible }]),
    loadMap: async () => {
      resolveMap({ ok: true, json: async () => ({ type: 'FeatureCollection', features: [] }) })
      await settle()
    },
    flushFrames: () => {
      const callbacks = [...frames.values()]
      frames.clear()
      callbacks.forEach((callback) => callback())
    }
  }
}

test('reduced-motion map initializes when scrolled into view after the local map loads', async () => {
  const map = mountMap()
  map.intersect(false)
  await settle()
  await map.loadMap()
  map.flushFrames()
  assert.equal(map.mapChart(), undefined)
  map.intersect(true)
  await settle()
  map.flushFrames()
  assert.ok(map.mapChart(), 'visibility must initialize the chart even when motion stays false')
  const dataOption = map.mapChart().options.at(-1)
  assert.equal(dataOption.animation, false)
  assert.equal(dataOption.series[0].type, 'scatter')
  map.app.unmount()
})

test('reduced-motion map initializes after background loading and leaves no RAF after unmount', async () => {
  const map = mountMap({ hidden: true })
  await map.loadMap()
  assert.equal(map.charts.length, 0)
  assert.equal(map.frames.size, 0, 'background dashboards do not schedule chart resize frames')
  map.document.hidden = false
  map.document.emit('visibilitychange')
  await settle()
  map.flushFrames()
  assert.ok(map.mapChart())
  map.app.unmount()
  assert.equal(map.frames.size, 0)
  assert.ok(map.charts.every((chart) => chart.disposed))
})

test('changing example data preserves the geo view and toggles the metric and series data', async () => {
  const map = mountMap({ initialProps: { cities: [], isExample: false, metricLabel: '工单数' } })
  await map.loadMap()
  map.flushFrames()
  const chart = map.mapChart()
  assert.equal(chart.options.at(-1).series[0].data.length, 0)
  map.props.cities = helperModule.exports.YUNSHU_EXAMPLE_CITIES
  map.props.isExample = true
  map.props.metricLabel = '销售展示示例'
  await settle()
  const example = chart.options.at(-1)
  assert.equal(example.series[0].data.length, 18)
  assert.equal(example.series[0].name, '销售展示示例')
  assert.equal(example.geo, undefined, 'data changes do not reset user pan/zoom')
  assert.match(example.aria.label.description, /示例/)
  map.props.cities = []
  map.props.isExample = false
  await settle()
  assert.equal(chart.options.at(-1).series[0].data.length, 0)
  map.app.unmount()
})

test('background tabs replace animated ripple series and schedule no continuous resize work', async () => {
  const map = mountMap({
    reduced: false,
    initialProps: { cities: helperModule.exports.YUNSHU_EXAMPLE_CITIES }
  })
  await map.loadMap()
  map.flushFrames()
  const chart = map.mapChart()
  assert.equal(chart.options.at(-1).series[0].type, 'effectScatter')
  map.document.hidden = true
  map.document.emit('visibilitychange')
  await settle()
  assert.equal(chart.options.at(-1).series[0].type, 'scatter')
  assert.equal(chart.options.at(-1).animation, false)
  assert.equal(map.frames.size, 0)
  map.app.unmount()
})

test('local-map loading that completes after unmount cannot schedule rendering', async () => {
  const map = mountMap()
  map.app.unmount()
  await map.loadMap()
  assert.equal(map.frames.size, 0)
  assert.ok(map.charts.every((chart) => chart.disposed))
})

test('real ECharts draws an empty China map and fits its initial full outline inside the canvas', () => {
  const echarts = require('echarts')
  const geoJson = JSON.parse(
    fs.readFileSync(path.join(__dirname, '../public/maps/china-provinces.geo.json'), 'utf8')
  )
  echarts.registerMap('review-china-outline', geoJson)
  const mounted = mountMap()
  // The same option factory used by the component can be read from a mounted chart below.
  return mounted.loadMap().then(() => {
    mounted.flushFrames()
    const baseOption = mounted.mapChart().options.find((option) => option.geo)
    const coordinates = []
    const visit = (coordinate) => {
      if (typeof coordinate[0] === 'number') coordinates.push(coordinate)
      else coordinate.forEach(visit)
    }
    geoJson.features.forEach((feature) => visit(feature.geometry.coordinates))
    for (const [width, height] of [
      [360, 260],
      [600, 320]
    ]) {
      const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width, height })
      chart.setOption({
        ...baseOption,
        animation: false,
        geo: { ...baseOption.geo, map: 'review-china-outline' },
        series: [{ type: 'scatter', coordinateSystem: 'geo', data: [] }]
      })
      const svg = chart.renderToSVGString()
      assert.ok((svg.match(/<path/g) || []).length >= 34, 'empty values retain provincial geometry')
      const pixels = coordinates.map((coordinate) =>
        chart.convertToPixel({ geoIndex: 0 }, coordinate)
      )
      assert.ok(pixels.every(([x, y]) => x >= 0 && x <= width && y >= 0 && y <= height))
      chart.dispose()
    }
    mounted.app.unmount()
  })
})
