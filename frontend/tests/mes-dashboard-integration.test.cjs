const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const dayjs = require('dayjs')
const { parse, compileScript } = require('vue/compiler-sfc')

const compile = (source) =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
const quantityModule = { exports: {} }
vm.runInNewContext(
  compile(fs.readFileSync(path.join(__dirname, '../src/utils/mesQuantity.ts'), 'utf8')),
  { module: quantityModule, exports: quantityModule.exports }
)

function setup(t, production = async () => ({})) {
  const filename = path.join(__dirname, '../src/views/mes/dashboard/index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const module = { exports: {} }
  const mounted = [],
    unmounted = [],
    requests = [],
    destinations = [],
    intervals = new Map()
  let currentTime = new Date('2026-10-31T23:59:59')
  class ClockDate extends Date {
    constructor(...args) {
      super(...(args.length ? args : [currentTime.getTime()]))
    }
  }
  const dependencies = {
    vue,
    dayjs,
    '@/utils/mesQuantity': quantityModule.exports,
    '@/utils/formatTime': { formatDate: (value) => value },
    '@/components/Echart': {},
    './components/ChinaDistributionMap.vue': {},
    './components/chinaDistribution': { YUNSHU_EXAMPLE_CITIES: [] },
    '@/api/mes/dashboard': {
      DashboardApi: {
        production: (params) => {
          requests.push(params)
          return production(params)
        }
      }
    }
  }
  vm.runInNewContext(
    compile(compileScript(descriptor, { id: 'dashboard-integration' }).content),
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || require(name),
      ...vue,
      Date: ClockDate,
      document: { hidden: false },
      useRouter: () => ({ push: (value) => destinations.push(value) }),
      onMounted: (callback) => mounted.push(callback),
      onBeforeUnmount: (callback) => unmounted.push(callback),
      setInterval: (callback, delay) => {
        intervals.set(delay, callback)
        return delay
      },
      clearInterval: (id) => intervals.delete(id)
    },
    { filename }
  )
  const scope = vue.effectScope()
  const state = scope.run(() => module.exports.default.setup({}, { expose() {} }))
  t.after(() => {
    unmounted.forEach((callback) => callback())
    scope.stop()
  })
  return {
    state,
    requests,
    destinations,
    intervals,
    mounted,
    setTime: (time) => {
      currentTime = new Date(time)
    }
  }
}

const payload = (changes = {}) => ({
  date: '2026-10-31',
  basis: '缺料：全厂当前，不受日期筛选',
  taskCount: 0,
  feedbackCount: 0,
  tasks: [],
  workstations: [],
  trend: [],
  devices: [],
  ...changes
})

test('unscheduled stations remain available and exact task quantities survive the new layout', (t) => {
  const { state } = setup(t)
  state.data.value = payload({
    taskCount: 2,
    workstations: [
      { id: 10, name: '未排产工位' },
      { id: 20, name: '精密加工' }
    ],
    tasks: [
      { workstationId: 20, quantity: '9999999999999.000001', producedQuantity: '0.000001' },
      { workstationId: 20, quantity: '0.000001', producedQuantity: '0.000002' }
    ]
  })
  assert.deepEqual(
    Array.from(state.workstationOptions.value, (s) => s.scheduleLabel),
    ['有排产', '未排产']
  )
  const stations = state.stationTasks.value
  assert.equal(stations[0].target, '9999999999999.000002')
  assert.equal(stations[0].produced, '0.000003')
  assert.equal(stations[1].count, 0)
  assert.equal(state.activeStationCount.value, 1)
})

test('truncated task lists and other station scopes do not falsely mark stations unscheduled', (t) => {
  const { state } = setup(t)
  state.data.value = payload({ taskCount: 101, workstations: [{ id: 10 }, { id: 20 }] })
  assert.ok(state.workstationOptions.value.every((s) => s.scheduleLabel === '待查看'))
  state.data.value.taskCount = 0
  state.filters.workstationId = 10
  assert.equal(state.workstationOptions.value.find((s) => s.id === 20).scheduleLabel, '待查看')
  assert.deepEqual(
    Array.from(state.stationTasks.value, (s) => s.id),
    [10]
  )
})

test('date picker exposes scoped history and today shortcuts without silently changing the day', (t) => {
  const { state } = setup(t)
  state.filters.date = '2026-10-31'
  state.data.value = payload({ availability: { latestActivityDate: '2026-10-28' } })
  assert.equal(state.filters.date, '2026-10-31')
  assert.equal(dayjs(state.dateShortcuts.value[1].value()).format('YYYY-MM-DD'), '2026-10-28')
  assert.equal(dayjs(state.dateShortcuts.value[0].value()).format('YYYY-MM-DD'), '2026-10-31')
  assert.match(state.productionContext.value, /暂无排产/)
  state.data.value.date = '2026-10-28'
  assert.match(state.productionContext.value, /历史生产数据/)
  state.data.value.availability.latestActivityDate = null
  assert.equal(state.dateShortcuts.value.length, 1)
})

test('empty trend still supplies seven dated zero values and station navigation uses the chosen ID', (t) => {
  const { state, destinations } = setup(t)
  state.data.value = payload()
  const chart = state.trendOptions.value
  assert.equal(chart.xAxis.data.length, 7)
  assert.equal(chart.xAxis.data.at(-1), '10-31')
  assert.ok(chart.series.every((series) => series.data.every((v) => v === 0)))
  state.openStation(42)
  state.enterWorkbench()
  assert.equal(destinations[0].query.workstationId, 42)
})

test('the new layout keeps last successful data and rejects out-of-order filter responses', async (t) => {
  const pending = []
  const { state } = setup(
    t,
    () => new Promise((resolve, reject) => pending.push({ resolve, reject }))
  )
  state.data.value = payload({ date: '2026-10-25' })
  const first = state.load()
  state.filters.workshopId = 3
  state.changeWorkshop()
  pending[1].resolve(payload({ date: '2026-10-30' }))
  await new Promise(setImmediate)
  pending[0].resolve(payload({ date: '2026-10-26' }))
  await first
  assert.equal(state.data.value.date, '2026-10-30')
  const failed = state.load()
  pending[2].reject(new Error('offline'))
  await failed
  assert.equal(state.data.value.date, '2026-10-30')
  assert.match(state.error.value, /刷新失败/)
})

test('midnight advances live dates but leaves deliberately selected history unchanged', async (t) => {
  const h = setup(t, async (params) => payload({ date: params.date }))
  h.state.filters.date = h.state.today.value
  h.mounted.forEach((callback) => callback())
  h.setTime('2026-11-01T00:00:01')
  h.intervals.get(1000)()
  assert.equal(h.state.filters.date, '2026-11-01')
  assert.equal(h.requests.at(-1).date, '2026-11-01')
  h.state.filters.date = '2026-10-28'
  const count = h.requests.length
  h.setTime('2026-11-02T00:00:01')
  h.intervals.get(1000)()
  assert.equal(h.state.filters.date, '2026-10-28')
  assert.equal(h.requests.length, count)
})
