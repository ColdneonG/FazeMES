const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function evaluate(source, filename, dependencies = {}, globals = {}) {
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) =>
        dependencies[name] || (name.startsWith('@') || name.endsWith('.vue') ? {} : require(name)),
      ...globals
    },
    { filename }
  )
  return module.exports
}
const constantsFile = path.join(__dirname, '../src/views/mes/utils/constants.ts')
const constants = evaluate(fs.readFileSync(constantsFile, 'utf8'), constantsFile)
const quantityFile = path.join(__dirname, '../src/utils/mesQuantity.ts')
const quantities = evaluate(fs.readFileSync(quantityFile, 'utf8'), quantityFile)
const sourceQuantityFile = path.join(__dirname, '../src/views/mes/pro/feedback/sourceQuantities.ts')
const sourceQuantities = evaluate(fs.readFileSync(sourceQuantityFile, 'utf8'), sourceQuantityFile, {
  '@/utils/mesQuantity': quantities
})
const flush = async () => {
  for (let i = 0; i < 8; i++) await vue.nextTick()
}
function setup(t, relative, dependencies = {}, initial = {}) {
  const filename = path.join(__dirname, '../src/views/mes', relative)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const route = vue.reactive({ path: '/mes/workbench', query: {}, ...initial })
  const navigations = [],
    messages = [],
    events = [],
    mounted = []
  const router = {
    push: async (location) => {
      navigations.push(location)
    },
    replace: async (location) => {
      navigations.push(location)
      route.path = location.path || route.path
      route.query = location.query || {}
    }
  }
  const component = evaluate(
    compileScript(descriptor, { id: 'production-handoff' }).content,
    filename,
    {
      vue,
      '@/views/mes/utils/constants': constants,
      '@/views/mes/pro/feedback/sourceQuantities': sourceQuantities,
      '@/utils/permission': { checkPermi: () => true },
      ...dependencies
    },
    {
      ...vue,
      onMounted: (callback) => mounted.push(callback),
      useRoute: () => route,
      useRouter: () => router,
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({
        confirm: async () => {},
        success: (value) => messages.push(value),
        warning: (value) => messages.push(value),
        error: (value) => messages.push(value)
      })
    }
  ).default
  const scope = vue.effectScope()
  const state = scope.run(() =>
    component.setup(
      {},
      {
        expose: () => {},
        emit: (name, value) => events.push([name, value])
      }
    )
  )
  t.after(() => scope.stop())
  return { state, route, router, navigations, messages, events, mounted }
}

test('material issue handoff filters the exact order and station and prefills a new draft', async (t) => {
  const queries = [],
    opened = []
  const app = setup(
    t,
    'wm/productissue/index.vue',
    {
      '@/api/mes/wm/productissue': {
        WmProductIssueApi: {
          getProductIssuePage: async (params) => {
            queries.push({ ...params })
            return { list: [], total: 0 }
          }
        }
      }
    },
    { path: '/mes/wm/product-issue', query: { workOrderId: '12', workstationId: '21' } }
  )
  await flush()
  assert.equal(queries.at(-1).workOrderId, 12)
  assert.equal(queries.at(-1).workstationId, 21)
  app.state.formRef.value = { open: (...args) => opened.push(args) }
  app.state.openForm('create')
  assert.equal(opened[0][2].workOrderId, 12)
  assert.equal(opened[0][2].workstationId, 21)
  app.state.openForm('update', 4)
  assert.equal(opened[1][2], undefined, 'existing documents must retain their stored relations')
  app.route.query = { workOrderId: '13' }
  await flush()
  assert.equal(queries.at(-1).workOrderId, 13)
  assert.equal(queries.at(-1).workstationId, undefined)
  app.route.query = { workOrderId: '-1', workstationId: 'invalid' }
  await flush()
  assert.equal(queries.at(-1).workOrderId, undefined)
  assert.equal(queries.at(-1).workstationId, undefined)
})

test('a prefilled issue stays unsaved until the user saves and a new blank issue clears context', async (t) => {
  let creates = 0
  const { state } = setup(t, 'wm/productissue/ProductIssueForm.vue', {
    '@/api/mes/wm/productissue': {
      WmProductIssueApi: {
        createProductIssue: async () => {
          creates++
          return 1
        }
      }
    }
  })
  await state.open('create', undefined, { workOrderId: 12, workstationId: 21 })
  assert.equal(state.formData.value.workOrderId, 12)
  assert.equal(state.formData.value.workstationId, 21)
  assert.equal(creates, 0)
  await state.open('create')
  assert.equal(state.formData.value.workOrderId, undefined)
  assert.equal(state.formData.value.workstationId, undefined)
})

