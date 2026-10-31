<template>
  <div ref="screen" class="production-screen">
    <header class="screen-header">
      <div class="brand"><img src="/fazemes-logo.svg" alt="FazeMES标识" /><span>FazeMES</span></div>
      <div class="screen-heading"><h1>生产实时大屏</h1></div>
      <div class="screen-clock"
        ><strong>{{ clock }}</strong
        ><span>{{ dateLabel }}</span></div
      >
    </header>
    <div class="screen-controls">
      <div class="filter-controls">
        <el-select
          v-model="filters.workshopId"
          placeholder="全部车间"
          aria-label="车间"
          clearable
          popper-class="production-screen-popper"
          :teleported="false"
          @change="changeWorkshop"
        >
          <el-option v-for="w in data?.workshops || []" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-select
          v-model="filters.workstationId"
          placeholder="全部工作站"
          aria-label="工作站"
          clearable
          popper-class="production-screen-popper"
          :teleported="false"
          @change="load"
        >
          <el-option
            v-for="w in workstationOptions"
            :key="w.id"
            :label="`${w.name} · ${w.scheduleLabel}`"
            :value="w.id"
          />
        </el-select>
        <el-date-picker
          v-model="filters.date"
          type="date"
          aria-label="生产日期"
          value-format="YYYY-MM-DD"
          :clearable="false"
          :shortcuts="dateShortcuts"
          popper-class="production-screen-popper"
          :teleported="false"
          @change="load"
        />
      </div>
      <div class="refresh-controls">
        <span :class="['live-state', { failed: error }]" :title="productionContext" role="status">{{
          error ? '刷新失败' : loading ? '同步中' : '已连接'
        }}</span>
        <el-switch v-model="autoRefresh" active-text="30秒刷新" aria-label="自动刷新" />
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button @click="toggleFullscreen">全屏</el-button>
        <el-button @click="router.back()">返回</el-button>
      </div>
    </div>
    <div v-if="error" class="screen-error" role="alert"
      >{{ error }}{{ data ? ` · 保留上次成功数据 ${formatDate(data.updatedAt)}` : '' }}</div
    >
    <main v-if="data" class="screen-main">
      <div class="analytics-column">
        <section class="screen-panel production-panel" :title="productionContext">
          <div class="panel-heading"><h2>生产运行概况</h2></div>
          <div class="production-metrics">
            <article
              v-for="(k, index) in kpis.slice(0, 3)"
              :key="k.label"
              class="metric-row"
              :class="k.tone"
            >
              <span class="metric-symbol" aria-hidden="true">{{ ['产', '成', '质'][index] }}</span>
              <span>{{ k.label }}</span
              ><strong>{{ k.value }}</strong>
            </article>
          </div>
          <div class="production-counts"
            ><span
              >任务 <b>{{ data.taskCount }}</b></span
            ><span
              >报工 <b>{{ data.feedbackCount }}</b></span
            ><span
              >合格 <b>{{ format(data.qualified) }}</b></span
            ></div
          >
        </section>
        <section class="screen-panel trend-panel">
          <div class="panel-heading"><h2>近7日产量趋势</h2></div>
          <div
            class="chart-body"
            :title="data.trend.length ? '' : '近7日无已完成报工，零值表示当日没有完成记录。'"
            ><Echart :options="trendOptions" height="100%"
          /></div>
        </section>
        <section class="screen-panel ranking-panel">
          <div class="panel-heading"
            ><h2>城市排行</h2><span v-if="mapExample" class="example-badge">示例</span></div
          >
          <ChinaDistributionMap
            :cities="mapCities"
            :metric-label="mapExample ? '销售示例' : '当日关联工单数'"
            :unit="mapExample ? '示例值' : '单'"
            :is-example="mapExample"
            view="ranking"
          />
        </section>
      </div>
      <div class="production-column">
        <section
          class="screen-panel map-panel"
          :title="mapExample ? '示例值，与本系统业务统计无关' : regionData?.basis"
        >
          <div class="panel-heading">
            <h2>{{ mapExample ? '产品销售市场 · 示例' : '客户区域工单分布' }}</h2>
            <div class="map-controls"
              ><span v-if="!mapExample" class="map-count"
                >已定位
                <b
                  >{{ regionData?.locatedOrderCount || 0 }}/{{
                    regionData?.totalOrderCount || 0
                  }}</b
                >
                · 待定位 <b>{{ regionData?.unlocatedOrderCount || 0 }}</b></span
              ><el-switch v-model="mapExample" active-text="示例" aria-label="切换地图示例数据"
            /></div>
          </div>
          <ChinaDistributionMap
            :cities="mapCities"
            :metric-label="mapExample ? '销售示例' : '当日关联工单数'"
            :unit="mapExample ? '示例值' : '单'"
            :is-example="mapExample"
            view="map"
            :empty-text="
              regionData?.totalOrderCount
                ? '已有工单，需完善客户的标准城市地址。'
                : '所选日期及范围暂无关联工单，可调整日期或筛选。'
            "
          />
        </section>
        <section class="screen-panel flow-panel">
          <div class="panel-heading"><h2>工位生产动态</h2></div>
          <div v-if="!stationTasks.length" class="screen-empty"
            >当前范围尚未建立工位，请先完善工作站资料。</div
          >
          <div v-else class="flow-strip">
            <template v-for="(station, index) in flowStations" :key="station.id">
              <button class="topology-station" @click="openStation(station.id)"
                ><span class="station-node" aria-hidden="true"><Icon icon="ep:setting" /></span
                ><span
                  ><strong>{{ station.name }}</strong
                  ><small>{{
                    station.count
                      ? `${format(station.produced)} / ${format(station.target)}`
                      : '所选日期未排产'
                  }}</small></span
                ></button
              >
              <div
                v-if="index < flowStations.length - 1"
                class="topology-link"
                aria-hidden="true"
              ></div>
            </template>
          </div>
        </section>
        <section class="screen-panel orders-panel">
          <div class="panel-heading"
            ><h2>工单执行进度</h2><span class="panel-total">{{ data.workOrders.length }}</span></div
          >
          <div class="panel-scroll" tabindex="0" aria-label="工单执行进度列表">
            <div v-if="!data.workOrders.length" class="screen-empty"
              >所选日期没有关联排产工单，可调整日期或车间查看。</div
            >
            <table v-else class="order-table">
              <thead
                ><tr><th>工单编码</th><th>工单名称</th><th>已产 / 目标</th><th>进度</th></tr></thead
              >
              <tbody
                ><tr v-for="w in data.workOrders" :key="w.id"
                  ><td>{{ w.code }}</td
                  ><td :title="w.name">{{ w.name }}</td
                  ><td>{{ format(w.producedQuantity) }} / {{ format(w.quantity) }}</td
                  ><td
                    ><el-progress
                      :percentage="progress(w.producedQuantity, w.quantity)"
                      :stroke-width="5"
                      color="#32cddd" /></td></tr
              ></tbody>
            </table>
          </div>
        </section>
      </div>
      <div class="operations-column">
        <section class="screen-panel equipment-panel">
          <div class="panel-heading"
            ><h2>设备状态</h2><span class="panel-total">{{ deviceCount }}</span></div
          >
          <div class="device-chart chart-body"
            ><Echart v-if="deviceCount" :options="deviceOptions" height="100%" /><div
              v-if="deviceCount"
              class="device-total"
              ><strong>{{ deviceCount }}</strong
              ><span>设备总数</span></div
            ><div v-else class="screen-empty">{{
              filters.workstationId
                ? '该工位尚未绑定设备，请完善工位设备资源。'
                : '当前车间范围尚无设备台账，请完善设备所属车间。'
            }}</div></div
          >
          <div class="device-legend"
            ><span v-for="d in data.devices" :key="d.status"
              ><i :class="`device-status-${d.status}`"></i>{{ deviceStatus(d.status) }}
              <b>{{ d.count }}</b></span
            ></div
          >
        </section>
        <section class="screen-panel andon-panel">
          <div class="panel-heading"><h2>现场异常态势</h2></div>
          <div :class="['andon-state', { alert: Number(data.activeAndon) > 0 }]"
            ><i></i>{{ Number(data.activeAndon) === 0 ? '当前无活动安灯' : '当前有活动安灯' }}</div
          >
          <div class="exception-metrics"
            ><article v-for="k in kpis.slice(3)" :key="k.label" :class="['exception-ring', k.tone]"
              ><span>{{ k.label }}</span
              ><strong>{{ k.value }}</strong></article
            ></div
          >
        </section>
        <section class="screen-panel tasks-panel">
          <div class="panel-heading"
            ><h2>工位任务</h2
            ><span
              class="panel-total"
              :title="`${activeStationCount} 个已排产 / ${stationTasks.length} 个工位 · 展示前8项`"
              >{{ stationTasks.length }}</span
            ></div
          >
          <div class="panel-scroll" tabindex="0" aria-label="工位任务列表">
            <div v-if="!stationTasks.length" class="screen-empty"
              >当前范围尚未建立工位，请先完善工作站资料。</div
            >
            <button
              v-for="s in visibleStationTasks"
              :key="s.id"
              class="station-row"
              @click="openStation(s.id)"
              ><div
                ><strong>{{ s.name }}</strong
                ><span>{{
                  s.count ? `${s.count} 项任务` : '工位已建档 · 所选日期未排产'
                }}</span></div
              ><el-progress
                :percentage="progress(s.produced, s.target)"
                :stroke-width="5"
                color="#32cddd"
              /><div class="station-quantity"
                ><span>{{ format(s.produced) }} / {{ format(s.target) }}</span
                ><span>查看 →</span></div
              ></button
            >
          </div>
        </section>
      </div>
    </main>
    <div v-else-if="loading" class="screen-empty">正在读取生产业务数据…</div>
    <div v-else class="screen-empty">数据尚未加载，请点击刷新重试。</div>
    <el-drawer
      v-model="drawer"
      :title="`${selectedStationName} · ${data?.date || filters.date} 任务`"
      size="min(900px, 90vw)"
      class="production-screen-drawer"
      :append-to="screen"
      append-to-body
    >
      <el-table :data="selectedTasks" empty-text="暂无任务"
        ><el-table-column prop="code" label="任务编码" min-width="150" /><el-table-column
          prop="name"
          label="任务名称"
          min-width="140" /><el-table-column prop="quantity" label="目标" /><el-table-column
          prop="producedQuantity"
          label="已产" /><el-table-column label="状态"
          ><template #default="{ row }">{{ orderStatus(row.status) }}</template></el-table-column
        ><el-table-column prop="startTime" label="计划开始" min-width="165"
      /></el-table>
      <el-button class="mt-16px" type="primary" @click="enterWorkbench">进入工位工作台</el-button>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { formatQuantity, quantityText, quantityUnits } from '@/utils/mesQuantity'
