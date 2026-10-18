import { isUrl } from '@/utils/is'
import { getRootPath } from '@/utils/layout'
import { resolveDynamicPath } from '@/utils/routeParams'
import { pathResolve } from '@/utils/routerHelper'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

export const normalizeMenuTargetPath = (targetPath: string): string => {
  if (!targetPath || isUrl(targetPath)) {
    return targetPath
  }
  return targetPath.startsWith('/') ? targetPath : pathResolve('/', targetPath)
}

export const findRouteByPath = (
  routes: AppRouteRecordRaw[],
  targetPath: string,
  parentPath = '/',
  preferChild = true
): { fullPath: string; route: AppRouteRecordRaw } | undefined => {
  for (const route of routes) {
    const fullPath = isUrl(route.path) ? route.path : pathResolve(parentPath, route.path)
    const resolvedFullPath = resolveDynamicPath(fullPath, route.meta?.params as Record<string, any>)
    if (fullPath === targetPath || resolvedFullPath === targetPath) {
      const child = preferChild
        ? findRouteByPath(route.children || [], targetPath, fullPath, preferChild)
        : undefined
      if (child) {
        return child
      }
      return { fullPath: resolvedFullPath, route }
    }
    const child = route.children?.length
      ? findRouteByPath(route.children, targetPath, fullPath, preferChild)
      : undefined
    if (child) {
      return child
    }
  }
}

export const getRootMenuRoute = (
  routes: AppRouteRecordRaw[],
  targetPath: string
): { fullPath: string; route: AppRouteRecordRaw } | undefined => {
  const normalizedPath = normalizeMenuTargetPath(targetPath)
  const roots = routes
    .filter((route) => !route.meta?.hidden)
    .map((route) => {
      const fullPath = isUrl(route.path) ? route.path : pathResolve('/', route.path)
      const resolvedFullPath = resolveDynamicPath(
        fullPath,
        route.meta?.params as Record<string, any>
      )
      return { fullPath: resolvedFullPath, route }
    })
  // 展示分组的地址不一定是后代页面的前缀，例如工序仍用 /mes/pro/process，归属基础资料。
  // 先查整棵展示树的明确归属，避免提前命中生产管理的 /mes/pro 前缀。
  const exact =
    roots.find((root) =>
      findRouteByPath(root.route.children || [], normalizedPath, root.fullPath)
    ) || roots.find((root) => root.fullPath === normalizedPath)
  if (exact) return exact

  const getPrefixLength = (route: AppRouteRecordRaw, parentPath = '/'): number => {
    if (route.meta?.hidden) return -1
    const fullPath = pathResolve(parentPath, route.path)
    const resolvedPath = resolveDynamicPath(fullPath, route.meta?.params as Record<string, any>)
    const selfLength =
      resolvedPath !== '/' && normalizedPath.startsWith(`${resolvedPath}/`)
        ? resolvedPath.length
        : -1
    return Math.max(
      selfLength,
      ...(route.children || []).map((child) => getPrefixLength(child, fullPath))
    )
  }
  let closest: (typeof roots)[number] | undefined
  let closestLength = -1
  for (const root of roots) {
    const prefixLength = getPrefixLength(root.route)
    if (prefixLength > closestLength) {
      closest = root
      closestLength = prefixLength
    }
  }
  return closest
}

export const getRootMenuActivePath = (routes: AppRouteRecordRaw[], targetPath: string): string => {
  return (
    getRootMenuRoute(routes, targetPath)?.fullPath ||
    getRootPath(normalizeMenuTargetPath(targetPath))
  )
}

/** 详情页通过 activeMenu 复用展示分组，再追加自身标题。 */
export const getMenuBreadcrumb = (
  routes: AppRouteRecordRaw[],
  current: Pick<RouteLocationNormalizedLoaded, 'path' | 'name' | 'meta' | 'matched'>
): AppRouteRecordRaw[] => {
  const findChain = (
    items: AppRouteRecordRaw[],
    targetPath: string,
    parentPath = ''
  ): AppRouteRecordRaw[] => {
    for (const item of items) {
      const fullPath = pathResolve(parentPath, item.path)
      const childChain = findChain(item.children || [], targetPath, fullPath)
      const resolvedPath = resolveDynamicPath(fullPath, item.meta?.params as Record<string, any>)
      if (childChain.length || fullPath === targetPath || resolvedPath === targetPath) {
        return [{ ...item, path: fullPath }, ...childChain]
      }
    }
    return []
  }
  const matchedPath = current.matched.at(-1)?.path || current.path
  let chain = findChain(routes, matchedPath)
  if (!chain.length && matchedPath !== current.path) chain = findChain(routes, current.path)
  if (!chain.length && current.meta.activeMenu) {
    chain = findChain(routes, normalizeMenuTargetPath(current.meta.activeMenu as string))
    if (chain.length && current.meta.title && chain.at(-1)?.meta?.title !== current.meta.title) {
      chain.push({
        path: current.path,
        name: current.name,
        meta: { ...current.meta }
      } as AppRouteRecordRaw)
    }
  }
  return chain.filter((item) => item.meta?.breadcrumb !== false)
}
