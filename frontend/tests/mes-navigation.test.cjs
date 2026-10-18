const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

// Exercise the production TypeScript helpers without booting the application or a DOM.
const aliases = {}
function loadSource(source, filename, dependencies = aliases) {
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(
    compiled,
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || require(name),
      reactive: (value) => value,
      ref: (value) => ({ value }),
      unref: (value) => value?.value,
      URL
    },
    { filename }
  )
  return module.exports
}
function load(relativePath) {
  const filename = path.join(__dirname, '../src', relativePath)
  return loadSource(fs.readFileSync(filename, 'utf8'), filename)
}
aliases['@/utils/is'] = load('utils/is.ts')
aliases['@/utils/layout'] = load('utils/layout.ts')
aliases['@/utils/routeParams'] = load('utils/routeParams.ts')
aliases['@/utils/tree'] = load('utils/tree.ts')
aliases['lodash-es'] = { cloneDeep: structuredClone }
// routerHelper imports view modules through import.meta.glob; load its actual path helper alone.
const routerSource = fs.readFileSync(path.join(__dirname, '../src/utils/routerHelper.ts'), 'utf8')
const pathSource = routerSource.match(/export const pathResolve = [\s\S]*?(?=\n\/\/ 路由降级)/)[0]
aliases['@/utils/routerHelper'] = loadSource(
  "import { isUrl } from '@/utils/is'\n" + pathSource,
  'routerHelper.pathResolve.ts'
)
aliases['@/layout/components/Menu/src/helper'] = load('layout/components/Menu/src/helper.ts')
const { pathResolve } = aliases['@/utils/routerHelper']
const mesNavigation = load('utils/mesNavigation.ts')
const { applyMesDeliveryPolicy, buildMesNavigation, collectNavigationPages, getMesLandingPath } =
  mesNavigation
const { findRouteByPath, getRootMenuRoute, getRootMenuActivePath, getMenuBreadcrumb } = load(
  'layout/components/Menu/src/menuRoute.ts'
)
const { filterBreadcrumb } = load('layout/components/Breadcrumb/src/helper.ts')
const { filterAffixTags } = load('layout/components/TagsView/src/helper.ts')
const { initTabMap, filterMenusPath, tabPathMap } = load('layout/components/TabMenu/src/helper.ts')
const plain = (value) => JSON.parse(JSON.stringify(value))
const leaf = (routePath, title, meta = {}) => ({
  path: routePath,
  name: title,
  component: title + 'Component',
  meta: { title, ...meta }
})
const folder = (routePath, title, children, meta = {}) => ({
  path: routePath,
  name: title + 'Group',
  meta: { title, alwaysShow: true, ...meta },
  children
})
function fixture() {
  return [
    folder('/', '首页', [leaf('index', '工作台', { affix: true })], { alwaysShow: false }),
    folder('/mes', 'MES 系统', [
      { ...leaf('home/index', 'MES 首页'), name: 'MesHome' },
      folder('pro', '生产管理', [
        leaf('workorder', '生产工单'),
        leaf('task', '排产'),
        leaf('process', '工序'),
        leaf('route', '工艺路线')
      ]),
      folder('qc', '质量管理', [leaf('iqc', '来料检验')]),
      folder('wm', '仓库管理', [
        leaf('warehouse', '仓库'),
        folder('stocktaking', '库存盘点', [leaf('plan', '盘点方案'), leaf('task', '盘点任务')])
      ]),
      folder('dv', '设备管理', [leaf('device', '设备台账')]),
      folder('tm', '工装夹具', [leaf('tool', '工装台账')]),
      folder('md', '基础资料', [leaf('item', '物料')]),
      folder('cal', '班组排班', [leaf('team', '班组')]),
      leaf('workbench', '工位工作台'),
      leaf('quality-workbench', '现场质检'),
      leaf('kitting', '齐套分析'),
      leaf('sntrace', '单件追溯'),
      leaf('controlled-sop', '受控 SOP'),
      leaf('report', '报表设计'),
      leaf('dashboard', '生产看板'),
      leaf('portal-admin', '客户门户管理'),
      leaf('ai', 'AI 与知识库'),
      leaf('face', '本人人脸登录'),
      folder('experimental', '隐藏目录', [leaf('/mes/experimental-child', '隐藏后代')], {
        hidden: true
      }),
      leaf('/mes/custom', '客户扩展', { query: { mode: 'factory' }, hash: '#overview' })
    ]),
    folder('/system', '系统管理', [
      ...['user', 'role', 'dept', 'post', 'dict', 'menu', 'tenant', 'oauth2'].map((name) =>
        leaf(name, name)
      ),
      folder('log', '日志', [leaf('operate-log', '操作日志'), leaf('login-log', '登录日志')]),
      folder('messages', '消息', [leaf('notice', '公告'), leaf('sms-channel', '短信渠道')])
    ]),
    folder('/infra', '基础设施', [leaf('file', '文件'), leaf('job', '任务')])
  ]
}
function delivery() {
  const routes = fixture()
  applyMesDeliveryPolicy(routes)
  return { routes, navigation: buildMesNavigation(routes) }
}
const pagePaths = (routes) => plain(collectNavigationPages(routes).map((page) => page.path)).sort()

