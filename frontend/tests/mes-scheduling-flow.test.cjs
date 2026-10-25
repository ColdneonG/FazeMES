const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const constants = {
  MesProWorkOrderStatusEnum: { PREPARE: 0, CONFIRMED: 1 },
  MesProWorkOrderTypeEnum: { SELF: 1, PURCHASE: 3 },
  MesProTaskStatusEnum: { PREPARE: 0 }
}

function setup(t, relative, { props = {}, dependencies = {}, route, allowed = true } = {}) {
  const filename = path.join(__dirname, '../src/views/mes/pro/task', relative)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const module = { exports: {} }
  const events = [],
    warnings = [],
    destinations = [],
    mounted = [],
    activated = []
  const currentRoute = route || vue.reactive({ path: '/mes/pro/task', query: {} })
  const aliases = {
    vue,
    '@/utils/mesQuantity': { quantityRule: () => ({}) },
    '@/utils/formatTime': {},
    '@/utils/dict': {},
    '@/utils/tree': { handleTree: (list) => list },
    '@/utils/permission': { checkPermi: () => allowed },
    '@/utils/constants': { CommonStatusEnum: { ENABLE: 0 } },
    '@/views/mes/utils/constants': constants,
    '@/api/mes/pro/workorder': { ProWorkOrderApi: {} },
    '@/api/mes/pro/route/process': { ProRouteProcessApi: {} },
    '@/api/mes/pro/task': { ProTaskApi: {} },
    './scheduleReview': { reviewScheduleChanges: async () => true },
    ...dependencies
  }
  vm.runInNewContext(
    ts.transpileModule(compileScript(descriptor, { id: 'scheduling-flow' }).content, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) => aliases[name] || (name.endsWith('.vue') ? {} : require(name)),
      ...vue,
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({
        warning: (value) => warnings.push(value),
        success: () => {},
        confirm: async () => {},
        delConfirm: async () => {},
        error: () => {}
      }),
      useRouter: () => ({
        push: async (value) => {
          destinations.push(value)
        }
      }),
      useRoute: () => currentRoute,
      onMounted: (callback) => mounted.push(callback),
      onActivated: (callback) => activated.push(callback)
    },
    { filename }
  )
  const scope = vue.effectScope()
  const state = scope.run(() =>
    module.exports.default.setup(vue.reactive(props), {
      expose: () => {},
      emit: (name, value) => events.push([name, value])
    })
  )
  t.after(() => scope.stop())
  return { state, events, warnings, destinations, route: currentRoute, mounted, activated }
}

test('finishing scheduling leaves work-order and task execution states unchanged', (t) => {
  const { state, events } = setup(t, 'WorkOrderForm2.vue', {
    dependencies: {
      '@/api/mes/pro/workorder': {
        ProWorkOrderApi: {
          finishWorkOrder: () => assert.fail('scheduling must never finish the work order')
        }
      }
    }
  })
  state.dialogVisible.value = true
  state.completeScheduling()
  assert.equal(state.dialogVisible.value, false)
  assert.equal(events[0][0], 'success')
})

test('a direct scheduling link still requires a confirmed self-production order', async (t) => {
  for (const order of [
    { id: 51, status: 0, type: 1 },
    { id: 51, status: 1, type: 3 }
  ]) {
    const { state, warnings } = setup(t, 'WorkOrderForm2.vue', {
      dependencies: {
        '@/api/mes/pro/workorder': {
          ProWorkOrderApi: {
            getWorkOrder: async () => order
          }
        }
      }
    })
    await state.open('schedule', 51)
    assert.equal(state.dialogVisible.value, false)
    assert.match(warnings[0], /先确认自行生产工单/)
  }
})

test('a scheduling deep link cannot open a creation form without creation permission', async (t) => {
  const { state, warnings } = setup(t, 'WorkOrderForm2.vue', { allowed: false })
  await state.open('schedule', 51)
  assert.equal(state.dialogVisible.value, false)
  assert.match(warnings[0], /没有排产权限/)
})

test('work-order deep links open outside the first page and follow reused-page query changes', async (t) => {
  const opened = []
  const { state, route, mounted } = setup(t, 'index.vue', {
    route: vue.reactive({ path: '/mes/pro/task', query: { workOrderId: '987654' } }),
    dependencies: {
      '@/api/mes/pro/workorder': {
        ProWorkOrderApi: { getWorkOrderPage: async () => ({ list: [], total: 0 }) }
      },
      '@/api/mes/pro/task': { ProTaskApi: { getGanttTaskList: async () => [] } }
    }
  })
  state.formRef.value = { open: async (...args) => opened.push(args) }
  await mounted[0]()
  assert.deepEqual(opened, [['schedule', 987654]])
  assert.equal(state.workOrderList.value.length, 0)
  route.query.workOrderId = '77'
  await vue.nextTick()
  assert.deepEqual(opened[1], ['schedule', 77])
  route.path = '/mes/pro/workorder'
  await vue.nextTick()
  route.path = '/mes/pro/task'
  await vue.nextTick()
  assert.deepEqual(opened[2], ['schedule', 77])
})

