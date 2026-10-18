<template>
  <div ref="screen" class="production-screen">
    <div class="ambient-flylines" aria-hidden="true"><i v-for="n in 7" :key="n"></i></div>
    <div class="header-light-beam" aria-hidden="true"></div>
    <header class="screen-header"
      ><div class="brand"
        ><img class="brand-mark" src="/fazemes-logo.svg" alt="FazeMES标识" /><div
          ><span class="brand-name">FazeMES</span
          ><span class="brand-caption">制造运营指挥中心</span></div
        ></div
      ><div class="screen-heading"><h1>生产实时大屏</h1><span>PRODUCTION CONTROL CENTER</span></div
      ><div class="screen-clock"
        ><strong>{{ clock }}</strong
        ><span>{{ dateLabel }}</span></div
      ></header
    >
    <div class="screen-controls"
      ><div class="filter-controls"
        ><el-select
          v-model="filters.workshopId"
          placeholder="全部车间"
          clearable
          @change="changeWorkshop"
          ><el-option
            v-for="w in data?.workshops || []"
            :key="w.id"
            :label="w.name"
            :value="w.id" /></el-select
        ><el-select
          v-model="filters.workstationId"
          placeholder="全部工作站"
          clearable
          @change="load"
          ><el-option
            v-for="w in data?.workstations || []"
            :key="w.id"
            :label="w.name"
            :value="w.id" /></el-select
        ><el-date-picker
          v-model="filters.date"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          @change="load" /></div
      ><div class="refresh-controls"
        ><span :class="['live-state', { failed: error }]">{{
          error ? '刷新失败' : loading ? '同步中' : '业务数据已连接'
        }}</span
        ><el-switch v-model="autoRefresh" active-text="30秒刷新" /><el-button
          :loading="loading"
          @click="load"
          >刷新</el-button
        ><el-button @click="toggleFullscreen">全屏</el-button
        ><el-button @click="router.back()">返回</el-button></div
      ></div
    >
    <div v-if="error" class="screen-error"
      >{{ error }}{{ data ? ` · 保留上次成功数据 ${formatDate(data.updatedAt)}` : '' }}</div
    >
    <main v-if="data" class="screen-main">
      <section class="kpi-grid"
        ><article v-for="k in kpis" :key="k.label" class="kpi-card" :class="k.tone"
          ><span>{{ k.label }}</span
          ><strong>{{ k.value }}</strong
          ><small>{{ k.note }}</small></article
        ></section
      >
      <div class="overview-grid">
        <section class="screen-panel map-panel">
          <div class="panel-heading">
            <h2>{{ mapExample ? '产品销售市场 · 示例' : '客户区域工单分布' }}</h2>
            <el-switch v-model="mapExample" active-text="示例" aria-label="切换地图示例数据" />
          </div>
          <ChinaDistributionMap
            :cities="mapCities"
            :metric-label="mapExample ? '云枢销售展示示例' : '当日关联工单数'"
            :unit="mapExample ? '示例值' : '单'"
            :source-label="
              mapExample
                ? '云枢原图示例值，与本系统业务统计无关'
                : regionData?.basis || '客户城市分布尚未加载'
            "
            :is-example="mapExample"
            empty-text="暂无可定位工单，可切换示例查看城市涟漪与排行"
          />
          <p v-if="!mapExample" class="map-coverage">
            已定位 {{ regionData?.locatedOrderCount || 0 }} /
            {{ regionData?.totalOrderCount || 0 }} 单 ·
            {{ regionData?.unlocatedOrderCount || 0 }} 单城市信息待完善
          </p>
        </section>
        <div class="production-column">
          <section class="screen-panel hero-summary" data-aero-panel>
            <span class="hero-scan" aria-hidden="true"></span>
            <div class="hero-copy"
              ><span>当日完成报工产量</span><strong>{{ format(data.produced) }}</strong
              ><small>{{ filters.date }} · {{ data.feedbackCount }} 笔已完成报工</small></div
            >
            <div class="hero-orbit" aria-hidden="true"
              ><span class="orbit-satellite one"></span><span class="orbit-satellite two"></span
              ><div class="orbit-core"><span>生产数据</span><b>已连接</b></div></div
            >
          </section>
          <section class="screen-panel trend-panel">
            <div class="panel-heading"
              ><h2>近7日产量趋势</h2><span>已完成报工 · 与当前筛选一致</span></div
            >
            <Echart v-if="data.trend.length" :options="trendOptions" :height="265" />
            <div v-else class="screen-empty">近7日暂无已完成报工</div>
          </section>
          <section class="screen-panel flow-panel">
            <div class="panel-heading"><h2>工位生产动态</h2><span>当前筛选 · 任务台账</span></div>
            <div v-if="!stationTasks.length" class="screen-empty">暂无匹配工位任务</div>
            <div v-else class="flow-strip">
              <template v-for="(station, index) in flowStations" :key="station.id">
                <button class="topology-station" @click="openStation(station.id)">
                  <span class="station-node" aria-hidden="true"
                    ><span class="node-glow"></span><Icon icon="ep:setting"
                  /></span>
                  <strong>{{ station.name }}</strong
                  ><small>{{ format(station.produced) }} / {{ format(station.target) }}</small>
                </button>
                <div v-if="index < flowStations.length - 1" class="topology-link" aria-hidden="true"
                  ><i></i><i></i><i></i
                ></div>
              </template>
              <span class="wip-carrier carrier-one" aria-hidden="true"></span
              ><span class="wip-carrier carrier-two" aria-hidden="true"></span>
            </div>
            <p v-if="stationTasks.length" class="flow-note"
              >仅展示前 {{ flowStations.length }} 个工位，连线用于动态展示；点击查看任务。</p
            >
          </section>
        </div>
        <div class="operations-column">
          <section class="screen-panel equipment-panel">
            <div class="panel-heading"><h2>设备状态</h2><span>设备台账当前状态</span></div>
            <Echart v-if="deviceCount" :options="deviceOptions" :height="245" />
            <div v-else class="screen-empty">暂无匹配设备</div>
            <div class="device-legend"
              ><span v-for="d in data.devices" :key="d.status"
                >{{ deviceStatus(d.status) }} <b>{{ d.count }}</b></span
              ></div
            >
          </section>
          <section class="screen-panel andon-panel">
            <div class="panel-heading"><h2>现场异常态势</h2><span>当前尚未处置</span></div>
            <div v-if="Number(data.activeAndon) === 0" class="safe-monitor">
              <div class="safe-radar" aria-hidden="true"><i></i><i></i><i></i><b></b></div>
              <div class="safe-copy"
                ><strong>当前无活动安灯</strong><span>以当前筛选范围内的安灯记录为准</span></div
              >
            </div>
            <div v-else class="andon-alert-summary"
              ><span class="alert-code"><i></i>活动安灯</span
              ><strong>{{ format(data.activeAndon) }}</strong
              ><span>请进入现场安灯处理</span></div
            >
            <div class="exception-totals"
              ><span
                >待过程检验 <b>{{ format(data.pendingInspection) }}</b></span
              ><span
                >全厂缺料工单
                <b>{{
                  data.shortageAvailable ? format(data.shortageWorkOrderCount) : '—'
                }}</b></span
              ></div
            >
          </section>
        </div>
      </div>
      <div class="screen-grid lower-grid"
        ><section class="screen-panel"
          ><div class="panel-heading"><h2>工单执行进度</h2><span>关联当日任务 · 最多30项</span></div
          ><div v-if="!data.workOrders.length" class="screen-empty">暂无当日关联工单</div
          ><div v-for="w in data.workOrders" :key="w.id" class="order-row"
            ><div
              ><strong>{{ w.code }}</strong
              ><small>{{ w.name }}</small></div
            ><div class="order-progress"
              ><el-progress
                :percentage="progress(w.producedQuantity, w.quantity)"
                :stroke-width="7"
                color="#35d5c0"
              /><span>{{ format(w.producedQuantity) }} / {{ format(w.quantity) }}</span></div
            ></div
          ></section
        ><section class="screen-panel"
          ><div class="panel-heading"
            ><h2>工位任务</h2><span>点击工位查看任务 · 最多100项</span></div
          ><div v-if="!stationTasks.length" class="screen-empty">暂无匹配工位任务</div
          ><button
            v-for="s in stationTasks"
            :key="s.id"
            class="station-row"
            @click="openStation(s.id)"
            ><div
              ><strong>{{ s.name }}</strong
              ><small
                >{{ s.count }} 项任务 · {{ format(s.produced) }} / {{ format(s.target) }}</small
              ></div
            ><el-progress
              :percentage="progress(s.produced, s.target)"
              :stroke-width="7"
              color="#62a5ff"
            /><span>查看 →</span></button
          ></section
        ></div
      >
      <footer class="screen-footer"
        ><span>{{ data.basis }}</span
        ><strong>最近成功刷新 {{ formatDate(data.updatedAt) }}</strong></footer
      >
    </main>
    <div v-else-if="loading" class="screen-empty">正在读取生产业务数据…</div
    ><div v-else class="screen-empty">数据尚未加载，请点击刷新重试。</div>
    <el-drawer
      v-model="drawer"
      :title="`${selectedStationName} · 当日任务`"
      size="min(900px, 90vw)"
      append-to-body
      ><el-alert
        title="以下为当前筛选日期交叠的生产工序任务，数据来自任务台账。"
        type="info"
        :closable="false"
      /><el-table :data="selectedTasks" class="mt-16px" empty-text="暂无任务"
        ><el-table-column prop="code" label="任务编码" min-width="150" /><el-table-column
          prop="name"
          label="任务名称"
          min-width="140" /><el-table-column prop="quantity" label="目标" /><el-table-column
          prop="producedQuantity"
          label="已产" /><el-table-column label="状态"
          ><template #default="{ row }">{{ orderStatus(row.status) }}</template></el-table-column
        ><el-table-column prop="startTime" label="计划开始" min-width="165" /></el-table
      ><el-button class="mt-16px" type="primary" @click="enterWorkbench"
        >进入工位工作台</el-button
      ></el-drawer
    >
  </div>