test('delivery policy keeps canonical pages addressable while filtering technical navigation', () => {
  const { routes, navigation } = delivery()
  const oldHome = findRouteByPath(routes, '/mes/home/index').route
  assert.equal(oldHome.name, 'MesHome')
  assert.equal(oldHome.component, 'MES 首页Component')
  assert.equal(oldHome.redirect, '/index')
  assert.equal(oldHome.meta.noTagsView, true)
  for (const hiddenPath of [
    '/mes/face',
    '/system/menu',
    '/system/tenant',
    '/system/oauth2',
    '/infra/file'
  ]) {
    assert.equal(findRouteByPath(routes, hiddenPath).route.meta.hidden, true)
    assert.equal(pagePaths(navigation).includes(hiddenPath), false)
  }
  for (const visiblePath of [
    '/system/user',
    '/system/role',
    '/system/log/operate-log',
    '/system/messages/notice'
  ]) {
    assert.equal(pagePaths(navigation).includes(visiblePath), true)
  }
  assert.equal(navigation.find((route) => route.path === '/system').meta.title, '系统设置')
})

test('projection neither loses nor invents any visible authorized page and preserves metadata', () => {
  const routes = fixture()
  applyMesDeliveryPolicy(routes)
  const before = JSON.stringify(routes)
  const navigation = buildMesNavigation(routes)
  assert.deepEqual(pagePaths(navigation), pagePaths(routes))
  assert.equal(JSON.stringify(routes), before)
  assert.equal(
    navigation.some((route) => route.path === '/mes'),
    false
  )
  assert.equal(new Set(pagePaths(navigation)).size, pagePaths(navigation).length)
  const custom = findRouteByPath(navigation, '/mes/custom').route
  assert.deepEqual(plain(custom.meta.query), { mode: 'factory' })
  assert.equal(custom.meta.hash, '#overview')
  assert.equal(custom.component, '客户扩展Component')
})

test('MES and settings pages have at most one display group and inventory is flattened', () => {
  const { navigation } = delivery()
  for (const route of navigation.filter(
    (route) => route.path.startsWith('/mes') || route.path === '/system'
  )) {
    assert.equal(
      (route.children || []).some((child) => child.children?.length),
      false
    )
  }
  const warehouse = navigation.find((route) => route.path === '/mes/wm')
  assert.equal(
    warehouse.children.some((route) => route.path === '/mes/wm/stocktaking/plan'),
    true
  )
  assert.equal(
    warehouse.children.some((route) => route.path === '/mes/wm/stocktaking/task'),
    true
  )
  assert.equal(findRouteByPath(navigation, '/mes/portal-admin').route.meta.title, '客户协同')
})

