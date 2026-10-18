// 导航是授权路由的展示投影；调整分组时不改变页面地址、路由名称或权限。
const resolvePath = (parent: string, path: string): string => {
  if (/^https?:\/\//.test(path)) return path
  return (path.startsWith('/') ? path : `${parent}/${path}`).replace(/\/{2,}/g, '/')
}

const isWithin = (path: string, prefix: string): boolean =>
  path === prefix || path.startsWith(`${prefix}/`)

const systemPages = [
  '/system/user',
  '/system/role',
  '/system/dept',
  '/system/post',
  '/system/dict',
  '/system/log/operate-log',
  '/system/log/login-log',
  '/system/messages/notice'
]

/** 只隐藏交付界面中的维护入口，旧地址及后台服务继续保留。 */
export const applyMesDeliveryPolicy = (
  routes: AppRouteRecordRaw[],
  parent = '',
  parentHidden = false
): void => {
  for (const route of routes) {
    const path = resolvePath(parent, route.path)
    const isSystem = isWithin(path, '/system')
    const systemVisible = systemPages.some((page) => isWithin(page, path) || isWithin(path, page))
    const oldHome = route.name === 'MesHome'
    const hidden =
      parentHidden ||
      isWithin(path, '/infra') ||
      path === '/mes/face' ||
      oldHome ||
      (isSystem && !systemVisible)
    if (hidden) {
      route.meta = { ...route.meta, hidden: true }
    }
    if (path === '/system') route.meta = { ...route.meta, title: '系统设置' }
    if (oldHome) {
      route.redirect = '/index'
      route.meta = { ...route.meta, noTagsView: true }
    }
    if (route.children) applyMesDeliveryPolicy(route.children, path, hidden)
  }
}

/** 返回可见页面的绝对地址，供分组、工作台和菜单搜索共同使用。 */
export const collectNavigationPages = (
  routes: AppRouteRecordRaw[],
  parent = ''
): AppRouteRecordRaw[] =>
  routes.flatMap((route) => {
    if (route.meta?.hidden) return []
    const path = resolvePath(parent, route.path)
    if (route.children?.length) return collectNavigationPages(route.children, path)
    return [{ ...route, path, meta: { ...route.meta }, children: undefined }]
  })

interface NavigationGroup {
  path: string
  name: string
  title: string
  icon: string
  matches: (path: string) => boolean
}

const groups: NavigationGroup[] = [
  {
    path: '/mes/pro',
    name: 'MesNavigationProduction',
    title: '生产管理',
    icon: 'ep:management',
    matches: (path) =>
      (isWithin(path, '/mes/pro') &&
        !isWithin(path, '/mes/pro/process') &&
        !isWithin(path, '/mes/pro/route')) ||
      ['/mes/workbench', '/mes/kitting'].includes(path) ||
      isWithin(path, '/mes/cal')
  },
  {
    path: '/mes/qc',
    name: 'MesNavigationQuality',
    title: '质量与追溯',
    icon: 'ep:circle-check',
    matches: (path) =>
      isWithin(path, '/mes/qc') || ['/mes/quality-workbench', '/mes/sntrace'].includes(path)
  },
  {
    path: '/mes/wm',
    name: 'MesNavigationWarehouse',
    title: '仓库管理',
    icon: 'ep:box',
    matches: (path) => isWithin(path, '/mes/wm')
  },
  {
    path: '/mes/dv',
    name: 'MesNavigationEquipment',
    title: '设备与工装',
    icon: 'ep:cpu',
    matches: (path) => isWithin(path, '/mes/dv') || isWithin(path, '/mes/tm')
  },
  {
    path: '/mes/md',
    name: 'MesNavigationMasterData',
    title: '基础资料与工艺',
    icon: 'ep:data-analysis',
    matches: (path) =>
      isWithin(path, '/mes/md') ||
      isWithin(path, '/mes/pro/process') ||
      isWithin(path, '/mes/pro/route') ||
      path === '/mes/controlled-sop'
  },
  {
    path: '/mes/analytics',
    name: 'MesNavigationAnalytics',
    title: '报表与看板',
    icon: 'ep:data-board',
    matches: (path) => ['/mes/report', '/mes/dashboard'].some((prefix) => isWithin(path, prefix))
  }
]

export const buildMesNavigation = (routes: AppRouteRecordRaw[]): AppRouteRecordRaw[] => {
  const mesRoot = routes.find((route) => route.path === '/mes' && !route.meta?.hidden)
  const pages = mesRoot ? collectNavigationPages(mesRoot.children || [], '/mes') : []
  const used = new Set<string>()
  const navigation: AppRouteRecordRaw[] = []
  for (const group of groups) {
    const children = pages.filter((page) => group.matches(page.path))
    if (!children.length) continue
    children.forEach((page) => used.add(page.path))
    navigation.push({
      path: group.path,
      name: group.name,
      redirect: children[0].path,
      meta: { title: group.title, icon: group.icon, alwaysShow: false },
      children
    })
  }
  // 单页功能直接进入页面，不再增加只有一个页面的空目录。
  for (const page of pages.filter((page) => !used.has(page.path))) {
    if (page.path === '/mes/portal-admin') page.meta.title = '客户协同'
    navigation.push(page)
  }
  const settingsRoot = routes.find((route) => route.path === '/system' && !route.meta?.hidden)
  const settings = settingsRoot ? collectNavigationPages([settingsRoot]) : []
  if (settings.length) {
    navigation.push({
      ...settingsRoot!,
      meta: { ...settingsRoot!.meta, title: '系统设置', alwaysShow: false },
      redirect: settings[0].path,
      children: settings
    })
  }
  return [
    ...routes.filter((route) => !['/mes', '/system', '/infra'].includes(route.path)),
    ...navigation
  ]
}

/** 现场岗位首次进入系统时直达自己的工作台，管理员仍进入统一工作台。 */
export const getMesLandingPath = (
  routes: AppRouteRecordRaw[],
  permissions: ReadonlySet<string>
): string | undefined => {
  if (
    permissions.has('*:*:*') ||
    permissions.has('mes:home:query') ||
    ['mes:pro-work-order:create', 'mes:pro-task:create', 'mes:pro-task:update'].some((permission) =>
      permissions.has(permission)
    ) ||
    routes.some((route) => route.path === '/system' && !route.meta?.hidden)
  )
    return undefined
  const pages = collectNavigationPages(routes)
  const production = pages.some((page) => page.path === '/mes/workbench')
  const quality = pages.some((page) => page.path === '/mes/quality-workbench')
  if (production && !quality) return '/mes/workbench'
  if (quality && !production) return '/mes/quality-workbench'
  return undefined
}