import { formatDate } from '@/utils/formatTime'
import dayjs from 'dayjs'
import { Echart } from '@/components/Echart'
import type { EChartsOption } from 'echarts'
import { DashboardApi, type ProductionDashboard } from '@/api/mes/dashboard'
import ChinaDistributionMap from './components/ChinaDistributionMap.vue'
import { YUNSHU_EXAMPLE_CITIES } from './components/chinaDistribution'
defineOptions({ name: 'MesProductionDashboard' })
const router = useRouter()
const screen = ref<HTMLElement>()
const data = ref<ProductionDashboard>()
const mapExample = ref(false)
const regionData = computed(() => data.value?.regionalDistribution)
const mapCities = computed(() =>
  mapExample.value ? YUNSHU_EXAMPLE_CITIES : regionData.value?.cities || []
)
const loading = ref(false)
const error = ref('')
const autoRefresh = ref(true)
const filters = reactive<{ workshopId?: number; workstationId?: number; date: string }>({
  date: dayjs().format('YYYY-MM-DD')
})
const now = ref(new Date())
const clock = computed(() => dayjs(now.value).format('HH:mm:ss'))
const dateLabel = computed(() => dayjs(now.value).format('YYYY年MM月DD日'))
const today = computed(() => dayjs(now.value).format('YYYY-MM-DD'))
const hasProduction = computed(() => Boolean(data.value?.taskCount || data.value?.feedbackCount))
const latestActivityDate = computed(() => data.value?.availability?.latestActivityDate)
const workstationOptions = computed(() => {
  const scheduled = new Set(data.value?.tasks.map((task) => task.workstationId))
  const completeList = (data.value?.taskCount || 0) <= (data.value?.tasks.length || 0)
  return (data.value?.workstations || [])
    .map((station) => ({
      ...station,
      scheduled: scheduled.has(station.id),
      scheduleLabel: scheduled.has(station.id)
        ? '有排产'
        : completeList && (!filters.workstationId || filters.workstationId === station.id)
          ? '未排产'
          : '待查看'
    }))
    .sort((left, right) => Number(right.scheduled) - Number(left.scheduled))
})
// Keep the remote layout: date actions live in the existing picker's popover.
const dateShortcuts = computed(() => [
  { text: '今天', value: () => dayjs(today.value).toDate() },
  ...(latestActivityDate.value && latestActivityDate.value !== today.value
    ? [
        {
          text: `最近有数据日期 ${latestActivityDate.value}`,
          value: () => dayjs(latestActivityDate.value).toDate()
        }
      ]
    : [])
])
const productionContext = computed(() => {
  if (!data.value) return '正在读取生产业务数据'
  const date = data.value.date
  const period =
    date === today.value ? '当日生产' : date > today.value ? '计划日期' : '历史生产数据'
  const activity = hasProduction.value
    ? `${data.value.taskCount} 项任务 · ${data.value.feedbackCount} 笔完成报工`
    : '所选范围当日暂无排产和已完成报工；可从日期选择器查看最近有数据日期'
  return `${date} · ${period} · ${activity}。${data.value.basis}`
})
const format = (n: unknown) => formatQuantity(n ?? '0')
const progress = (produced: number | string, target: number | string) =>
  Number(target) > 0
    ? Math.min(100, Math.max(0, Math.round((Number(produced || 0) / Number(target)) * 100)))
    : 0
