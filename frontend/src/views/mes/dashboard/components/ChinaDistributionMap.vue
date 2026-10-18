<script setup lang="ts">
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch
} from 'vue'
import * as echarts from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { BarChart, EffectScatterChart, MapChart, ScatterChart } from 'echarts/charts'
import { AriaComponent, GeoComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  escapeChartText,
  normalizeCityDistribution,
  type CityDistributionPoint
} from './chinaDistribution'

defineOptions({ name: 'ChinaDistributionMap' })

// The shared Echart wrapper does not register Geo / EffectScatter. Keep this dependency local.
echarts.use([
  BarChart,
  EffectScatterChart,
  MapChart,
  ScatterChart,
  AriaComponent,
  GeoComponent,
  GridComponent,
  TooltipComponent,
  CanvasRenderer
])

const props = withDefaults(
  defineProps<{
    cities?: readonly CityDistributionPoint[]
    metricLabel?: string
    unit?: string
    sourceLabel?: string
    emptyText?: string
    /** Must be true whenever the caller supplies demonstration data. */
    isExample?: boolean
    rankingLimit?: number
  }>(),
  {
    cities: () => [],
    metricLabel: '客户数',
    unit: '家',
    sourceLabel: '城市分布数据尚未接入',
    emptyText: '暂无可定位的城市数据',
    isExample: false,
    rankingLimit: 8
  }
)

const rootRef = ref<HTMLElement>()
const mapRef = ref<HTMLElement>()
const rankingRef = ref<HTMLElement>()
const mapStatus = ref<'loading' | 'ready' | 'error'>('loading')
const reducedMotion = ref(false)
const pageVisible = ref(true)
const componentVisible = ref(true)
const active = ref(true)
const validCities = computed(() => normalizeCityDistribution(props.cities))
const locatedCities = computed(() => validCities.value.filter((city) => city.coordinate))
const rankingCities = computed(() => {
  const limit = Number.isFinite(props.rankingLimit)
    ? Math.max(1, Math.min(20, Math.floor(props.rankingLimit)))
    : 8
  return [...validCities.value]
    .sort((left, right) => right.value - left.value || left.name.localeCompare(right.name, 'zh-CN'))
    .slice(0, limit)
})
const motionEnabled = computed(
  () => !reducedMotion.value && pageVisible.value && componentVisible.value && active.value
)
const unlocatedCount = computed(() => validCities.value.length - locatedCities.value.length)
const formatValue = (value: number) => value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const mapName = 'fazemes-china-provinces'
let mapChart: echarts.EChartsType | undefined
let rankingChart: echarts.EChartsType | undefined
let resizeObserver: ResizeObserver | undefined
let intersectionObserver: IntersectionObserver | undefined
let motionQuery: MediaQueryList | undefined
let resizeFrame = 0
let mounted = false

const tooltipFormatter = (params: unknown) => {
  const parameter = (Array.isArray(params) ? params[0] : params) as
    | { name?: unknown; value?: unknown }
    | undefined
  if (!parameter) return ''
  const rawValue = Array.isArray(parameter.value) ? parameter.value[2] : parameter.value
  const title = escapeChartText(parameter.name)
  if (typeof rawValue !== 'number' || !Number.isFinite(rawValue)) return title
  const label = escapeChartText(props.metricLabel)
  const value = escapeChartText(formatValue(rawValue))
  const unit = escapeChartText(props.unit)
  return `${title}<br/>${label}：<strong>${value}</strong> ${unit}${props.isExample ? '<br/>示例数据' : ''}`
}

const chartTooltip = {
  trigger: 'item' as const,
  confine: true,
  backgroundColor: 'rgba(4, 20, 33, 0.96)',
  borderColor: 'rgba(6, 182, 212, 0.5)',
  textStyle: { color: '#e6f7ff', fontSize: 12 },
  formatter: tooltipFormatter
}