test('explicit descendants take priority over another root prefix in every menu layout', () => {
  const { navigation } = delivery()
  const cases = {
    '/mes/pro/workorder': '/mes/pro',
    '/mes/workbench': '/mes/pro',
    '/mes/cal/team': '/mes/pro',
    '/mes/quality-workbench': '/mes/qc',
    '/mes/sntrace': '/mes/qc',
    '/mes/tm/tool': '/mes/dv',
    '/mes/pro/process': '/mes/md',
    '/mes/pro/route': '/mes/md',
    '/mes/controlled-sop': '/mes/md',
    '/mes/report': '/mes/analytics',
    '/mes/dashboard': '/mes/analytics'
  }
  for (const [page, root] of Object.entries(cases)) {
    assert.equal(getRootMenuRoute(navigation, page).fullPath, root, page)
    assert.equal(getRootMenuActivePath(navigation, page), root, page)
  }
  assert.equal(getRootMenuRoute(navigation, '/mes/pro/process/detail/7').fullPath, '/mes/md')
  assert.equal(getRootMenuRoute(navigation, '/mes/tm/tool/detail/7').fullPath, '/mes/dv')
  assert.equal(getRootMenuRoute(navigation, '/mes/proces'), undefined)
})

test('absolute children, relative children, empty default pages and external URLs resolve correctly', () => {
  assert.equal(pathResolve('/mes/pro', '/mes/workbench'), '/mes/workbench')
  assert.equal(pathResolve('/mes/pro', 'workorder'), '/mes/pro/workorder')
  assert.equal(pathResolve('/mes/pro', ''), '/mes/pro')
  assert.equal(pathResolve('/redirect', '/redirect/:path(.*)'), '/redirect/:path(.*)')
  assert.equal(pathResolve('/mes/pro', 'https://example.com/a'), 'https://example.com/a')
  const { navigation } = delivery()
  assert.equal(findRouteByPath(navigation, '/mes/workbench').fullPath, '/mes/workbench')
  assert.equal(findRouteByPath(navigation, '/mes/pro/mes/workbench'), undefined)
})

test('limited roles only receive their authorized pages, including single-page groups', () => {
  const routes = [folder('/mes', 'MES 系统', [folder('pro', '生产', [leaf('process', '工序')])])]
  applyMesDeliveryPolicy(routes)
  const navigation = buildMesNavigation(routes)
  assert.deepEqual(pagePaths(navigation), ['/mes/pro/process'])
  assert.deepEqual(plain(navigation.map((route) => route.path)), ['/mes/md'])
  assert.equal(navigation[0].redirect, '/mes/pro/process')
  assert.equal(navigation[0].meta.alwaysShow, false)
  assert.equal(
    navigation.some((route) => route.path === '/system'),
    false
  )
  assert.equal(buildMesNavigation([]).length, 0)
})

test('a hidden ancestor hides absolute descendants from navigation and search', () => {
  const routes = [
    folder('/mes', 'MES 系统', [
      folder('hidden', '不可见', [leaf('/mes/pro/workorder', '未授权可见')], { hidden: true })
    ])
  ]
  assert.deepEqual(pagePaths(routes), [])
  assert.deepEqual(pagePaths(buildMesNavigation(routes)), [])
  routes[0].meta.hidden = true
  assert.deepEqual(pagePaths(buildMesNavigation(routes)), [])
})

