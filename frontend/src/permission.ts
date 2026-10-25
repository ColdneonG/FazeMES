import router from './router'
import type { RouteRecordRaw } from 'vue-router'
import { isRelogin } from '@/config/axios/service'
import { getAccessToken, getTenantId } from '@/utils/auth'
import { ensureFileMediaSession } from '@/utils/fileMediaSession'
import { useTitle } from '@/hooks/web/useTitle'
import { useNProgress } from '@/hooks/web/useNProgress'
import { usePageLoading } from '@/hooks/web/usePageLoading'
import { useDictStoreWithOut } from '@/store/modules/dict'
import { useUserStoreWithOut } from '@/store/modules/user'
import { usePermissionStoreWithOut } from '@/store/modules/permission'
import { parseRouteLocation } from '@/utils/routeParams'
import { getMesLandingPath } from '@/utils/mesNavigation'

const { start, done } = useNProgress()

const { loadStart, loadDone } = usePageLoading()

// 路由不重定向白名单
const whiteList = [
  '/login',
  '/social-login',
  '/auth-redirect',
  '/bind',
  '/register',
  '/oauthLogin/gitee',
  '/pms/kb/document/share'
]

// 路由加载前
router.beforeEach(async (to, from, next) => {
  start()
  loadStart()
  // The client portal owns its session and never inherits the administrator token or menu.
  if (to.path === '/customer-portal' || to.path.startsWith('/customer-portal/')) {
    next()
    return
  }
  if (getAccessToken()) {
    // Establish browser media authentication before profile/SOP images are mounted after a reload.
    await ensureFileMediaSession(getAccessToken(), getTenantId()).catch(() => undefined)
    if (to.path === '/login') {
      next({ path: '/' })
    } else {
      const dictStore = useDictStoreWithOut()
      const userStore = useUserStoreWithOut()
      const permissionStore = usePermissionStoreWithOut()
      // 异步加载字典
      // 另外，间接 issue：https://gitee.com/yudaocode/yudao-ui-admin-vue3/issues/ID9FLI
      if (!dictStore.getIsSetDict) {
        dictStore.setDictMap().then()
      }
      if (!userStore.getIsSetUser) {
        isRelogin.show = true
        await userStore.setUserInfoAction()
        isRelogin.show = false
        // 后端过滤菜单
        await permissionStore.generateRoutes()
        permissionStore.getAddRouters.forEach((route) => {
          router.addRoute(route as unknown as RouteRecordRaw) // 动态添加可访问路由表
        })
        const redirectPath = from.query.redirect
        // 修复跳转时不带参数的问题
        const redirect = typeof redirectPath === 'string' ? redirectPath : to.fullPath
        const redirectLocation = parseRouteLocation(redirect)
        const landingPath =
          typeof redirectPath !== 'string' &&
          !redirectLocation.query &&
          !redirectLocation.hash &&
          ['/', '/index'].includes(redirectLocation.path)
            ? getMesLandingPath(permissionStore.getRouters, userStore.getPermissions)
            : undefined
        const nextData = landingPath
          ? { path: landingPath, replace: true }
          : to.fullPath === redirect
            ? { ...to, replace: true }
            : { ...redirectLocation, replace: true }
        next(nextData)
      } else {
        next()
      }
    }
  } else {
    if (whiteList.some((path) => to.path === path || to.path.startsWith(`${path}/`))) {
      next()
    } else {
      next(`/login?redirect=${encodeURIComponent(to.fullPath)}`) // 否则全部重定向到登录页
    }
  }
})

router.afterEach((to) => {
  useTitle(to?.meta?.title as string)
  done() // 结束Progress
  loadDone()
})