const mapBaseOption = (): EChartsOption => ({
  backgroundColor: 'transparent',
  tooltip: chartTooltip,
  geo: {
    map: mapName,
    roam: true,
    zoom: 1,
    scaleLimit: { min: 0.8, max: 5 },
    layoutCenter: ['50%', '48%'],
    layoutSize: '90%',
    label: { show: true, color: 'rgba(230, 247, 255, 0.53)', fontSize: 9 },
    itemStyle: {
      areaColor: 'rgba(6, 182, 212, 0.085)',
      borderColor: 'rgba(6, 182, 212, 0.5)',
      borderWidth: 0.9,
      shadowColor: 'rgba(6, 182, 212, 0.12)',
      shadowBlur: 10
    },
    emphasis: {
      label: { color: '#ecfeff' },
      itemStyle: { areaColor: 'rgba(6, 182, 212, 0.23)', borderColor: '#67e8f9' }
    }
  }
})

const renderMapData = () => {
  if (!mapChart) return
  const largestValue = Math.max(1, ...locatedCities.value.map((city) => city.value))
  mapChart.setOption(
    {
      animation: motionEnabled.value,
      aria: {
        enabled: true,
        label: {
          description: `中国城市${props.metricLabel}分布，${locatedCities.value.length}个已定位城市。${props.isExample ? '当前为示例数据。' : ''}`
        }
      },
      series: [
        {
          id: 'distribution-points',
          name: props.metricLabel,
          type: motionEnabled.value ? 'effectScatter' : 'scatter',
          coordinateSystem: 'geo',
          data: locatedCities.value.map((city) => ({
            name: city.name,
            value: [...city.coordinate!, city.value]
          })),
          symbolSize: (value: number[]) => 6 + 11 * Math.sqrt(value[2] / largestValue),
          itemStyle: { color: '#06b6d4', shadowBlur: 10, shadowColor: 'rgba(6, 182, 212, 0.5)' },
          rippleEffect: { brushType: 'stroke', scale: 2.5, period: 4 },
          emphasis: { scale: 1.3 },
          zlevel: 1
        }
      ]
    },
    { replaceMerge: ['series'] }
  )
}

const renderRanking = () => {
  if (!rankingChart) return
  rankingChart.setOption(
    {
      backgroundColor: 'transparent',
      animation: motionEnabled.value,
      animationDuration: 350,
      tooltip: chartTooltip,
      aria: { enabled: true },
      grid: { left: 8, right: 55, top: 5, bottom: 17, containLabel: true },
      xAxis: {
        type: 'value',
        min: 0,
        minInterval: 1,
        splitNumber: 2,
        axisLabel: {
          color: 'rgba(230, 247, 255, 0.42)',
          fontSize: 9,
          hideOverlap: true,
          formatter: (value: number) =>
            value >= 10000
              ? `${value / 10000}万`
              : value >= 1000
                ? `${value / 1000}千`
                : String(value)
        },
        splitLine: { lineStyle: { color: 'rgba(6, 182, 212, 0.08)' } }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: rankingCities.value.map((city) => city.name),
        axisTick: { show: false },
        axisLabel: {
          color: 'rgba(230, 247, 255, 0.78)',
          fontSize: 11,
          width: 72,
          overflow: 'truncate'
        },
        axisLine: { lineStyle: { color: 'rgba(6, 182, 212, 0.18)' } }
      },
      series: [
        {
          type: 'bar',
          name: props.metricLabel,
          data: rankingCities.value.map((city) => ({ name: city.name, value: city.value })),
          barMaxWidth: 10,
          showBackground: true,
          backgroundStyle: { color: 'rgba(6, 182, 212, 0.035)', borderRadius: 5 },
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
              { offset: 0, color: 'rgba(6, 182, 212, 0.3)' },
              { offset: 1, color: '#06b6d4' }
            ]),
            borderRadius: [0, 5, 5, 0]
          },
          label: {
            show: true,
            position: 'right',
            color: '#8edeea',
            fontSize: 10,
            formatter: (parameter) => formatValue(Number(parameter.value))
          }
        }
      ]
    },
    { notMerge: true }
  )
}

const ensureCharts = () => {
  if (!mounted || !active.value || !pageVisible.value || !componentVisible.value) return
  if (
    !mapChart &&
    mapStatus.value === 'ready' &&
    mapRef.value?.clientWidth &&
    mapRef.value.clientHeight
  ) {
    mapChart = echarts.init(mapRef.value)
    mapChart.setOption(mapBaseOption())
    renderMapData()
  }
  if (!rankingChart && rankingRef.value?.clientWidth && rankingRef.value.clientHeight) {
    rankingChart = echarts.init(rankingRef.value)
    renderRanking()
  }
}