const ratio = computed(() =>
  Number(data.value?.produced) > 0
    ? `${((Number(data.value?.qualified) / Number(data.value?.produced)) * 100).toFixed(2)}%`
    : '—'
)
const kpis = computed(() => [
  {
    label: '工序任务目标',
    value: format(data.value?.target),
    tone: ''
  },
  {
    label: '完成报工产量',
    value: format(data.value?.produced),
    tone: 'teal'
  },
  {
    label: '报工合格率',
    value: ratio.value,
    tone: 'teal'
  },
  {
    label: '过程待检单',
    value: format(data.value?.pendingInspection),
    tone: 'amber'
  },
  {
    label: '活动安灯',
    value: format(data.value?.activeAndon),
    tone: 'rose'
  },
  {
    label: '全厂缺料工单',
    value: data.value?.shortageAvailable ? format(data.value.shortageWorkOrderCount) : '—',
    tone: 'amber'
  }
])
let requestSequence = 0
async function load() {
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const result = await DashboardApi.production({ ...filters })
    if (sequence === requestSequence) data.value = result
  } catch {
    if (sequence === requestSequence) error.value = '生产数据刷新失败，请检查网络或服务后重试。'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}
const changeWorkshop = () => {
  filters.workstationId = undefined
  load()
}
const deviceLabels: Record<number, string> = { 1: '停机', 2: '生产中', 3: '维护中' }
const deviceColors: Record<number, string> = { 1: '#74849b', 2: '#35d5c0', 3: '#f3b85f' }
const orderLabels: Record<number, string> = {
  0: '草稿',
  1: '已确认',
  2: '审批中',
  3: '已审批',
  4: '已完成',
  5: '已取消'
}
const deviceStatus = (s: number) => deviceLabels[s] || `状态 ${s}`
const orderStatus = (s: number) => orderLabels[s] || String(s)
const deviceCount = computed(
  () => data.value?.devices.reduce((s, d) => s + Number(d.count), 0) || 0
)
const deviceOptions = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  color: ['#74849b', '#35d5c0', '#f3b85f'],
  tooltip: { trigger: 'item' },
  series: [
    {
      type: 'pie',
      radius: ['61%', '82%'],
      label: { show: false },
      itemStyle: { borderColor: '#050e16', borderWidth: 3 },
      data:
        data.value?.devices.map((d) => ({
          name: deviceStatus(d.status),
          value: Number(d.count),
          itemStyle: { color: deviceColors[d.status] || '#74849b' }
        })) || []
    }
  ]
}))
const trendOptions = computed<EChartsOption>(() => {
  const days = Array.from({ length: 7 }, (_, i) =>
    dayjs(data.value?.date || filters.date)
      .subtract(6 - i, 'day')
      .format('YYYY-MM-DD')
  )
  return {
    backgroundColor: 'transparent',
    color: ['#32cddd', '#40e0b5'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { color: '#a5bbd4' } },
    grid: { left: 12, right: 12, top: 36, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: days.map((d) => d.slice(5)),
      axisLabel: { color: '#91a9c3' },
      axisLine: { lineStyle: { color: '#2a405b' } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#91a9c3' },
      splitLine: { lineStyle: { color: '#1b3049' } }
    },
    series: (['produced', 'qualified'] as const).map((key, index) => ({
      name: index ? '合格数量' : '报工数量',
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.08 },
      data: days.map((day) =>
        Number(data.value?.trend.find((t) => String(t.day).slice(0, 10) === day)?.[key] || 0)
      )
    }))
  }
})
const stationTasks = computed(() => {
  const result = new Map<
    number,
    { id: number; name: string; count: number; producedUnits: bigint; targetUnits: bigint }
  >()
  data.value?.workstations
    .filter((station) => !filters.workstationId || station.id === filters.workstationId)
    .forEach((station) => {
      result.set(station.id, {
        id: station.id,
        name: station.name,
        count: 0,
        producedUnits: 0n,
        targetUnits: 0n
      })
    })
  data.value?.tasks.forEach((t) => {
    const s = result.get(t.workstationId) || {
      id: t.workstationId,
      name:
        data.value?.workstations.find((s) => s.id === t.workstationId)?.name ||
        `工位 ${t.workstationId}`,
      count: 0,
      producedUnits: 0n,
      targetUnits: 0n
    }
    s.count++
    s.producedUnits += quantityUnits(t.producedQuantity ?? '0') ?? 0n
    s.targetUnits += quantityUnits(t.quantity ?? '0') ?? 0n
    result.set(t.workstationId, s)
  })
  return [...result.values()]
    .sort((left, right) => right.count - left.count || left.id - right.id)
    .map((station) => ({
      ...station,
      produced: quantityText(station.producedUnits),
      target: quantityText(station.targetUnits)
    }))
})
const activeStationCount = computed(
  () => stationTasks.value.filter((station) => station.count > 0).length
)
const flowStations = computed(() => stationTasks.value.slice(0, 5))
const visibleStationTasks = computed(() => stationTasks.value.slice(0, 8))
const drawer = ref(false)
const selectedStation = ref<number>()
const selectedStationName = computed(
  () => data.value?.workstations.find((s) => s.id === selectedStation.value)?.name || '工位'
)
const selectedTasks = computed(
  () => data.value?.tasks.filter((t) => t.workstationId === selectedStation.value) || []
)
const openStation = (id: number) => {
  selectedStation.value = id
  drawer.value = true
}
const enterWorkbench = () => {
  router.push({ path: '/mes/workbench', query: { workstationId: selectedStation.value } })
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await screen.value?.requestFullscreen()
  } catch {
    error.value = '浏览器未允许全屏，可继续使用当前大屏视图。'
  }
}
let clockTimer: ReturnType<typeof setInterval>
let refreshTimer: ReturnType<typeof setInterval>
onMounted(() => {
  load()
  clockTimer = setInterval(() => {
    const wasToday = filters.date === today.value
    now.value = new Date()
    if (wasToday && filters.date !== today.value) {
      filters.date = today.value
      load()
    }
  }, 1000)
  refreshTimer = setInterval(() => {
    if (autoRefresh.value && !document.hidden && !loading.value) load()
  }, 30000)
})
onBeforeUnmount(() => {
  clearInterval(clockTimer)
  clearInterval(refreshTimer)
})
</script>