test('every saved plan refreshes both work-order quantities and gantt tasks', async (t) => {
  let orders = 0,
    gantt = 0
  const { state } = setup(t, 'index.vue', {
    dependencies: {
      '@/api/mes/pro/workorder': {
        ProWorkOrderApi: {
          getWorkOrderPage: async () => {
            orders++
            return { list: [{ id: 42, quantityScheduled: 10 }], total: 1 }
          }
        }
      },
      '@/api/mes/pro/task': {
        ProTaskApi: {
          getGanttTaskList: async () => {
            gantt++
            return [{ id: 61 }]
          }
        }
      }
    }
  })
  await state.refreshSchedule()
  assert.equal(orders, 1)
  assert.equal(gantt, 1)
  assert.equal(state.workOrderList.value[0].quantityScheduled, 10)
  assert.equal(state.ganttTasks.value[0].id, 61)
})

test('task handoff selects the task own workstation and work order and closes its parent dialog', async (t) => {
  const { state, destinations, events } = setup(t, 'ProTaskList.vue', {
    props: { workOrderId: 42, routeId: 7, processId: 8 }
  })
  await state.openWorkbench({ id: 61, workstationId: 11, workOrderId: 42 })
  assert.equal(destinations[0].path, '/mes/workbench')
  assert.equal(destinations[0].query.taskId, 61)
  assert.equal(destinations[0].query.workstationId, 11)
  assert.equal(destinations[0].query.workOrderId, 42)
  assert.equal(events[0][0], 'navigate')
})

const stationSelector = '../../md/workstation/components/MdWorkstationSelectDialog.vue'

test('a fixed workstation process survives modified queries, pagination, and reset', async (t) => {
  const requests = []
  const { state } = setup(t, stationSelector, {
    props: { multiple: false, processId: 7 },
    dependencies: {
      '@/api/mes/md/workstation': {
        MdWorkstationApi: {
          getWorkstationPage: async (query) => {
            requests.push({ ...query })
            return { list: [], total: 0 }
          }
        }
      }
    }
  })
  await state.open()
  state.queryParams.processId = 99
  state.queryParams.pageNo = 4
  await state.getList()
  assert.equal(requests[1].processId, 7)
  assert.equal(requests[1].pageNo, 4)
  state.queryParams.code = 'old'
  state.queryParams.workshopId = 5
  state.resetQuery()
  assert.equal(requests[2].processId, 7)
  assert.equal(requests[2].pageNo, 1)
  assert.equal(requests[2].code, undefined)
  assert.equal(requests[2].workshopId, undefined)
})

test('a fixed process rejects an incompatible single workstation and permits a matching one', (t) => {
  const { state, events, warnings } = setup(t, stationSelector, {
    props: { multiple: false, processId: 7 },
    dependencies: { '@/api/mes/md/workstation': { MdWorkstationApi: {} } }
  })
  state.dialogVisible.value = true
  state.currentRadioRow.value = { id: 11, processId: 8 }
  state.confirmSelect()
  assert.equal(events.length, 0)
  assert.equal(state.dialogVisible.value, true)
  assert.match(warnings[0], /当前工序/)
  state.currentRadioRow.value = { id: 12, processId: 7 }
  state.confirmSelect()
  assert.equal(events[0][1][0].id, 12)
  assert.equal(state.dialogVisible.value, false)
})

test('a fixed process validates reserved selections from every page', (t) => {
  const { state, events } = setup(t, stationSelector, {
    props: { multiple: true, processId: 7 },
    dependencies: { '@/api/mes/md/workstation': { MdWorkstationApi: {} } }
  })
  state.dialogVisible.value = true
  state.list.value = [{ id: 12, processId: 7 }]
  state.selectedRows.value = [
    { id: 11, processId: 8 },
    { id: 12, processId: 7 }
  ]
  state.confirmSelect()
  assert.equal(events.length, 0)
  assert.equal(state.dialogVisible.value, true)
  state.selectedRows.value = [
    { id: 13, processId: 7 },
    { id: 12, processId: 7 }
  ]
  state.confirmSelect()
  assert.equal(events[0][1].length, 2)
})

test('an unconstrained workstation selector keeps free process filtering and mixed selections', async (t) => {
  const requests = []
  const { state, events } = setup(t, stationSelector, {
    props: { multiple: true },
    dependencies: {
      '@/api/mes/md/workstation': {
        MdWorkstationApi: {
          getWorkstationPage: async (query) => {
            requests.push({ ...query })
            return { list: [], total: 0 }
          }
        }
      }
    }
  })
  state.queryParams.processId = 8
  await state.getList()
  assert.equal(requests[0].processId, 8)
  state.selectedRows.value = [
    { id: 11, processId: 8 },
    { id: 12, processId: 7 }
  ]
  state.confirmSelect()
  assert.equal(events[0][1].length, 2)
  state.resetQuery()
  assert.equal(requests[1].processId, undefined)
})
