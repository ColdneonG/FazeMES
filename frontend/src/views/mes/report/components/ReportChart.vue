<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import echarts from '@/plugins/echarts'
import type { EChartsOption } from 'echarts'
import type { ReportBlock, ReportPreview } from '@/api/mes/report'
import { resultColumn } from '../designerModel'

const props = defineProps<{ block: ReportBlock; result?: ReportPreview }>()
const host = ref<HTMLElement>()
let chart: ReturnType<typeof echarts.init> | undefined
let observer: ResizeObserver | undefined
let resizeFrame = 0
const hasData = computed(
  () =>
    !!props.result?.rows.length &&
    props.block.binding.metrics.some((field) => resultColumn(props.result, field))
)
const negativePie = computed(() => {
  if (props.block.type !== 'pie') return false
  const metric = resultColumn(props.result, props.block.binding.metrics[0])
  return (
    !!metric &&
    (props.result?.rows || [])
      .slice(0, props.block.binding.rowLimit)
      .some((row) => Number(row[metric.key]) < 0)
  )
})
const options = computed<EChartsOption>(() => {
  const rows = (props.result?.rows || []).slice(0, props.block.binding.rowLimit)
  const dimension = resultColumn(props.result, props.block.binding.dimension)
  const metrics = props.block.binding.metrics
    .map((field) => resultColumn(props.result, field))
    .filter((column): column is NonNullable<typeof column> => !!column)
  const labels = rows.map((row, index) =>
    dimension ? String(row[dimension.key] ?? '空') : String(index + 1)
  )
  const valueOf = (value: unknown) =>
    value === null || value === undefined || value === ''
      ? null
      : Number.isFinite(Number(value))
        ? Number(value)
        : null
  const base: EChartsOption = {
    animation: false,
    color: ['#147d91', '#42b7af', '#6d8fce', '#e9b963', '#a591c8'],
    // ECharts richText tooltip renders text inside the chart, avoiding HTML from business values.
    tooltip: {
      trigger: props.block.type === 'pie' ? 'item' : 'axis',
      renderMode: 'richText',
      confine: true
    },
    legend: { type: 'scroll', bottom: 0, textStyle: { color: '#607487', fontSize: 10 } },
    textStyle: { fontFamily: 'Microsoft YaHei, sans-serif', color: '#607487' }
  }
  if (props.block.type === 'pie')
    return {
      ...base,
      series: [
        {
          type: 'pie',
          radius: ['36%', '64%'],
          center: ['50%', '42%'],
          label: { fontSize: 10 },
          data: negativePie.value
            ? []
            : rows.flatMap((row, index) => {
                const value = valueOf(row[metrics[0]?.key])
                return value === null ? [] : [{ name: labels[index], value }]
              })
        }
      ]
    }
  return {
    ...base,
    grid: { left: 48, right: 14, top: 12, bottom: 46, containLabel: true },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { fontSize: 10, width: 65, overflow: 'truncate' },
      axisLine: { lineStyle: { color: '#d4e0e7' } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 10 },
      splitLine: { lineStyle: { color: '#edf2f5' } }
    },
    series: metrics.slice(0, 5).map((column) => ({
      name: column.title,
      type: props.block.type === 'line' ? 'line' : 'bar',
      barMaxWidth: 28,
      data: rows.map((row) => valueOf(row[column.key]))
    }))
  }
})
const render = () => {
  if (!host.value?.clientWidth || !host.value.clientHeight) return
  if (!chart) chart = echarts.init(host.value, undefined, { renderer: 'canvas' })
  chart.setOption(options.value, { notMerge: true })
  chart.resize()
}
const schedule = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    render()
  })
}
watch(options, schedule, { deep: true })
onMounted(() => {
  observer = new ResizeObserver(schedule)
  if (host.value) observer.observe(host.value)
  render()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  chart?.dispose()
})
</script>

<template>
  <div class="report-chart-wrap">
    <div ref="host" class="report-chart"></div>
    <div v-if="!hasData || negativePie" class="chart-empty">{{
      negativePie
        ? '环形图不支持负数，请改用柱状图或折线图'
        : result
          ? '当前条件下无可绘制数据'
          : '绑定数值字段后刷新真实数据'
    }}</div>
  </div>
</template>

<style scoped>
.report-chart-wrap {
  position: relative;
  width: 100%;
  height: 100%;
}

.report-chart {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: grid;
  font-size: 12px;
  color: #7e919d;
  pointer-events: none;
  place-items: center;
}
</style>
