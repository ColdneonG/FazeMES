<template>
  <div class="kpi-grid mb-16px">
    <el-card v-for="item in cards" :key="item.label" shadow="never">
      <div class="kpi-heading">
        <div class="kpi-label">
          <Icon :icon="item.icon" :size="20" :color="item.color" />
          {{ item.label }}
        </div>
        <el-link
          v-if="item.path"
          :underline="false"
          type="primary"
          @click="emit('navigate', item.path)"
          >查看</el-link
        >
      </div>
      <div class="kpi-value" :style="{ color: item.color }">{{ item.value }}</div>
      <div class="kpi-hint">{{ item.hint }}</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import type { MesHomeSummaryVO } from '@/api/mes/home'
import { formatQuantity, quantityUnits } from '@/utils/mesQuantity'

defineOptions({ name: 'HomeKpiCards' })

const props = defineProps<{
  summary: MesHomeSummaryVO
  destinations: Record<string, string>
}>()
const emit = defineEmits<{ navigate: [path: string] }>()

const cards = computed(() => {
  const data = props.summary
  const qualified = quantityUnits(data.todayQualifiedQuantity)
  const unqualified = quantityUnits(data.todayUnqualifiedQuantity)
  const total = (qualified ?? 0n) + (unqualified ?? 0n)
  const known = qualified !== undefined && unqualified !== undefined
  return [
    {
      label: '进行中工单',
      icon: 'ep:document',
      color: '#409eff',
      value: data.workOrderActiveCount,
      hint: '待排产 ' + data.workOrderPrepareCount + ' · 已完成 ' + data.workOrderFinishedCount,
      path: props.destinations.MesProWorkOrder
    },
    {
      label: '今日产量',
      icon: 'ep:data-analysis',
      color: '#67c23a',
      value: formatQuantity(data.todayOutput) + ' 件',
      hint: '昨日产量 ' + formatQuantity(data.yesterdayOutput) + ' 件',
      path: props.destinations.MesProFeedback
    },
    {
      label: '报工合格率',
      icon: 'ep:circle-check',
      color: '#e6a23c',
      value:
        known && total > 0n
          ? (Number((qualified * 1000n + total / 2n) / total) / 10).toFixed(1) + '%'
          : '—',
      hint:
        known && total > 0n
          ? '今日合格 ' +
            formatQuantity(data.todayQualifiedQuantity) +
            ' · 不良 ' +
            formatQuantity(data.todayUnqualifiedQuantity)
          : '今日暂无报工质量数据',
      path: props.destinations.MesProFeedback
    },
    {
      label: '运行中设备',
      icon: 'ep:cpu',
      color: '#7c3aed',
      value: data.machineryProducing + ' / ' + data.machineryTotal,
      hint: '停机 ' + data.machineryStop + ' · 维护 ' + data.machineryMaintenance,
      path: props.destinations.MesDvMachinery
    }
  ]
})
</script>

<style scoped lang="scss">
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.kpi-heading,
.kpi-label {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kpi-heading {
  justify-content: space-between;
}

.kpi-label {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.kpi-value {
  margin: 18px 0 12px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.kpi-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

@media (width <= 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (width <= 600px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