test('detail breadcrumbs retain the new group and page before the detail title', () => {
  const { navigation } = delivery()
  const breadcrumbs = filterBreadcrumb(navigation)
  const chain = getMenuBreadcrumb(breadcrumbs, {
    path: '/mes/pro/process/detail/7',
    name: 'ProcessDetail',
    meta: { title: '工序详情', activeMenu: '/mes/pro/process' },
    matched: [{ path: '/mes/pro/process/detail/:id' }]
  })
  assert.deepEqual(plain(chain.map((route) => route.meta.title)), [
    '基础资料与工艺',
    '工序',
    '工序详情'
  ])
  assert.equal(chain[0].redirect, '/mes/pro/process')
  assert.equal(chain[2].path, '/mes/pro/process/detail/7')
  const reportChain = getMenuBreadcrumb(breadcrumbs, {
    path: '/mes/report',
    name: '报表设计',
    meta: { title: '报表设计' },
    matched: [{ path: '/mes/report' }]
  })
  assert.deepEqual(plain(reportChain.map((route) => route.meta.title)), ['报表与看板', '报表设计'])
  assert.equal(reportChain[0].redirect, '/mes/report')
})

test('split-menu path maps contain canonical cross-group URLs and reset on role change', () => {
  const { navigation } = delivery()
  initTabMap(navigation)
  filterMenusPath(navigation, navigation)
  assert.equal(tabPathMap['/mes/md'].includes('/mes/pro/process'), true)
  assert.equal(tabPathMap['/mes/dv'].includes('/mes/tm/tool'), true)
  assert.equal(tabPathMap['/mes/pro'].includes('/mes/workbench'), true)
  assert.equal(tabPathMap['/mes/pro'].includes('/mes/pro/mes/workbench'), false)
  initTabMap([])
  assert.deepEqual(Object.keys(tabPathMap), [])
})

test('delivery keeps a single affixed default workbench tag', () => {
  const { navigation } = delivery()
  assert.deepEqual(plain(filterAffixTags(navigation).map((tag) => tag.path)), ['/index'])
})

const roleNavigation = (...pages) =>
  buildMesNavigation([
    folder(
      '/mes',
      'MES 系统',
      pages.map((page) => leaf(page, page))
    )
  ])

test('operator landing uses the authorized production workbench', () => {
  const navigation = roleNavigation('workbench', 'pro/feedback')
  assert.equal(getMesLandingPath(navigation, new Set(['mes:pro:feedback:query'])), '/mes/workbench')
})

test('quality operator landing uses the authorized quality workbench', () => {
  const navigation = roleNavigation('quality-workbench', 'qc/iqc')
  assert.equal(
    getMesLandingPath(navigation, new Set(['mes:qc:iqc:query'])),
    '/mes/quality-workbench'
  )
})

test('roles with both field workbenches retain the unified workbench', () => {
  const navigation = roleNavigation('workbench', 'quality-workbench')
  assert.equal(getMesLandingPath(navigation, new Set()), undefined)
})

test('roles without a field workbench retain the unified workbench', () => {
  const navigation = roleNavigation('pro/workorder', 'qc/iqc', 'wm/stock')
  assert.equal(getMesLandingPath(navigation, new Set(['mes:pro:workorder:query'])), undefined)
})

test('home, wildcard and system administrators retain the unified workbench', () => {
  const navigation = roleNavigation('workbench')
  for (const permission of ['mes:home:query', '*:*:*']) {
    assert.equal(getMesLandingPath(navigation, new Set([permission])), undefined)
  }
  const settings = folder('/system', '系统设置', [leaf('/system/user', '用户')])
  assert.equal(getMesLandingPath([...navigation, settings], new Set()), undefined)
  settings.meta.hidden = true
  assert.equal(getMesLandingPath([...navigation, settings], new Set()), '/mes/workbench')
})

test('unknown, missing and hidden routes never create an unauthorized landing entry', () => {
  for (const navigation of [
    [],
    roleNavigation('unknown', 'workbench-extra'),
    [leaf('/mes/workbench', '隐藏现场', { hidden: true })],
    [folder('/mes/pro', '隐藏生产组', [leaf('/mes/workbench', '现场')], { hidden: true })]
  ]) {
    assert.equal(getMesLandingPath(navigation, new Set(['mes:workbench:query'])), undefined)
  }
})