const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = 0
  if (!mounted || !pageVisible.value || !active.value || !componentVisible.value) return
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    if (!mounted || !pageVisible.value || !active.value || !componentVisible.value) return
    ensureCharts()
    mapChart?.resize()
    rankingChart?.resize()
  })
}

const loadMap = async () => {
  mapStatus.value = 'loading'
  try {
    if (!echarts.getMap(mapName)) {
      // Local, same-origin GeoJSON only. No external geocoding or coordinate upload.
      const response = await fetch(`${import.meta.env.BASE_URL}maps/china-provinces.geo.json`)
      if (!response.ok) throw new Error('Local map unavailable')
      const geoJson = await response.json()
      if (geoJson?.type !== 'FeatureCollection' || !Array.isArray(geoJson.features)) {
        throw new Error('Invalid local GeoJSON')
      }
      if (!mounted) return
      echarts.registerMap(mapName, geoJson)
    }
    if (!mounted) return
    mapStatus.value = 'ready'
    await nextTick()
    scheduleResize()
  } catch {
    if (mounted) mapStatus.value = 'error'
  }
}

const syncPageVisibility = () => {
  pageVisible.value = !document.hidden
}
const syncMotionPreference = () => {
  reducedMotion.value = motionQuery?.matches ?? false
}

watch([validCities, () => props.metricLabel, () => props.unit, () => props.isExample], () => {
  renderMapData()
  renderRanking()
})
watch(() => props.rankingLimit, renderRanking)
// Visibility must initialize/resize charts independently of animation preference.
// In reduced-motion mode, motionEnabled remains false across foreground/viewport changes.
watch([pageVisible, componentVisible, active], scheduleResize)
watch(motionEnabled, () => {
  // Replacing effectScatter with scatter also stops the continuous ripple animator while hidden.
  renderMapData()
  renderRanking()
  scheduleResize()
})

onMounted(() => {
  mounted = true
  syncPageVisibility()
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  syncMotionPreference()
  motionQuery.addEventListener('change', syncMotionPreference)
  document.addEventListener('visibilitychange', syncPageVisibility)
  window.addEventListener('resize', scheduleResize)
  if (typeof ResizeObserver !== 'undefined' && rootRef.value) {
    resizeObserver = new ResizeObserver(scheduleResize)
    resizeObserver.observe(rootRef.value)
  }
  if (typeof IntersectionObserver !== 'undefined' && rootRef.value) {
    intersectionObserver = new IntersectionObserver(([entry]) => {
      componentVisible.value = entry.isIntersecting
    })
    intersectionObserver.observe(rootRef.value)
  }
  ensureCharts()
  void loadMap()
})
onActivated(() => {
  active.value = true
  scheduleResize()
})
onDeactivated(() => {
  active.value = false
})
onBeforeUnmount(() => {
  mounted = false
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeObserver?.disconnect()
  intersectionObserver?.disconnect()
  motionQuery?.removeEventListener('change', syncMotionPreference)
  document.removeEventListener('visibilitychange', syncPageVisibility)
  window.removeEventListener('resize', scheduleResize)
  mapChart?.dispose()
  rankingChart?.dispose()
  mapChart = undefined
  rankingChart = undefined
})
</script>

