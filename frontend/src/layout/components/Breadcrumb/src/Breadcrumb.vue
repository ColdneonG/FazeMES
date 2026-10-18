<script lang="tsx">
import { ElBreadcrumb, ElBreadcrumbItem } from 'element-plus'
import { ref, watch, computed, unref, defineComponent, TransitionGroup } from 'vue'
import { useRouter } from 'vue-router'
import { usePermissionStore } from '@/store/modules/permission'
import { filterBreadcrumb } from './helper'
import { getMenuBreadcrumb } from '@/layout/components/Menu/src/menuRoute'
import type { RouteLocationNormalizedLoaded, RouteMeta } from 'vue-router'

import { Icon } from '@/components/Icon'
import { useAppStore } from '@/store/modules/app'
import { useDesign } from '@/hooks/web/useDesign'

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('breadcrumb')

const appStore = useAppStore()

// 面包屑图标
const breadcrumbIcon = computed(() => appStore.getBreadcrumbIcon)

export default defineComponent({
  name: 'Breadcrumb',
  setup() {
    const { currentRoute } = useRouter()

    const { t } = useI18n()

    const levelList = ref<AppRouteRecordRaw[]>([])

    const permissionStore = usePermissionStore()

    const menuRouters = computed(() => {
      const routers = permissionStore.getRouters
      return filterBreadcrumb(routers)
    })

    const getBreadcrumb = () => {
      levelList.value = getMenuBreadcrumb(unref(menuRouters), currentRoute.value)
    }

    const renderBreadcrumb = () => {
      return unref(levelList).map((v, index) => {
        const redirect =
          typeof v.redirect === 'string' && v.redirect !== 'noredirect' ? v.redirect : undefined
        // 分组只是展示投影，其 path 可能没有真实路由；点击父级应进入分组默认页。
        const target =
          index < unref(levelList).length - 1 ? { path: redirect || v.path } : undefined
        const meta = v.meta as RouteMeta
        return (
          <ElBreadcrumbItem to={v.redirect === 'noredirect' ? undefined : target} key={v.name}>
            {meta?.icon && breadcrumbIcon.value ? (
              <div class="flex items-center">
                <Icon icon={meta.icon} class="mr-[2px]" svgClass="inline-block"></Icon>
                {t(v?.meta?.title)}
              </div>
            ) : (
              t(v?.meta?.title)
            )}
          </ElBreadcrumbItem>
        )
      })
    }

    watch(
      () => [currentRoute.value, unref(menuRouters)] as const,
      ([route]: readonly [RouteLocationNormalizedLoaded, AppRouteRecordRaw[]]) => {
        if (route.path.startsWith('/redirect/')) {
          return
        }
        getBreadcrumb()
      },
      {
        immediate: true
      }
    )

    return () => (
      <ElBreadcrumb separator="/" class={`${prefixCls} flex items-center h-full ml-[10px]`}>
        <TransitionGroup appear enter-active-class="animate__animated animate__fadeInRight">
          {renderBreadcrumb()}
        </TransitionGroup>
      </ElBreadcrumb>
    )
  }
})
</script>

<style lang="scss" scoped>
$prefix-cls: #{$elNamespace}-breadcrumb;

.#{$prefix-cls} {
  :deep(.#{$prefix-cls}__item) {
    display: flex;
    .#{$prefix-cls}__inner {
      display: flex;
      align-items: center;
      color: var(--top-header-text-color);

      &:hover {
        color: var(--el-color-primary);
      }
    }
  }

  :deep(.#{$prefix-cls}__item):not(:last-child) {
    .#{$prefix-cls}__inner {
      color: var(--top-header-text-color);

      &:hover {
        color: var(--el-color-primary);
      }
    }
  }

  :deep(.#{$prefix-cls}__item):last-child {
    .#{$prefix-cls}__inner {
      display: flex;
      align-items: center;
      color: var(--el-text-color-placeholder);

      &:hover {
        color: var(--el-text-color-placeholder);
      }
    }
  }
}
</style>
