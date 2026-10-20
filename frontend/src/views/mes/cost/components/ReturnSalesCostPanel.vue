<template>
  <section v-loading="loading" class="return-cost">
    <el-button :disabled="loading || !canQuery" @click="load">刷新退货成本</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="cost">
      <h3>{{ cost.returnCode }} · 原始出库成本恢复</h3>
      <p
        >成本依据原销售出库时冻结的分摊凭据，保留材料、人工和制造费用分项。金额为人民币、不含税。</p
      >
      <el-alert
        v-if="!cost.posted"
        title="本单尚未上架，当前未生成实际退回成本。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-for="issue in cost.issues"
        :key="issue"
        :title="issue"
        type="warning"
        :closable="false"
      />
      <el-descriptions :column="2" border>
        <el-descriptions-item label="退回材料成本">{{
          money(cost.materialAmount)
        }}</el-descriptions-item>
        <el-descriptions-item label="退回人工成本">{{
          money(cost.laborAmount)
        }}</el-descriptions-item>
        <el-descriptions-item label="退回制造费用">{{
          money(cost.overheadAmount)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际退回成本">{{
          money(cost.costAmount)
        }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="cost.lines" border>
        <el-table-column prop="itemCode" label="物料编码" min-width="130" />
        <el-table-column prop="itemName" label="物料名称" min-width="130" />
        <el-table-column prop="originSalesCode" label="原销售出库单" min-width="150" />
        <el-table-column prop="originTransactionId" label="原流水ID" width="100" />
        <el-table-column label="退货行数量" min-width="140"
          ><template #default="{ row }">{{ costNumber(row.quantity) }}</template></el-table-column
        >
        <el-table-column label="已上架数量" min-width="140"
          ><template #default="{ row }">{{
            costNumber(row.postedQuantity)
          }}</template></el-table-column
        >
        <el-table-column
          v-for="column in partColumns"
          :key="column.key"
          :label="column.label"
          min-width="140"
          ><template #default="{ row }">{{ money(row[column.key]) }}</template></el-table-column
        >
        <el-table-column label="核算提示" min-width="220"
          ><template #default="{ row }">{{
            row.issues?.join('；') || (row.status === 'COMPLETE' ? '依据完整' : '依据待核定')
          }}</template></el-table-column
        >
      </el-table>
      <h3>对应原出库的累计退回情况</h3>
      <el-table :data="cost.originals" border>
        <el-table-column prop="salesCode" label="原销售单" min-width="150" />
        <el-table-column prop="originTransactionId" label="原流水ID" width="100" />
        <el-table-column prop="itemCode" label="物料编码" min-width="130" />
        <el-table-column
          v-for="column in originalQuantityColumns"
          :key="column.key"
          :label="column.label"
          min-width="140"
          ><template #default="{ row }">{{
            costNumber(row[column.key])
          }}</template></el-table-column
        >
        <el-table-column
          v-for="column in originalAmountColumns"
          :key="column.key"
          :label="column.label"
          min-width="150"
          ><template #default="{ row }">{{ money(row[column.key]) }}</template></el-table-column
        >
        <el-table-column label="核算提示" min-width="220"
          ><template #default="{ row }">{{
            row.issues?.join('；') || (row.status === 'COMPLETE' ? '依据完整' : '依据待核定')
          }}</template></el-table-column
        >
      </el-table>
      <p
        >累计退回覆盖关联原流水的全部已入库退货。缺少原始成本时显示“待核定”；退款和收入需另有业务凭据。</p
      >
    </template>
  </section>
</template>
<script setup lang="ts">
import { CostApi, type ReturnSalesCost } from '@/api/mes/cost'
import { checkPermi } from '@/utils/permission'
import { money, costNumber } from '../format'
const props = defineProps<{ returnId: number }>()
const cost = ref<ReturnSalesCost>()
const loading = ref(false)
const error = ref('')
let version = 0
const canQuery = computed(() => checkPermi(['mes:cost:query']))
const partColumns = [
  { key: 'materialAmount', label: '材料成本' },
  { key: 'laborAmount', label: '人工成本' },
  { key: 'overheadAmount', label: '制造费用' },
  { key: 'costAmount', label: '退回成本合计' }
]
const originalQuantityColumns = [
  { key: 'outboundQuantity', label: '原出库数量' },
  { key: 'returnedQuantity', label: '累计已退数量' },
  { key: 'remainingQuantity', label: '尚未退回数量' }
]
const originalAmountColumns = [
  { key: 'originalCostAmount', label: '原始出库成本' },
  { key: 'returnedCostAmount', label: '累计退回成本' },
  { key: 'remainingCostAmount', label: '尚未退回成本' }
]
async function load() {
  const request = ++version,
    id = props.returnId
  cost.value = undefined
  error.value = ''
  loading.value = false
  if (!id || !canQuery.value) return
  loading.value = true
  try {
    const result = await CostApi.getReturnSales(id)
    if (request !== version || id !== props.returnId || !canQuery.value) return
    if (result.returnId !== id) throw new Error('document changed')
    cost.value = result
  } catch {
    if (request === version) {
      cost.value = undefined
      error.value = '无法读取当前退货成本，请核对单据和成本查询权限后重试'
    }
  } finally {
    if (request === version) loading.value = false
  }
}
watch(
  () => [props.returnId, canQuery.value],
  () => {
    void load()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  version++
})
</script>
<style scoped>
.return-cost {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 16px;
}

.return-cost p {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}
</style>