<style scoped lang="scss">
.production-screen {
  --screen-cyan: #47d9f5;
  --screen-muted: #83a9bc;
  --screen-line: #173744;
  --el-color-primary: #23bcd4;
  --el-text-color-primary: #d8f4ff;
  --el-text-color-regular: #a3c9d9;
  --el-text-color-placeholder: #83a9bc;
  --el-fill-color-blank: #081923;
  --el-border-color: #245064;
  --el-switch-on-color: #148a9a;

  position: fixed;
  z-index: 2000;
  display: flex;
  padding: 16px 24px 20px;
  overflow: auto;
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
  color: #c6e6ef;
  background: #02090d;
  inset: 0;
  flex-direction: column;
  gap: 14px;
  color-scheme: dark;
}

.production-screen * {
  box-sizing: border-box;
}

.screen-header {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  min-height: 56px;
  gap: 32px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
}

.brand img {
  width: 36px;
  height: 36px;
}

.screen-heading {
  display: flex;
  align-items: center;
  gap: 24px;
}

.screen-heading::before,
.screen-heading::after {
  width: clamp(30px, 8vw, 180px);
  height: 1px;
  background: linear-gradient(90deg, transparent, #238ca4);
  content: '';
}

.screen-heading::after {
  transform: rotate(180deg);
}

.screen-heading h1 {
  margin: 0;
  font-size: clamp(24px, 2vw, 38px);
  font-weight: 700;
  letter-spacing: 6px;
  color: #d9f8ff;
  text-shadow: 0 0 18px #28c5ed85;
  white-space: nowrap;
}

.screen-clock {
  text-align: right;
}

.screen-clock strong {
  display: block;
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}

.screen-clock span {
  font-size: 12px;
  color: var(--screen-muted);
}

.screen-controls,
.filter-controls,
.refresh-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.screen-controls {
  flex-shrink: 0;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--screen-line);
}

