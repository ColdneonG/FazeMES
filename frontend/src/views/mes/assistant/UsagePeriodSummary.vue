<template>
  <section class="usage-period">
    <h4
      >{{ title }} <small>{{ period.start }} 至 {{ period.end }}（不含）</small></h4
    >
    <el-alert
      v-if="period.blocked"
      :title="period.blockReason || '当前额度不足或存在待核定用量'"
      type="warning"
      :closable="false"
    />
    <el-table :data="metrics" size="small" border>
      <el-table-column prop="name" label="项目" min-width="145" />
      <el-table-column label="上限" min-width="110"
        ><template #default="{ row }">{{ limitText(row.metric.limit) }}</template></el-table-column
      >
      <el-table-column prop="metric.used" label="已计量" min-width="100" />
      <el-table-column prop="metric.reserved" label="调用中预留" min-width="100" />
      <el-table-column label="待核定占用" min-width="110"
        ><template #default="{ row }">{{ measured(row.metric.unknown) }}</template></el-table-column
      >
      <el-table-column label="可用" min-width="110"
        ><template #default="{ row }">{{
          row.metric.remaining == null
            ? row.metric.limit == null
              ? '未启用上限'
              : '待核定'
            : row.metric.remaining
        }}</template></el-table-column
      >
    </el-table>
    <p
      >未知Token调用：{{ period.unknownTokenCalls }}（聊天 {{ period.unknownChatTokenCalls }}，Jev
      {{ period.unknownJevTokenCalls }}）；全部模型实际Token合计：{{
        measured(period.actualTotalTokens)
      }}。</p
    >
    <p
      >未定价调用：{{ period.unpricedCalls }}；已有价格的待核定金额：¥{{
        period.knownUnknownAmount
      }}。金额按配置价格估算。{{
        period.usageComplete ? '已跟踪调用的用量完整。' : '已跟踪调用仍有未知用量或未定价记录。'
      }}{{ period.coverageComplete ? '' : '本周期包含计量启用前时段，历史用量未知。' }}</p
    >
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { UsagePeriod } from '@/api/mes/assistant/usage'
import { limitText, measured } from './usagePolicy'
const props = defineProps<{ title: string; period: UsagePeriod }>()
const metrics = computed(() => [
  { name: '调用次数（全部模型）', metric: props.period.calls },
  { name: '聊天模型Token', metric: props.period.tokens },
  { name: '人民币估算金额', metric: props.period.amount }
])
</script>
<style scoped>
h4 {
  margin-bottom: 10px;
}

small {
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

p {
  margin: 8px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
