<template>
  <div class="mes-workspace">
    <el-card shadow="never" class="mb-16px">
      <div class="welcome">
        <div>
          <div class="eyebrow">FazeMES / 生产执行</div>
          <h1>{{ userStore.getUser.nickname }}，欢迎进入工作台</h1>
          <p>查看生产动态、处理岗位待办，快速进入日常业务。</p>
        </div>
        <div>
          <el-button
            v-if="checkPermi(['mes:insight:query'])"
            type="primary"
            @click="router.push('/mes/insight')"
            >进入生产决策中心</el-button
          >
          <el-button v-if="canLoadData" :loading="loading" @click="loadData">刷新数据</el-button>
        </div>
      </div>
    </el-card>

    <HomeShortcuts v-if="shortcuts.length" :items="shortcuts" @navigate="navigate" />

    <el-row v-if="statistics.length" :gutter="16" class="statistics">
      <el-col v-for="stat in statistics" :key="stat.key" :xs="24" :sm="8">
        <el-card v-loading="loading" shadow="never">
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-value">{{ counts[stat.key] ?? '—' }}</div>
          <div class="stat-hint">{{ stat.hint }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="loadFailed"
      class="mb-16px"
      title="部分数据加载失败，请点击刷新数据重试。"
      type="warning"
      show-icon
      :closable="false"
    />

    <template v-if="canQueryHomeStatistics">
      <HomeKpiCards
        v-if="summary"
        :summary="summary"
        :destinations="destinations"
        @navigate="navigate"
      />
      <el-skeleton v-else-if="loading" :rows="3" animated class="mb-16px" />
      <el-row :gutter="16">
        <el-col :lg="summary && hasAlerts ? 16 : 24" :xs="24" class="mb-16px">
          <HomeProductionTrend v-if="refreshToken > 0" :refresh-token="refreshToken" />
        </el-col>
        <el-col v-if="summary && hasAlerts" :lg="8" :xs="24" class="mb-16px">
          <HomeAlertPanel :summary="summary" :destinations="destinations" @navigate="navigate" />
        </el-col>
      </el-row>
    </template>

    <el-row v-if="canQueryWorkOrders || canQueryHomeStatistics" :gutter="16">
      <el-col
        v-if="canQueryWorkOrders"
        :lg="canQueryHomeStatistics ? 16 : 24"
        :xs="24"
        class="mb-16px"
      >
        <el-card shadow="never">
          <template #header>
            <div class="section-header">
              <span>最近生产工单</span>
              <el-link
                v-if="destinations.MesProWorkOrder"
                type="primary"
                :underline="false"
                @click="navigate(destinations.MesProWorkOrder)"
                >全部工单</el-link
              >
            </div>
          </template>
          <el-table v-loading="loading" :data="workOrders" empty-text="暂无生产工单">
            <el-table-column prop="code" label="工单编码" min-width="150" />
            <el-table-column prop="name" label="工单名称" min-width="150" />
            <el-table-column prop="productName" label="生产产品" min-width="130" />
            <el-table-column prop="quantity" label="计划数量" width="100" align="right" />
            <el-table-column prop="quantityProduced" label="已生产" width="100" align="right" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="scope">
                <dict-tag :type="DICT_TYPE.MES_PRO_WORK_ORDER_STATUS" :value="scope.row.status" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col
        v-if="canQueryHomeStatistics"
        :lg="canQueryWorkOrders ? 8 : 24"
        :xs="24"
        class="mb-16px"
      >
        <HomeWorkOrderChart v-if="refreshToken > 0" :refresh-token="refreshToken" />
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb-16px">
      <template #header>
        <div class="section-header">
          <span>全部业务</span>
          <span class="secondary">根据当前账号的菜单权限显示</span>
        </div>
      </template>
      <el-empty v-if="!moduleGroups.length" description="当前账号尚未分配业务菜单，请联系管理员" />
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
              @click="navigate(link.path)"
            >
              {{ link.title }}
            </el-link>
          </div>
        </div>
      </div>
    </el-card>
    <p class="attribution">FazeMES · 所有统计来自当前企业数据库</p>
  </div>
</template>

<script setup lang="ts">
import { MdItemApi } from '@/api/mes/md/item'
import { MesHomeStatisticsApi, type MesHomeSummaryVO } from '@/api/mes/home'
import { ProFeedbackApi } from '@/api/mes/pro/feedback'
import { ProWorkOrderApi, type ProWorkOrderVO } from '@/api/mes/pro/workorder'
import { usePermissionStore } from '@/store/modules/permission'
import { useUserStore } from '@/store/modules/user'
import { checkPermi } from '@/utils/permission'
import { DICT_TYPE } from '@/utils/dict'
import { collectNavigationPages } from '@/utils/mesNavigation'
import HomeKpiCards from '@/views/mes/home/HomeKpiCards.vue'
import HomeAlertPanel from '@/views/mes/home/HomeAlertPanel.vue'
import HomeProductionTrend from '@/views/mes/home/HomeProductionTrend.vue'
import HomeWorkOrderChart from '@/views/mes/home/HomeWorkOrderChart.vue'
import HomeShortcuts from '@/views/mes/home/HomeShortcuts.vue'

defineOptions({ name: 'Index' })

const router = useRouter()
const userStore = useUserStore()
const permissionStore = usePermissionStore()
const loading = ref(false)
const loadFailed = ref(false)
const refreshToken = ref(0)
const summary = ref<MesHomeSummaryVO>()
const workOrders = ref<ProWorkOrderVO[]>([])
const canQueryWorkOrders = computed(() => checkPermi(['mes:pro-work-order:query']))
// 三个首页统计接口使用同一权限，缺少该权限时不挂载图表或请求汇总。
const canQueryHomeStatistics = computed(() => checkPermi(['mes:home:query']))

const statisticDefinitions = [
  {
    key: 'item',
    label: '物料 / 产品',
    hint: '已维护的物料与产品总数',
    permission: 'mes:md-item:query'
  },
  {
    key: 'workOrder',
    label: '生产工单',
    hint: '当前企业的工单总数',
    permission: 'mes:pro-work-order:query'
  },
  {
    key: 'feedback',
    label: '生产报工',
    hint: '当前企业的报工单总数',
    permission: 'mes:pro-feedback:query'
  }
]
const counts = reactive<Record<string, number | undefined>>({})
const statistics = computed(() =>
  statisticDefinitions.filter((stat) => checkPermi([stat.permission]))
)
const canLoadData = computed(() => statistics.value.length > 0 || canQueryHomeStatistics.value)

const moduleGroups = computed(() =>
  permissionStore.getRouters
    .filter((route) => !route.meta?.hidden && route.path.startsWith('/mes/'))
    .map((route) => ({
      title: String(route.meta?.title || route.name || ''),
      icon: route.meta?.icon,
      path: route.path,
      links: collectNavigationPages([route])
        .filter((page) => page.path !== '/index')
        .map((page) => ({ title: String(page.meta?.title || page.name || ''), path: page.path }))
    }))
    .filter((group) => group.links.length > 0)
)

const authorizedPages = computed(() => collectNavigationPages(permissionStore.getRouters))
const destinations = computed<Record<string, string>>(() =>
  Object.fromEntries(
    authorizedPages.value.filter((page) => page.name).map((page) => [String(page.name), page.path])
  )
)
const hasAlerts = computed(() =>
  ['MesProAndonRecord', 'MesDvRepair', 'MesProWorkOrder'].some((name) => destinations.value[name])
)

// 优先提供现场操作、质检、计划和仓储入口；每一项都来自当前账号的可见页面。
const shortcutPriority = [
  'MesProWorkbench',
  'MesQualityWorkbench',
  'MesProWorkOrder',
  'MesWmMaterialStock',
  'MesProFeedback',
  'MesQcPendingInspect',
  'MesDvMachinery',
  'MesProTask',
  'MesQcIqc',
  'MesDvRepair'
]
const shortcuts = computed(() => {
  const businessPages = authorizedPages.value.filter((page) => page.path.startsWith('/mes/'))
  const prioritized = shortcutPriority.flatMap((name) =>
    businessPages.filter((page) => page.name === name)
  )
  const selected = [
    ...prioritized,
    ...businessPages.filter((page) => !shortcutPriority.includes(String(page.name)))
  ]
  return selected.slice(0, 5).map((page) => ({
    name: String(page.meta?.title || page.name || ''),
    path: page.path,
    icon: page.meta?.icon || 'ep:right'
  }))
})

// 汇总卡片和快捷入口只接受展示树中已经授权的页面地址。
const navigate = (path: string) => {
  if (authorizedPages.value.some((page) => page.path === path)) router.push(path)
}

const loadData = async () => {
  if (loading.value || !canLoadData.value) return
  loading.value = true
  loadFailed.value = false
  refreshToken.value++
  const requests: Promise<void>[] = []
  if (checkPermi(['mes:md-item:query'])) {
    counts.item = undefined
    requests.push(
      MdItemApi.getItemPage({ pageNo: 1, pageSize: 1 }).then((page) => {
        counts.item = page.total
      })
    )
  }
  if (canQueryWorkOrders.value) {
    counts.workOrder = undefined
    workOrders.value = []
    requests.push(
      ProWorkOrderApi.getWorkOrderPage({ pageNo: 1, pageSize: 5 }).then((page) => {
        counts.workOrder = page.total
        workOrders.value = page.list
      })
    )
  }
  if (checkPermi(['mes:pro-feedback:query'])) {
    counts.feedback = undefined
    requests.push(
      ProFeedbackApi.getFeedbackPage({ pageNo: 1, pageSize: 1 }).then((page) => {
        counts.feedback = page.total
      })
    )
  }
  if (canQueryHomeStatistics.value) {
    summary.value = undefined
    requests.push(
      MesHomeStatisticsApi.getHomeSummary().then((data) => {
        summary.value = data
      })
    )
  }
  try {
    const results = await Promise.allSettled(requests)
    loadFailed.value = results.some((result) => result.status === 'rejected')
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