.filter-controls .el-select {
  width: 146px;
}

.filter-controls :deep(.el-date-editor) {
  width: 146px;
}

.refresh-controls :deep(.el-button + .el-button) {
  margin-left: 0;
}

.refresh-controls :deep(.el-switch__label) {
  color: var(--screen-muted);
}

.production-screen :deep(.el-select__wrapper),
.production-screen :deep(.el-input__wrapper) {
  background: #081923;
  box-shadow: 0 0 0 1px #245064 inset;
}

.production-screen :deep(.el-input__inner) {
  color: #c6e6ef;
}

.live-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-right: 6px;
  font-size: 12px;
  color: #41dcb3;
}

.live-state::before {
  width: 6px;
  height: 6px;
  background: currentcolor;
  border-radius: 50%;
  content: '';
  box-shadow: 0 0 8px currentcolor;
}

.live-state.failed {
  color: #ff6485;
}

.screen-error {
  padding: 10px 14px;
  color: #ff9cac;
  background: #311524;
  border: 1px solid #663043;
}

.screen-main {
  display: grid;
  flex: 1;
  min-height: 560px;
  grid-template-columns: minmax(240px, 1fr) minmax(440px, 2.25fr) minmax(250px, 1fr);
  gap: 20px;
}

.analytics-column,
.production-column,
.operations-column {
  display: grid;
  min-width: 0;
  min-height: 0;
  gap: 16px;
}

