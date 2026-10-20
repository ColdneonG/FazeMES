<template>
  <div class="legacy-evidence">
    <el-collapse
      ><el-collapse-item title="查看本次查询的业务依据" name="evidence">
        <el-table
          :data="answerRows"
          max-height="360"
          size="small"
          empty-text="当前范围没有符合条件的业务记录"
        >
          <el-table-column
            v-if="answer.topic === 'risks'"
            prop="code"
            label="工单编码"
            min-width="150"
          />
          <el-table-column
            v-if="answer.topic === 'risks'"
            prop="productName"
            label="产品"
            min-width="140"
          />
          <el-table-column v-if="answer.topic === 'risks'" label="估算完工" min-width="160"
            ><template #default="{ row }">{{
              row.forecastAt ? fmt(row.forecastAt) : '资料不足，暂不能估算'
            }}</template></el-table-column
          >
          <el-table-column v-if="answer.topic === 'risks'" label="主要依据" min-width="220"
            ><template #default="{ row }">{{
              row.reasons?.map((r) => r.message).join('；') || '当前未发现风险'
            }}</template></el-table-column
          >
          <el-table-column v-if="answer.topic === 'quality'" prop="dimension" label="维度" />
          <el-table-column v-if="answer.topic === 'quality'" prop="value" label="分组" />
          <el-table-column v-if="answer.topic === 'quality'" prop="product" label="产品" />
          <el-table-column v-if="answer.topic === 'quality'" label="不良率"
            ><template #default="{ row }">{{ row.rate }}%</template></el-table-column
          >
          <el-table-column v-if="answer.topic === 'quality'" prop="quantity" label="样本数量" />
          <el-table-column v-if="answer.topic === 'flow'" prop="station" label="工作站" />
          <el-table-column v-if="answer.topic === 'flow'" prop="tasks" label="任务数" />
          <el-table-column v-if="answer.topic === 'flow'" prop="pending" label="未完成" />
          <el-table-column v-if="answer.topic === 'flow'" label="计划至首报间隔中位数"
            ><template #default="{ row }">{{
              row.medianWaitHours == null ? '尚无有效样本' : `${row.medianWaitHours} 小时`
            }}</template></el-table-column
          >
          <el-table-column
            v-if="answer.topic === 'incidents'"
            prop="title"
            label="异常"
            min-width="180"
          />
          <el-table-column v-if="answer.topic === 'incidents'" prop="ownerName" label="负责人" />
          <el-table-column v-if="answer.topic === 'incidents'" label="状态"
            ><template #default="{ row }">{{
              statusNames[String(row.status)]
            }}</template></el-table-column
          >
          <el-table-column v-if="answer.topic === 'incidents'" label="处理截止"
            ><template #default="{ row }">{{ fmt(row.dueAt) }}</template></el-table-column
          >
          <template v-if="answer.topic === 'cost'">
            <el-table-column prop="code" label="产品／工单编码" min-width="170" />
            <el-table-column prop="costBasis" label="核算口径" min-width="210" />
            <el-table-column prop="status" label="核算状态" min-width="110" />
            <el-table-column
              v-for="column in costColumns"
              :key="column.key"
              :label="column.label"
              min-width="130"
            >
              <template #default="{ row }">{{
                row[column.key] === undefined ? '—' : money(row[column.key])
              }}</template>
            </el-table-column>
            <el-table-column label="缺失依据" min-width="220">
              <template #default="{ row }">{{ row.issues?.join('；') || '无' }}</template>
            </el-table-column>
            <el-table-column label="核对原始记录" min-width="130">
              <template #default="{ row }">
                <el-button link type="primary" @click="openCostRecord(row)">成本明细</el-button>
              </template>
            </el-table-column>
          </template>
        </el-table>
        <el-button link type="primary" @click="showEvidence">前往分析页查看原始记录</el-button>
      </el-collapse-item></el-collapse
    >
    <div v-for="suggestion in answer.suggestions" :key="suggestion.title" class="suggestion"
      ><div
        ><strong>{{ suggestion.title }}</strong
        ><p>{{ suggestion.content }}</p></div
      ><el-button v-hasPermi="['mes:insight:manage']" @click="emit('draft', suggestion)"
        >编辑并保存建议草稿</el-button
      ></div
    >
  </div>
</template>
<script setup lang="ts">
import type { Answer, Draft } from '@/api/mes/insight'
import { money } from '@/views/mes/cost/format'
import { openCostCenter } from '@/views/mes/cost/navigation'
const props = defineProps<{ answer: Answer }>()
const emit = defineEmits<{ draft: [value: Draft]; navigate: [topic: string] }>()
const router = useRouter()
const fmt = (value: unknown) => (value ? String(value).replace('T', ' ').slice(0, 19) : '—')
const statusNames: Record<string, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  VERIFYING: '待验证',
  CLOSED: '已关闭'
}
const answerRows = computed(() => {
  const key =
    props.answer?.topic === 'cost'
      ? 'items'
      : props.answer?.topic === 'risks'
        ? 'orders'
        : props.answer?.topic === 'quality'
          ? 'groups'
          : props.answer?.topic === 'flow'
            ? 'stations'
            : 'cases'
  return ((props.answer.evidence as Record<string, unknown>)[key] || []) as Record<
    string,
    unknown
  >[]
})
function showEvidence() {
  if (props.answer?.topic === 'cost') {
    void openCostCenter(router, {})
    return
  }
  if (['risks', 'quality', 'flow', 'incidents'].includes(props.answer.topic))
    emit('navigate', props.answer.topic)
}
function openCostRecord(row: Record<string, unknown>) {
  const id = Number(row.recordId)
  if (!Number.isSafeInteger(id) || id <= 0) return
  void openCostCenter(router, row.sourceType === 'WORKORDER' ? { workorderId: id } : { itemId: id })
}
const costColumns = [
  { key: 'totalCost', label: '标准单位／实际总额' },
  { key: 'planTotalCost', label: '工单冻结计划总额' },
  { key: 'materialCost', label: '材料成本' },
  { key: 'laborCost', label: '人工成本' },
  { key: 'overheadCost', label: '制造费用' },
  { key: 'salePrice', label: '产品参考售价' }
]
</script>
<style scoped>
.suggestion {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
}

.suggestion p {
  white-space: pre-wrap;
}
</style>
