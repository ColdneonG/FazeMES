<template>
  <el-card shadow="never" class="h-full">
    <template #header>待办与异常</template>
    <div class="alert-items">
      <el-button
        v-for="item in alertItems"
        :key="item.label"
        class="alert-item"
        text
        @click="emit('navigate', item.path)"
      >
        <Icon
          :icon="item.icon"
          :size="22"
          :color="item.count ? 'var(--el-color-warning)' : 'var(--el-color-primary)'"
        />
        <span class="alert-description">
          <span>{{ item.label }}</span>
          <span class="alert-hint">{{ item.desc }}</span>
        </span>
        <el-tag :type="item.count ? 'warning' : 'info'" effect="light">{{ item.count }}</el-tag>
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import type { MesHomeSummaryVO } from '@/api/mes/home'

defineOptions({ name: 'HomeAlertPanel' })

const props = defineProps<{
  summary: MesHomeSummaryVO
  destinations: Record<string, string>
}>()
const emit = defineEmits<{ navigate: [path: string] }>()

const alertItems = computed(() =>
  [
    {
      label: '安灯报警',
      desc: '未处置的安灯呼叫',
      icon: 'ep:warning-filled',
      path: props.destinations.MesProAndonRecord,
      count: props.summary.andonActiveCount
    },
    {
      label: '设备维修',
      desc: '待处理的维修工单',
      icon: 'ep:set-up',
      path: props.destinations.MesDvRepair,
      count: props.summary.repairActiveCount
    },
    {
      label: '待排产工单',
      desc: '草稿状态的生产工单',
      icon: 'ep:document-checked',
      path: props.destinations.MesProWorkOrder,
      count: props.summary.workOrderPrepareCount
    }
  ].filter((item) => item.path)
)
</script>

<style scoped lang="scss">
.alert-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.alert-item {
  height: auto;
  padding: 18px 8px;
  margin: 0;

  :deep(> span) {
    display: flex;
    align-items: center;
    width: 100%;
    gap: 12px;
  }
}

.alert-description {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  font-size: 14px;
  text-align: left;
  white-space: normal;
}

.alert-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