.analytics-column {
  grid-template-rows: auto minmax(170px, 1fr) minmax(180px, 1fr);
  padding-right: 16px;
  border-right: 1px solid var(--screen-line);
}

.production-column {
  grid-template-rows: minmax(300px, 1fr) 84px 150px;
}

.operations-column {
  grid-template-rows: minmax(180px, 1fr) auto minmax(150px, 1fr);
  padding-left: 16px;
  border-left: 1px solid var(--screen-line);
}

.screen-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
}

.panel-heading {
  display: flex;
  min-height: 38px;
  padding: 0 12px;
  margin-bottom: 10px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: #071c27;
  border-left: 2px solid #21c8ee;
}

.panel-heading h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 500;
  color: #7ee0f4;
}

.panel-total {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  color: #c6f5ff;
}

.production-metrics {
  display: grid;
  gap: 12px;
  padding: 4px 4px 10px;
}

.metric-row {
  display: grid;
  grid-template-columns: 32px 1fr auto;
  align-items: center;
  gap: 10px;
}

.metric-symbol {
  display: grid;
  width: 30px;
  height: 30px;
  font-size: 12px;
  color: #47d9f5;
  border: 1px solid #199aba;
  border-radius: 50%;
  box-shadow: inset 0 0 10px #0e7e9a55;
  place-items: center;
}

.metric-row > span:nth-child(2) {
  font-size: 13px;
  color: var(--screen-muted);
}

.metric-row strong {
  font-size: clamp(22px, 1.7vw, 32px);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: #dcf8ff;
}

.metric-row.teal strong {
  color: #51dfc8;
}

.production-counts {
  display: flex;
  padding: 10px 4px 0;
  font-size: 11px;
  color: var(--screen-muted);
  border-top: 1px solid #102b38;
  gap: 10px;
  justify-content: space-between;
}

.production-counts b {
  margin-left: 4px;
  font-weight: 500;
  color: #d1f2fb;
}

.chart-body {
  position: relative;
  flex: 1;
  min-height: 0;
}

.ranking-panel :deep(.china-distribution) {
  flex: 1;
  min-height: 0;
}