test('successful material issue completion retains the finished document and hands off its context', async (t) => {
  const finished = []
  const app = setup(t, 'wm/productissue/ProductIssueForm.vue', {
    '@/api/mes/wm/productissue': {
      WmProductIssueApi: {
        getProductIssue: async () => ({ id: 4, workOrderId: 12, workstationId: 21, status: 3 }),
        finishProductIssue: async (id) => finished.push(id)
      }
    }
  })
  await app.state.open('finish', 4)
  await app.state.handleFinish()
  assert.deepEqual(finished, [4])
  assert.equal(app.state.formData.value.status, constants.MesWmProductIssueStatusEnum.FINISHED)
  assert.equal(app.state.formType.value, 'detail')
  assert.equal(app.state.dialogVisible.value, true)
  assert.equal(app.navigations.length, 0)
  await app.state.openWorkbench()
  assert.equal(app.navigations[0].path, '/mes/workbench')
  assert.equal(app.navigations[0].query.workOrderId, 12)
  assert.equal(app.navigations[0].query.workstationId, 21)
  assert.equal(app.state.dialogVisible.value, false)
})

test('failed issue completion retains pending state and never enters production', async (t) => {
  const app = setup(t, 'wm/productissue/ProductIssueForm.vue', {
    '@/api/mes/wm/productissue': {
      WmProductIssueApi: {
        getProductIssue: async () => ({ id: 4, workOrderId: 12, workstationId: 21, status: 3 }),
        finishProductIssue: async () => {
          throw new Error('stock unavailable')
        }
      }
    }
  })
  await app.state.open('finish', 4)
  await app.state.handleFinish()
  assert.equal(app.state.formData.value.status, 3)
  assert.equal(app.state.formType.value, 'finish')
  assert.equal(app.state.dialogVisible.value, true)
  assert.equal(app.navigations.length, 0)
  assert.equal(app.events.length, 0)
})

function workbench(t, query = {}, overrides = {}, taskOverrides = {}) {
  const contextIds = [],
    stationIds = []
  const tasks = {
    21: [
      { id: 101, workOrderId: 12 },
      { id: 102, workOrderId: 13 }
    ],
    22: [
      { id: 201, workOrderId: 14 },
      { id: 202, workOrderId: 15 }
    ]
  }
  const app = setup(
    t,
    'pro/workbench/index.vue',
    {
      '@/api/mes/pro/task': {
        ProTaskApi: {
          getTaskPage: async ({ workOrderId }) => ({
            list: Object.entries(tasks).flatMap(([station, rows]) =>
              rows
                .filter((row) => row.workOrderId === workOrderId)
                .map((row) => ({ ...row, workstationId: Number(station) }))
            )
          }),
          ...taskOverrides
        }
      },
      '@/api/mes/pro/workbench': {
        WorkbenchApi: {
          getWorkstations: async () => [{ id: 21 }, { id: 22 }],
          getTasks: async (station) => {
            stationIds.push(station)
            return tasks[station] || []
          },
          getAvailability: async () => ({ available: true }),
          getContext: async (id) => {
            contextIds.push(id)
            return { task: { id }, sops: [], approvers: [] }
          },
          ...overrides
        }
      }
    },
    { query }
  )
  return { ...app, contextIds, stationIds }
}

test('production opens the exact task and switches correctly when another task link reuses the page', async (t) => {
  const app = workbench(t, { workstationId: '21', taskId: '102' })
  await app.mounted[0]()
  assert.deepEqual(app.contextIds, [102])
  app.route.query = { workstationId: '22', taskId: '201', workOrderId: '14' }
  await flush()
  assert.deepEqual(app.contextIds, [102, 201])
  assert.equal(app.state.stationId.value, 22)
  assert.equal(app.state.context.value.task.id, 201)
  assert.equal(app.state.tasks.value.length, 1)
  assert.equal(app.state.tasks.value[0].workOrderId, 14)
})

test('an issue without a workstation still locates its own orders scheduled task', async (t) => {
  const app = workbench(t, { workOrderId: '14' })
  await app.mounted[0]()
  assert.deepEqual(app.stationIds, [22])
  assert.deepEqual(app.contextIds, [201])
  assert.equal(app.state.stationId.value, 22)
  assert.equal(app.state.context.value.task.id, 201)
})

