<template>
  <el-card v-if="canQuery" v-loading="loading" shadow="never">
    <template #header>
      <div class="trend-heading">
        <span>生产趋势</span>
        <el-radio-group v-model="trendDays" size="small">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
      </div>
    </template>
    <el-empty v-if="failed" description="生产趋势加载失败">
      <el-button @click="loadData">重试</el-button>
    </el-empty>
    <Echart v-else-if="rows.length" :options="trendChartOptions" :height="300" />
    <el-empty v-else description="暂无生产趋势数据" :image-size="100" />
  </el-card>
</template>

<script setup lang="ts">
import type { EChartsOption } from 'echarts'
import { MesHomeStatisticsApi, type MesHomeProductionTrendVO } from '@/api/mes/home'
import { checkPermi } from '@/utils/permission'

defineOptions({ name: 'HomeProductionTrend' })

const props = withDefaults(defineProps<{ refreshToken?: number }>(), { refreshToken: 0 })
const canQuery = computed(() => checkPermi(['mes:home:query']))
const trendDays = ref(7)
const loading = ref(false)
const failed = ref(false)
const rows = ref<MesHomeProductionTrendVO[]>([])
const trendChartOptions = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
  legend: { data: ['产量', '合格品', '不良品'], bottom: 0 },
  grid: { left: 50, right: 20, top: 20, bottom: 40 },
  xAxis: {
    type: 'category',
    data: rows.value.map((row) => row.date.substring(5)),
    boundaryGap: false
  },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: '产量',
      type: 'line',
      smooth: true,
      data: rows.value.map((row) => Number(row.quantity)),
      itemStyle: { color: '#409eff' },
      areaStyle: { color: 'rgba(64,158,255,0.15)' }
    },
    {
      name: '合格品',
      type: 'line',
      smooth: true,
      data: rows.value.map((row) => Number(row.qualifiedQuantity)),
      itemStyle: { color: '#67c23a' }
    },
    {
      name: '不良品',
      type: 'line',
      smooth: true,
      data: rows.value.map((row) => Number(row.unqualifiedQuantity)),
      itemStyle: { color: '#f56c6c' }
    }
  ]
}))

let requestVersion = 0
const loadData = async () => {
  if (!canQuery.value) return
  const version = ++requestVersion
  loading.value = true
  failed.value = false
  try {
    const data = await MesHomeStatisticsApi.getProductionTrend(trendDays.value)
    if (version === requestVersion) rows.value = data
  } catch {
    if (version === requestVersion) {
      rows.value = []
      failed.value = true
    }
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

watch([trendDays, () => props.refreshToken], loadData, { immediate: true })
</script>

<style scoped lang="scss">
.trend-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
