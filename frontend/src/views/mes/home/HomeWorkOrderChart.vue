<template>
  <el-card v-if="canQuery" v-loading="loading" shadow="never" class="h-full">
    <template #header>工单状态分布</template>
    <el-empty v-if="failed" description="工单状态加载失败">
      <el-button @click="loadData">重试</el-button>
    </el-empty>
    <Echart v-else-if="hasData" :options="workOrderChartOptions" :height="280" />
    <el-empty v-else description="暂无工单数据" :image-size="100" />
  </el-card>
</template>

<script setup lang="ts">
import type { EChartsOption } from 'echarts'
import { MesHomeStatisticsApi, type MesHomeWorkOrderStatusVO } from '@/api/mes/home'
import { MesProWorkOrderStatusEnum } from '@/views/mes/utils/constants'
import { checkPermi } from '@/utils/permission'

defineOptions({ name: 'HomeWorkOrderChart' })

const props = withDefaults(defineProps<{ refreshToken?: number }>(), { refreshToken: 0 })
const canQuery = computed(() => checkPermi(['mes:home:query']))
const loading = ref(false)
const failed = ref(false)
const rows = ref<MesHomeWorkOrderStatusVO[]>([])
const hasData = computed(() => rows.value.some((row) => row.count > 0))
const statusColorMap: Record<number, string> = {
  [MesProWorkOrderStatusEnum.PREPARE]: '#909399',
  [MesProWorkOrderStatusEnum.CONFIRMED]: '#409eff',
  [MesProWorkOrderStatusEnum.FINISHED]: '#67c23a',
  [MesProWorkOrderStatusEnum.CANCELED]: '#f56c6c'
}
const workOrderChartOptions = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0, type: 'scroll' },
  series: [
    {
      type: 'pie',
      radius: ['40%', '70%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}\n{c}' },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
      data: rows.value.map((row) => ({
        name: row.statusName,
        value: row.count,
        itemStyle: { color: statusColorMap[row.status] || '#409eff' }
      }))
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
    const data = await MesHomeStatisticsApi.getWorkOrderStatusDistribution()
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

watch(() => props.refreshToken, loadData, { immediate: true })
</script>