.example-badge {
  font-size: 12px;
  color: #f5bf61;
}

.map-panel {
  padding: 0;
  background: radial-gradient(ellipse at center, #1c293b, #101925 75%);
}

.map-panel .panel-heading {
  padding: 10px 16px 0;
  margin-bottom: 0;
  background: transparent;
  border-left: 0;
}

.map-panel .panel-heading h2 {
  font-size: 14px;
  color: #c3e1eb;
}

.map-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.map-count {
  font-size: 11px;
  color: #83a9bc;
}

.map-count b {
  font-weight: 500;
  color: #b2dce9;
}

.map-controls :deep(.el-switch__label) {
  color: #f5bf61;
}

.map-panel :deep(.china-distribution) {
  min-height: 0;
  flex: 1;
}

.flow-panel .panel-heading {
  min-height: 30px;
  margin-bottom: 4px;
}

.flow-strip {
  display: flex;
  flex: 1;
  align-items: center;
  padding: 0 8px;
}

.topology-station {
  display: flex;
  min-width: 0;
  padding: 6px 3px;
  color: #b8dce7;
  cursor: pointer;
  background: transparent;
  border: 0;
  align-items: center;
  gap: 8px;
}

.station-node {
  display: grid;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  place-items: center;
  color: #47d9f5;
  background: #0c2935;
  border: 1px solid #21617b;
  border-radius: 50%;
}

.topology-station strong,
.topology-station small {
  display: block;
  max-width: 90px;
  overflow: hidden;
  font-size: 11px;
  font-weight: 400;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topology-station small {
  margin-top: 5px;
  color: var(--screen-muted);
}

.topology-link {
  height: 1px;
  min-width: 8px;
  margin: 0 8px;
  background: #21617b;
  flex: 1;
}

.panel-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: #225164 transparent;
}

.order-table {
  width: 100%;
  font-size: 12px;
  border-collapse: collapse;
  table-layout: fixed;
}

.order-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 8px 10px;
  font-weight: 400;
  color: #49c7e8;
  text-align: left;
  background: #07202d;
}

.order-table td {
  padding: 9px 10px;
  overflow: hidden;
  color: #a3c9d9;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-bottom: 1px solid #14303b;
}

.order-table tbody tr:nth-child(even) {
  background: #06141c;
}

.order-table th:last-child {
  width: 26%;
}

.screen-panel :deep(.el-progress__text) {
  min-width: 34px;
  font-size: 11px !important;
  color: #a3c9d9;
}

.screen-panel :deep(.el-progress-bar__outer) {
  background: #17313f;
}

.screen-empty {
  display: grid;
  height: 100%;
  min-height: 0;
  padding: 16px;
  font-size: 13px;
  color: #6d96aa;
  text-align: center;
  flex: 1;
  place-items: center;
}

.device-total {
  position: absolute;
  display: grid;
  inset: 0;
  place-content: center;
  text-align: center;
  pointer-events: none;
}

.device-total strong {
  font-size: 30px;
  font-weight: 500;
  color: #d6f4ff;
  font-variant-numeric: tabular-nums;
}

.device-total span {
  margin-top: 5px;
  font-size: 11px;
  color: var(--screen-muted);
}

.device-legend {
  display: flex;
  padding: 8px 0;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 12px;
}

.device-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--screen-muted);
}

.device-legend b {
  color: #dcf8ff;
}

.device-legend i {
  width: 6px;
  height: 6px;
  background: #74849b;
  border-radius: 50%;
}

.device-legend .device-status-2 {
  background: #35d5c0;
}

.device-legend .device-status-3 {
  background: #f3b85f;
}

.andon-state {
  display: flex;
  padding: 3px 4px 12px;
  font-size: 12px;
  color: #51dfb7;
  align-items: center;
  gap: 8px;
}

.andon-state i {
  width: 5px;
  height: 5px;
  background: currentcolor;
  border-radius: 50%;
  box-shadow: 0 0 7px currentcolor;
}

.andon-state.alert {
  color: #ff6485;
}

.exception-metrics {
  display: flex;
  justify-content: space-around;
  gap: 8px;
  padding-bottom: 4px;
}