<template>
  <div ref="rootRef" class="china-distribution">
    <div class="distribution-caption">
      <span><i></i>{{ metricLabel }}分布</span>
      <strong v-if="isExample" class="example-label">示例数据</strong>
      <span v-else>{{ locatedCities.length }} 个已定位城市</span>
    </div>
    <div class="distribution-map">
      <div
        ref="mapRef"
        class="map-canvas"
        role="img"
        :aria-label="`中国省级地图，展示城市${metricLabel}分布，可拖动和缩放`"
      ></div>
      <div v-if="mapStatus === 'loading'" class="map-message" role="status">正在加载本地底图…</div>
      <div v-else-if="mapStatus === 'error'" class="map-message map-error" role="status">
        <span>本地地图加载失败</span>
        <button type="button" @click="loadMap">重试</button>
      </div>
      <div v-else-if="!locatedCities.length" class="map-message map-empty" role="status">
        <span>{{ emptyText }}</span>
        <small>省级底图已就绪 · 待接入城市与坐标</small>
      </div>
      <span v-if="mapStatus === 'ready'" class="map-interaction">滚轮缩放 · 拖动平移</span>
    </div>
    <div class="distribution-ranking">
      <div class="ranking-heading">
        <span>城市排行</span><span>{{ metricLabel }}{{ unit ? ` / ${unit}` : '' }}</span>
      </div>
      <div
        ref="rankingRef"
        class="ranking-canvas"
        :class="{ 'ranking-canvas-empty': !rankingCities.length }"
        role="img"
        :aria-label="`城市${metricLabel}排行`"
      ></div>
      <p v-if="!rankingCities.length" class="ranking-empty">暂无城市统计数据</p>
      <ol class="accessible-ranking" aria-label="城市排行明细">
        <li v-for="(city, index) in rankingCities" :key="`${city.name}-${index}`">
          {{ city.name }}：{{ formatValue(city.value) }} {{ unit }}
        </li>
      </ol>
    </div>
    <div class="distribution-source">
      <span>{{ sourceLabel }}</span>
      <span v-if="unlocatedCount">{{ unlocatedCount }} 个城市待定位，仅计入排行</span>
    </div>
  </div>
</template>

<style scoped>
.china-distribution {
  display: flex;
  height: 100%;
  min-height: 520px;
  color: #e6f7ff;
  background:
    radial-gradient(ellipse at 50% 30%, rgb(6 182 212 / 5%), transparent 65%),
    linear-gradient(180deg, rgb(0 5 10 / 12%), rgb(0 5 10 / 25%));
  flex-direction: column;
}

.distribution-caption,
.ranking-heading,
.distribution-source {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.distribution-caption {
  padding: 4px 10px;
  font-size: 10px;
  color: rgb(230 247 255 / 48%);
}

.distribution-caption > span:first-child {
  display: flex;
  align-items: center;
  gap: 6px;
}

.distribution-caption i {
  width: 5px;
  height: 5px;
  background: #06b6d4;
  border-radius: 50%;
  box-shadow: 0 0 8px rgb(6 182 212 / 60%);
}

.example-label {
  padding: 2px 7px;
  font-size: 10px;
  font-weight: 400;
  color: #fde68a;
  border: 1px solid rgb(250 204 21 / 28%);
  border-radius: 3px;
}

.distribution-map {
  position: relative;
  min-height: 260px;
  flex: 1.3 1 0;
}

.map-canvas {
  position: absolute;
  inset: 0;
}

.map-message {
  position: absolute;
  display: flex;
  font-size: 12px;
  color: #8eabb9;
  text-align: center;
  inset: 0;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
}

.map-empty {
  pointer-events: none;
}

.map-message > span {
  padding: 6px 10px;
  background: rgb(0 10 18 / 78%);
  border-radius: 3px;
}

.map-message small {
  font-size: 10px;
  color: #7894a4;
}

.map-error button {
  padding: 3px 12px;
  color: #8edeea;
  cursor: pointer;
  background: rgb(6 182 212 / 8%);
  border: 1px solid rgb(6 182 212 / 40%);
  border-radius: 3px;
}

.map-interaction {
  position: absolute;
  right: 10px;
  bottom: 0;
  font-size: 9px;
  color: rgb(230 247 255 / 32%);
  pointer-events: none;
}

.distribution-ranking {
  position: relative;
  display: flex;
  min-height: 195px;
  margin: 8px 9px 0;
  flex: 1 1 0;
  flex-direction: column;
  border-top: 1px solid rgb(6 182 212 / 13%);
}

.ranking-heading {
  padding: 9px 0 4px;
  font-size: 11px;
  color: rgb(230 247 255 / 72%);
}

.ranking-heading > span:last-child {
  font-size: 10px;
  color: rgb(230 247 255 / 40%);
}

.ranking-canvas {
  min-height: 160px;
  flex: 1;
}

.ranking-canvas-empty {
  opacity: 0.25;
}

.ranking-empty {
  position: absolute;
  display: grid;
  margin: 0;
  font-size: 11px;
  color: #7894a4;
  inset: 40px 0 0;
  place-items: center;
}

.distribution-source {
  padding: 5px 10px 8px;
  font-size: 9px;
  line-height: 1.5;
  color: rgb(230 247 255 / 35%);
  flex-wrap: wrap;
}

.accessible-ranking {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
