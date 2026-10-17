<template>
  <div class="mes-workspace">
    <el-card shadow="never">
      <div class="welcome">
        <div>
          <div class="eyebrow">FazeMES / 生产执行</div>
          <h1>{{ userStore.getUser.nickname }}，欢迎进入生产工作台</h1>
          <p>从基础资料到生产工单，串联排产、领料、报工、质量和入库。</p>
        </div>
        <el-tag effect="plain" size="large">本地适配底稿</el-tag>
      </div>
    </el-card>

    <el-row :gutter="16" class="statistics">
      <el-col v-for="stat in statistics" :key="stat.key" :xs="24" :sm="8">
        <el-card v-loading="loading" shadow="never">
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-value">{{ stat.value ?? '—' }}</div>
          <div class="stat-hint">{{ stat.hint }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="loadFailed"
      class="mb-16px"
      title="部分生产数据加载失败，请确认后端服务后点击刷新。"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-card shadow="never" class="mb-16px">
      <template #header>
        <div class="section-header">
          <span>生产业务入口</span>
          <span class="secondary">根据当前账号的菜单权限显示</span>
        </div>
      </template>
      <el-empty v-if="!moduleGroups.length" description="当前账号尚未分配 MES 菜单，请联系管理员" />
      <div v-else class="module-grid">
        <div v-for="group in moduleGroups" :key="group.path" class="module-group">
          <h2>
            <Icon :icon="group.icon || 'ep:folder-opened'" :size="20" />
            {{ group.title }}
          </h2>
          <div class="module-links">
            <el-link
              v-for="link in group.links"
              :key="link.path"
              type="primary"
              :underline="false"
              @click="router.push(link.path)"
            >
              {{ link.title }}
            </el-link>
          </div>
        </div>
      </div>
    </el-card>

    <el-card v-if="canQueryWorkOrders" shadow="never">
      <template #header>
        <div class="section-header">
          <span>最近生产工单</span>
          <el-button :loading="loading" link type="primary" @click="loadData">刷新数据</el-button>
        </div>
      </template>
      <el-table
        v-loading="loading"
        :data="workOrders"
        empty-text="暂无工单，请先维护物料、BOM 和工艺路线后创建工单"
      >
        <el-table-column prop="code" label="工单编码" min-width="160" />
        <el-table-column prop="name" label="工单名称" min-width="160" />
        <el-table-column prop="productName" label="生产产品" min-width="140" />
        <el-table-column prop="quantity" label="计划数量" width="110" align="right" />
        <el-table-column prop="quantityProduced" label="已生产" width="110" align="right" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="scope">
            <dict-tag :type="DICT_TYPE.MES_PRO_WORK_ORDER_STATUS" :value="scope.row.status" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <p class="attribution"> FazeMES · 所有统计来自当前企业数据库 </p>
  </div>
</template>

<script setup lang="ts">
import { MdItemApi } from '@/api/mes/md/item'
import { ProFeedbackApi } from '@/api/mes/pro/feedback'
import { ProWorkOrderApi, type ProWorkOrderVO } from '@/api/mes/pro/workorder'
import { usePermissionStore } from '@/store/modules/permission'
import { useUserStore } from '@/store/modules/user'
import { checkPermi } from '@/utils/permission'
import { DICT_TYPE } from '@/utils/dict'

defineOptions({ name: 'Index' })

const router = useRouter()
const userStore = useUserStore()
const permissionStore = usePermissionStore()
const loading = ref(false)
const loadFailed = ref(false)
const workOrders = ref<ProWorkOrderVO[]>([])
const canQueryWorkOrders = computed(() => checkPermi(['mes:pro-work-order:query']))

const statistics = reactive([
  {
    key: 'item',
    label: '物料 / 产品',
    value: undefined as number | undefined,
    hint: '已维护的物料与产品总数'
  },
  {
    key: 'workOrder',
    label: '生产工单',
    value: undefined as number | undefined,
    hint: '当前租户的工单总数'
  },
  {
    key: 'feedback',
    label: '生产报工',
    value: undefined as number | undefined,
    hint: '当前租户的报工单总数'
  }
])

interface MenuLink {
  title: string
  path: string
}

const resolvePath = (parent: string, path: string) =>
  path.startsWith('/') ? path : `${parent}/${path}`.replace(/\/{2,}/g, '/')

const collectLinks = (routes: AppRouteRecordRaw[], parent: string): MenuLink[] =>
  routes.flatMap((route) => {
    if (route.meta?.hidden) return []
    const path = resolvePath(parent, route.path)
    if (route.children?.length) return collectLinks(route.children, path)
    return [{ title: route.meta?.title || route.name, path }]
  })

const moduleGroups = computed(() => {
  // remainingRouter 中还存在一个隐藏的 /mes 详情页目录，入口应使用后端授权的可见菜单根。
  const mesRoot = permissionStore.getRouters.find(
    (route) => route.path === '/mes' && !route.meta?.hidden
  )
  if (!mesRoot) return []
  return (mesRoot.children || [])
    .filter((route) => !route.meta?.hidden)
    .map((route) => {
      const path = resolvePath('/mes', route.path)
      return {
        title: route.meta?.title || route.name,
        icon: route.meta?.icon,
        path,
        links: route.children?.length
          ? collectLinks(route.children, path)
          : [{ title: route.meta?.title || route.name, path }]
      }
    })
})

const loadData = async () => {
  if (loading.value) return
  loading.value = true
  loadFailed.value = false
  const requests = [
    {
      permission: 'mes:md-item:query',
      load: () => MdItemApi.getItemPage({ pageNo: 1, pageSize: 1 })
    },
    {
      permission: 'mes:pro-work-order:query',
      load: () => ProWorkOrderApi.getWorkOrderPage({ pageNo: 1, pageSize: 5 })
    },
    {
      permission: 'mes:pro-feedback:query',
      load: () => ProFeedbackApi.getFeedbackPage({ pageNo: 1, pageSize: 1 })
    }
  ]
  try {
    const results = await Promise.allSettled(
      requests.map((request) =>
        checkPermi([request.permission]) ? request.load() : Promise.resolve(null)
      )
    )
    results.forEach((result, index) => {
      const stat = statistics[index]
      stat.value = result.status === 'fulfilled' && result.value ? result.value.total : undefined
      if (result.status === 'rejected') loadFailed.value = true
      if (!checkPermi([requests[index].permission])) stat.hint = '当前账号无查询权限'
    })
    const result = results[1]
    workOrders.value = result.status === 'fulfilled' && result.value ? result.value.list : []
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped lang="scss">
.welcome,
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  font-size: 13px;
  letter-spacing: 1px;
  color: var(--el-color-primary);
}

h1 {
  margin: 12px 0;
  font-size: 24px;
  font-weight: 600;
}

.welcome p,
.secondary,
.stat-hint,
.attribution {
  color: var(--el-text-color-secondary);
}

.welcome p {
  margin: 0;
  line-height: 1.7;
}

.statistics {
  margin-top: 16px;
}

.statistics .el-col {
  margin-bottom: 16px;
}

.stat-label {
  font-size: 14px;
}

.stat-value {
  margin: 12px 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-hint,
.secondary,
.attribution {
  font-size: 12px;
}

.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 20px;
}

.module-group {
  padding: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.module-group h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  font-size: 16px;
}

.module-links {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
}

.attribution {
  margin: 20px 0 8px;
  text-align: center;
}

@media (width <= 767px) {
  .welcome {
    align-items: flex-start;
    flex-direction: column;
  }

  h1 {
    font-size: 20px;
  }
}
</style>