.exception-ring {
  display: flex;
  width: 30%;
  aspect-ratio: 1;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

.exception-ring.amber {
  color: #e9b858;
}

.exception-ring.rose {
  color: #f65b82;
}

.exception-ring span {
  font-size: clamp(9px, 0.68vw, 12px);
}

.exception-ring strong {
  font-size: clamp(21px, 1.8vw, 32px);
  font-weight: 500;
  color: #d6f4ff;
  font-variant-numeric: tabular-nums;
}

.station-row {
  display: grid;
  width: 100%;
  padding: 12px 4px;
  gap: 8px;
  color: #b8dce7;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-bottom: 1px solid #14303b;
}

.station-row > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.station-row strong {
  font-size: 12px;
  font-weight: 500;
}

.station-row span {
  font-size: 11px;
  color: var(--screen-muted);
}

.station-row .station-quantity > span:last-child {
  color: #47d9f5;
}

.station-row:hover,
.topology-station:hover {
  background: #0c2632;
}

.production-screen button:focus-visible,
.panel-scroll:focus-visible {
  outline: 2px solid #47d9f5;
  outline-offset: 2px;
}

@media (width >= 1800px) and (height >= 950px) {
  .production-screen {
    padding: 24px 32px;
    gap: 20px;
  }

  .screen-main {
    gap: 24px;
  }

  .production-column {
    grid-template-rows: minmax(400px, 1fr) 96px 180px;
  }

  .panel-heading {
    min-height: 44px;
  }

  .panel-heading h2 {
    font-size: 18px;
  }

  .production-metrics {
    gap: 18px;
    padding: 12px 4px 18px;
  }

  .metric-row > span:nth-child(2),
  .order-table {
    font-size: 14px;
  }
}

@media (width <= 1150px) {
  .screen-main {
    flex: none;
    min-height: 0;
    grid-template-columns: minmax(250px, 1fr) minmax(400px, 1.8fr);
  }

  .analytics-column {
    grid-template-rows: auto 220px 240px;
  }

  .production-column {
    grid-template-rows: minmax(330px, 1fr) 108px 190px;
  }

  .operations-column {
    grid-column: 1 / -1;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    grid-template-rows: 250px;
    padding: 0;
    border-left: 0;
  }

  .screen-heading::before,
  .screen-heading::after {
    display: none;
  }

  .screen-controls {
    row-gap: 12px;
  }
}

@media (width <= 720px) {
  .production-screen {
    padding: 12px;
  }

  .screen-header {
    grid-template-columns: 1fr auto;
    gap: 12px;
  }

  .brand {
    font-size: 20px;
  }

  .screen-heading {
    grid-column: 1 / -1;
    grid-row: 2;
    justify-content: center;
  }

  .screen-heading h1 {
    font-size: 24px;
  }

  .filter-controls {
    width: 100%;
    gap: 6px;
  }

  .filter-controls .el-select,
  .filter-controls :deep(.el-date-editor) {
    width: 0;
    flex: 1;
    min-width: 0;
  }

  .refresh-controls {
    gap: 8px;
  }

  .screen-main {
    display: flex;
    flex-direction: column;
  }

  .production-column {
    order: -1;
    grid-template-rows: 360px auto 240px;
  }

  .analytics-column {
    padding-right: 0;
    border-right: 0;
    grid-template-rows: auto 240px 260px;
  }

  .operations-column {
    display: grid;
    grid-template-columns: 1fr;
    grid-template-rows: 270px auto 260px;
  }

  .map-panel .panel-heading {
    flex-wrap: wrap;
  }

  .map-controls {
    width: 100%;
    justify-content: space-between;
  }

  .flow-strip {
    padding: 10px 4px;
    overflow-x: auto;
  }

  .topology-station {
    flex-shrink: 0;
  }

  .exception-ring {
    width: 28%;
    max-width: 120px;
  }

  .exception-ring span {
    font-size: 12px;
  }

  .exception-ring strong {
    font-size: 28px;
  }

  .order-table {
    min-width: 530px;
  }
}
</style>

<style lang="scss">
.production-screen-popper,
.production-screen-drawer {
  --el-color-primary: #23bcd4;
  --el-bg-color: #091923;
  --el-bg-color-overlay: #091923;
  --el-fill-color-blank: #091923;
  --el-fill-color-light: #12303e;
  --el-text-color-primary: #d8f4ff;
  --el-text-color-regular: #a3c9d9;
  --el-text-color-secondary: #83a9bc;
  --el-border-color: #245064;
  --el-border-color-light: #173744;
  --el-border-color-lighter: #173744;
}
</style>