</template>

<script setup lang="ts">
import { formatDate } from '@/utils/formatTime'
import dayjs from 'dayjs'
import { Echart } from '@/components/Echart'
import type { EChartsOption } from 'echarts'
import { DashboardApi, type ProductionDashboard } from '@/api/mes/dashboard'
import ChinaDistributionMap from './components/ChinaDistributionMap.vue'
import { YUNSHU_EXAMPLE_CITIES } from './components/chinaDistribution'
import { useAeroThreeEffects } from './effects/useAeroThreeEffects'
import { useAeroAmbientEffects } from './effects/useAeroAmbientEffects'
import './effects/aero-effects.scss'
defineOptions({ name: 'MesProductionDashboard' })
const router = useRouter()
const screen = ref<HTMLElement>()
useAeroThreeEffects(screen)
useAeroAmbientEffects(screen)
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
const format = (n: unknown) => Number(n || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const progress = (produced: number, target: number) =>
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
    note: `${data.value?.taskCount || 0} 项当日交叠任务`,
    tone: ''
  },
  {
    label: '完成报工产量',
    value: format(data.value?.produced),
    note: `${data.value?.feedbackCount || 0} 笔已完成报工`,
    tone: 'teal'
  },
  {
    label: '报工合格率',
    value: ratio.value,
    note: `合格 ${format(data.value?.qualified)} / 报工 ${format(data.value?.produced)}`,
    tone: 'teal'
  },
  {
    label: '过程待检单',
    value: format(data.value?.pendingInspection),
    note: 'IPQC草稿待处理',
    tone: 'amber'
  },
  {
    label: '活动安灯',
    value: format(data.value?.activeAndon),
    note: '尚未处置的安灯事件',
    tone: 'rose'
  },
  {
    label: '缺料工单',
    value: data.value?.shortageAvailable ? format(data.value.shortageWorkOrderCount) : '—',
    note: data.value?.shortageAvailable ? '全厂当前 · 不受日期/工位筛选' : '齐套分析暂不可用',
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
      radius: ['45%', '72%'],
      label: { color: '#c6d8ec' },
      data:
        data.value?.devices.map((d) => ({
          name: deviceStatus(d.status),
          value: Number(d.count)
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
    color: ['#62a5ff', '#35d5c0'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { color: '#a5bbd4' } },
    grid: { left: 65, right: 30, top: 45, bottom: 40 },
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
    { id: number; name: string; count: number; produced: number; target: number }
  >()
  data.value?.tasks.forEach((t) => {
    const s = result.get(t.workstationId) || {
      id: t.workstationId,
      name:
        data.value?.workstations.find((s) => s.id === t.workstationId)?.name ||
        `工位 ${t.workstationId}`,
      count: 0,
      produced: 0,
      target: 0
    }
    s.count++
    s.produced += Number(t.producedQuantity || 0)
    s.target += Number(t.quantity || 0)
    result.set(t.workstationId, s)
  })
  return [...result.values()]
})
const flowStations = computed(() => stationTasks.value.slice(0, 5))
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
    now.value = new Date()
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
  position: fixed;
  z-index: 2000;
  min-width: 760px;
  padding: 22px 30px;
  overflow: auto;
  font-family: Inter, 'Microsoft YaHei', sans-serif;
  color: #dce8f6;
  background: radial-gradient(ellipse at 50% 0, #142c49 0, #0b182a 50%, #07111f 100%);
  inset: 0;
}

.screen-header,
.screen-controls,
.screen-main,
.screen-error {
  position: relative;
  z-index: 1;
}

.production-screen :deep(.aero-ambient-canvas) {
  position: fixed;
  z-index: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: 0.32;
  inset: 0;
}

.header-light-beam {
  position: absolute;
  top: 0;
  left: 20%;
  width: 60%;
  height: 2px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, #67edff, transparent);
  box-shadow: 0 0 22px #20cfff;
}

.screen-header {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  align-items: center;
  padding-bottom: 20px;
  border-bottom: 1px solid #26405e;
}

.brand {
  display: flex;
  gap: 12px;
  align-items: center;
}

.brand-mark {
  width: 42px;
  height: 42px;
  object-fit: contain;
}

.brand-name,
.brand-caption {
  display: block;
}

.brand-name {
  font-size: 23px;
  font-weight: 750;
  letter-spacing: 1px;
}

.brand-caption {
  margin-top: 4px;
  font-size: 11px;
  letter-spacing: 2px;
  color: #92a9c3;
}

.screen-heading {
  text-align: center;
}

.screen-heading h1 {
  margin: 0 0 8px;
  font-size: 27px;
  letter-spacing: 6px;
}

.screen-heading > span {
  font-size: 10px;
  letter-spacing: 3px;
  color: #6d92b7;
}

.screen-clock {
  text-align: right;
}

.screen-clock strong,
.screen-clock span {
  display: block;
}

.screen-clock strong {
  font-size: 26px;
  font-variant-numeric: tabular-nums;
}

.screen-clock span {
  margin-top: 4px;
  font-size: 12px;
  color: #92a9c3;
}

.screen-controls,
.filter-controls,
.refresh-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.screen-controls {
  justify-content: space-between;
  margin: 20px 0;
}

.filter-controls .el-select {
  width: 160px;
}

.filter-controls :deep(.el-date-editor) {
  width: 150px;
}

.live-state {
  font-size: 11px;
  color: #35d5c0;
}

.live-state.failed {
  color: #ff8095;
}

.refresh-controls :deep(.el-switch__label) {
  color: #a3b8d0;
}

.screen-error {
  padding: 12px;
  margin-bottom: 16px;
  color: #ff9cac;
  background: #381d30;
  border: 1px solid #693449;
  border-radius: 7px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

.kpi-card {
  padding: 20px;
  background: linear-gradient(130deg, #17314b, #102137);
  border: 1px solid #2b4866;
  border-radius: 9px;
}

.kpi-card > span {
  font-size: 12px;
  color: #a6bdd6;
}

.kpi-card strong {
  display: block;
  margin: 14px 0 10px;
  font-size: clamp(24px, 2.5vw, 38px);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.kpi-card small {
  font-size: 10px;
  color: #7895b3;
}

.kpi-card.teal strong {
  color: #35d5c0;
}

.kpi-card.amber strong {
  color: #f3b85f;
}

.kpi-card.rose strong {
  color: #fb819c;
}

.screen-grid {
  display: grid;
  grid-template-columns: 1.65fr 1fr;
  gap: 18px;
  margin-bottom: 18px;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(250px, 0.95fr) minmax(380px, 1.65fr) minmax(230px, 0.9fr);
  gap: 16px;
  margin-bottom: 18px;
}

.production-column,
.operations-column {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 16px;
}

.map-panel {
  display: flex;
  flex-direction: column;
}

.map-panel :deep(.china-distribution) {
  flex: 1;
}

.map-panel .panel-heading {
  flex-wrap: wrap;
  gap: 8px;
}

.map-panel :deep(.el-switch__label) {
  color: #f3b85f;
}

.map-coverage,
.flow-note {
  margin: 10px 0 0;
  font-size: 10px;
  line-height: 1.7;
  color: #7895b3;
}

.hero-summary {
  display: flex;
  min-height: 158px;
  align-items: center;
  justify-content: space-between;
  overflow: hidden;
  gap: 18px;
}

.hero-copy {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.hero-copy > span,
.hero-copy strong,
.hero-copy small {
  display: block;
}

.hero-copy > span {
  font-size: 12px;
  color: #a6bdd6;
}

.hero-copy strong {
  margin: 12px 0;
  font-size: clamp(28px, 3vw, 44px);
  line-height: 1;
  color: #62efff;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 0 18px #1b9ec16b;
}

.hero-copy small {
  font-size: 10px;
  line-height: 1.7;
  color: #7895b3;
}

.hero-orbit {
  --aero-orbit-size: 86px;

  margin-right: 16px;
}

.orbit-core {
  font-size: 9px;
  line-height: 1.6;
}

.orbit-core b {
  color: #d0ffff;
}

.flow-strip {
  position: relative;
  display: flex;
  min-height: 112px;
  padding: 10px 0 6px;
  align-items: flex-start;
  justify-content: space-around;
}

.topology-station {
  display: grid;
  width: 64px;
  min-width: 0;
  padding: 4px 0;
  color: inherit;
  text-align: center;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 5px;
  justify-items: center;
  gap: 10px;
}

.topology-station:focus-visible {
  outline: 2px solid #62a5ff;
  outline-offset: 3px;
}

.station-node {
  position: relative;
  display: grid;
  width: 30px;
  height: 30px;
  font-size: 21px;
  color: #55e5ff;
  background: #10354f;
  border: 1px solid #207c99;
  border-radius: 50%;
  place-items: center;
}

.topology-station strong {
  max-width: 64px;
  overflow: hidden;
  font-size: 10px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topology-station small {
  font-size: 9px;
  color: #7895b3;
}

.topology-link {
  margin-top: 8px;
  border-bottom: 1px solid #216482;
}

.safe-monitor {
  position: relative;
  display: flex;
  min-height: 148px;
  padding: 18px 10px;
  align-items: center;
  gap: 18px;
}

.safe-copy strong,
.safe-copy span {
  display: block;
}

.safe-copy strong {
  font-size: 13px;
  color: #75f5c7;
}

.safe-copy span {
  margin-top: 8px;
  font-size: 10px;
  line-height: 1.8;
  color: #7895b3;
}

.andon-alert-summary {
  display: grid;
  min-height: 148px;
  padding: 12px 0;
  text-align: center;
  place-content: center;
  gap: 8px;
}

.alert-code {
  display: flex;
  font-size: 12px;
  color: #f3b85f;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.andon-alert-summary strong {
  font-size: 38px;
  line-height: 1;
  color: #fb819c;
}

.andon-alert-summary > span:last-child {
  font-size: 10px;
  color: #92a9c3;
}

.exception-totals {
  display: flex;
  padding-top: 16px;
  margin-top: 12px;
  font-size: 10px;
  color: #92a9c3;
  border-top: 1px solid #1c314a;
  justify-content: space-between;
  gap: 12px;
}

.exception-totals b {
  margin-left: 6px;
  font-size: 15px;
  color: #f3b85f;
}

.screen-panel {
  min-width: 0;
  padding: 20px;
  background: #0d1d31e8;
  border: 1px solid #253d59;
  border-radius: 9px;
}

.panel-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.panel-heading h2 {
  padding-left: 10px;
  margin: 0;
  font-size: 15px;
  border-left: 3px solid #35d5c0;
}

.panel-heading > span {
  font-size: 10px;
  color: #7895b3;
}

.screen-empty {
  display: grid;
  min-height: 220px;
  font-size: 14px;
  color: #7895b3;
  place-items: center;
}

.device-legend {
  display: flex;
  font-size: 12px;
  color: #92a9c3;
  justify-content: center;
  gap: 20px;
}

.device-legend b {
  margin-left: 8px;
  color: #dce8f6;
}

.order-row,
.station-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: center;
  gap: 20px;
  padding: 13px 0;
  border-bottom: 1px solid #1c314a;
}

.order-row strong,
.order-row small,
.station-row strong,
.station-row small {
  display: block;
}

.order-row strong,
.station-row strong {
  font-size: 12px;
  font-weight: 500;
  color: #dce8f6;
}

.order-row small,
.station-row small {
  margin-top: 5px;
  font-size: 10px;
  color: #7895b3;
}

.order-progress > span {
  font-size: 10px;
  color: #92a9c3;
}

.screen-panel :deep(.el-progress__text) {
  font-size: 11px !important;
  color: #a6bdd6;
}

.screen-panel :deep(.el-progress-bar__outer) {
  background: #233c58;
}

.station-row {
  width: 100%;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-bottom: 1px solid #1c314a;
  grid-template-columns: 1.2fr 1fr 50px;
}

.station-row:hover {
  background: #142c45;
}

.station-row > span {
  font-size: 10px;
  color: #62a5ff;
}

.screen-footer {
  display: flex;
  padding-bottom: 10px;
  font-size: 10px;
  line-height: 1.8;
  color: #6c89a6;
  justify-content: space-between;
  gap: 24px;
}

.screen-footer strong {
  flex-shrink: 0;
  font-weight: 400;
}

@media (width <= 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(3, 1fr);
  }

  .screen-grid {
    grid-template-columns: 1fr;
  }

  .screen-heading h1 {
    font-size: 21px;
  }

  .screen-footer {
    flex-direction: column;
    gap: 6px;
  }
}

@media (width <= 1100px) {
  .overview-grid {
    grid-template-columns: minmax(250px, 0.9fr) minmax(380px, 1.4fr);
  }

  .operations-column {
    display: grid;
    grid-template-columns: 1fr 1fr;
    grid-column: 1 / -1;
  }
}

@media (width <= 800px) {
  .production-screen {
    min-width: 0;
    padding: 16px;
  }

  .screen-header {
    grid-template-columns: 1fr auto;
    gap: 18px;
  }

  .screen-heading {
    order: 3;
    grid-column: 1 / -1;
  }

  .overview-grid,
  .operations-column {
    grid-template-columns: 1fr;
  }

  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kpi-card,
  .screen-panel {
    padding: 16px;
  }

  .panel-heading {
    gap: 10px;
    flex-wrap: wrap;
  }

  .device-legend {
    flex-wrap: wrap;
  }
}
</style>