function guardHarness(navigation, permissions = new Set()) {
  let guard
  const noOp = () => {}
  const user = {
    getIsSetUser: false,
    getPermissions: permissions,
    setUserInfoAction: async () => {}
  }
  const permission = { getRouters: navigation, getAddRouters: [], generateRoutes: async () => {} }
  const router = { beforeEach: (handler) => (guard = handler), afterEach: noOp, addRoute: noOp }
  const dependencies = {
    './router': router,
    '@/config/axios/service': { isRelogin: { show: false } },
    '@/utils/auth': { getAccessToken: () => 'test-token' },
    '@/hooks/web/useTitle': { useTitle: noOp },
    '@/hooks/web/useNProgress': { useNProgress: () => ({ start: noOp, done: noOp }) },
    '@/hooks/web/usePageLoading': { usePageLoading: () => ({ loadStart: noOp, loadDone: noOp }) },
    '@/store/modules/dict': { useDictStoreWithOut: () => ({ getIsSetDict: true }) },
    '@/store/modules/user': { useUserStoreWithOut: () => user },
    '@/store/modules/permission': { usePermissionStoreWithOut: () => permission },
    '@/utils/routeParams': aliases['@/utils/routeParams'],
    '@/utils/mesNavigation': mesNavigation
  }
  loadSource(
    fs.readFileSync(path.join(__dirname, '../src/permission.ts'), 'utf8'),
    'permission.ts',
    dependencies
  )
  return async (fullPath, redirect) => {
    const parsed = aliases['@/utils/routeParams'].parseRouteLocation(fullPath)
    let result
    await guard(
      { ...parsed, fullPath },
      { query: redirect === undefined ? {} : { redirect } },
      (target) => (result = target)
    )
    return plain(result)
  }
}

test('first-auth guard only applies role landing to the default entry', async () => {
  const navigate = guardHarness(roleNavigation('workbench'))
  for (const entry of ['/', '/index']) {
    assert.deepEqual(await navigate(entry), { path: '/mes/workbench', replace: true })
  }
  const deepLink = '/mes/pro/feedback?workorderId=42#records'
  assert.deepEqual(await navigate(deepLink), {
    path: '/mes/pro/feedback',
    query: { workorderId: '42' },
    hash: '#records',
    fullPath: deepLink,
    replace: true
  })
})

test('first-auth guard preserves explicit business redirect including its query and fragment', async () => {
  const navigate = guardHarness(roleNavigation('workbench'))
  assert.deepEqual(await navigate('/index', '/mes/wm/warehouse?warehouseId=7#locations'), {
    path: '/mes/wm/warehouse',
    query: { warehouseId: '7' },
    hash: '#locations',
    replace: true
  })
})

test('first-auth guard preserves explicit root redirects and parameterized default entries', async () => {
  const navigate = guardHarness(roleNavigation('workbench'))
  for (const redirect of ['/', '/index', '/index?view=overview#alerts']) {
    assert.deepEqual(await navigate('/mes/pro/task', redirect), {
      ...plain(aliases['@/utils/routeParams'].parseRouteLocation(redirect)),
      replace: true
    })
  }
  for (const entry of ['/index?view=overview', '/index#alerts', '/index?view=overview#alerts']) {
    assert.deepEqual(await navigate(entry), {
      ...plain(aliases['@/utils/routeParams'].parseRouteLocation(entry)),
      fullPath: entry,
      replace: true
    })
  }
})

test('planners with production workbench access retain the unified workbench', async () => {
  const navigation = roleNavigation('workbench', 'pro/workorder', 'pro/task')
  for (const permission of [
    'mes:pro-work-order:create',
    'mes:pro-task:create',
    'mes:pro-task:update'
  ]) {
    const permissions = new Set([permission])
    assert.equal(getMesLandingPath(navigation, permissions), undefined)
    const navigate = guardHarness(navigation, permissions)
    assert.deepEqual(await navigate('/index'), {
      path: '/index',
      fullPath: '/index',
      replace: true
    })
  }
})