test('order task lookup spans pages and selects the earliest planned executable task', async (t) => {
  const pages = []
  const app = workbench(
    t,
    { workOrderId: '14' },
    {
      getTasks: async (station) => [{ id: station === 22 ? 902 : 901, workOrderId: 14 }]
    },
    {
      getTaskPage: async ({ pageNo }) => {
        pages.push(pageNo)
        return {
          total: 101,
          list:
            pageNo === 1
              ? [
                  ...Array.from({ length: 99 }, (_, index) => ({
                    id: index + 1,
                    workstationId: 999
                  })),
                  { id: 901, workstationId: 21, startTime: '2026-10-26T08:00:00' }
                ]
              : [{ id: 902, workstationId: 22, startTime: '2026-10-25T08:00:00' }]
        }
      }
    }
  )
  await app.mounted[0]()
  assert.deepEqual(pages, [1, 2])
  assert.deepEqual(app.contextIds, [902])
  assert.equal(app.state.stationId.value, 22)
})

test('a cancelled route transition leaves a finished issue open for retry', async (t) => {
  const app = setup(t, 'wm/productissue/ProductIssueForm.vue', {
    '@/api/mes/wm/productissue': {
      WmProductIssueApi: {
        getProductIssue: async () => ({ id: 4, workOrderId: 12, status: 4 })
      }
    }
  })
  await app.state.open('detail', 4)
  app.router.push = async () => ({ type: 4 })
  await app.state.openWorkbench()
  assert.equal(app.state.dialogVisible.value, true)
})

test('an order with no executable task asks for scheduling instead of showing another order', async (t) => {
  const app = workbench(t, { workOrderId: '999' })
  await app.mounted[0]()
  assert.deepEqual(app.stationIds, [])
  assert.deepEqual(app.contextIds, [])
  assert.equal(app.state.context.value, undefined)
  assert.equal(app.state.loading.value, false)
  assert.match(app.state.loadError.value, /生产排产/)
})

test('invalid task and workstation links never silently open another task', async (t) => {
  const app = workbench(t, { workstationId: '21', taskId: '999' })
  await app.mounted[0]()
  assert.deepEqual(app.contextIds, [])
  assert.equal(app.state.context.value, undefined)
  assert.match(app.state.loadError.value, /指定任务/)
  await app.state.selectTask(101)
  assert.equal(app.state.loadError.value, '')
  assert.equal(app.state.context.value.task.id, 101)
  app.contextIds.length = 0
  app.route.query = { workstationId: '999', taskId: '101' }
  await flush()
  assert.deepEqual(app.stationIds, [21])
  assert.deepEqual(app.contextIds, [])
  assert.match(app.state.loadError.value, /指定工作站/)
})

test('a slow previous order cannot overwrite the material issue list after a handoff', async (t) => {
  let release
  const oldRows = new Promise((resolve) => {
    release = resolve
  })
  const app = setup(
    t,
    'wm/productissue/index.vue',
    {
      '@/api/mes/wm/productissue': {
        WmProductIssueApi: {
          getProductIssuePage: ({ workOrderId }) =>
            workOrderId === 12
              ? oldRows
              : Promise.resolve({ list: [{ id: 2, workOrderId: 13 }], total: 1 })
        }
      }
    },
    { path: '/mes/wm/product-issue', query: { workOrderId: '12' } }
  )
  app.route.query = { workOrderId: '13' }
  await flush()
  release({ list: [{ id: 1, workOrderId: 12 }], total: 1 })
  await flush()
  assert.equal(app.state.queryParams.workOrderId, 13)
  assert.equal(app.state.list.value[0].workOrderId, 13)
  assert.equal(app.state.loading.value, false)
})

test('material issue to production retains the order and does not select another orders task', async (t) => {
  const app = workbench(t, { workstationId: '21', workOrderId: '13' })
  await app.mounted[0]()
  assert.deepEqual(app.contextIds, [102])
  assert.equal(app.state.tasks.value.length, 1)
  app.route.query = { workstationId: '21', workOrderId: '999' }
  await flush()
  assert.equal(app.state.tasks.value.length, 0)
  assert.equal(app.state.context.value, undefined)
  assert.deepEqual(app.contextIds, [102])
})

test('a stale task fetch cannot replace a newer production handoff', async (t) => {
  let release
  const oldTasks = new Promise((resolve) => {
    release = resolve
  })
  const app = workbench(
    t,
    { workstationId: '21', taskId: '101' },
    {
      getTasks: (id) => (id === 21 ? oldTasks : Promise.resolve([{ id: 201, workOrderId: 14 }]))
    }
  )
  const firstLoad = app.mounted[0]()
  await flush()
  app.route.query = { workstationId: '22', taskId: '201' }
  await flush()
  release([{ id: 101, workOrderId: 12 }])
  await firstLoad
  await flush()
  assert.deepEqual(app.contextIds, [201])
  assert.equal(app.state.context.value.task.id, 201)
})
