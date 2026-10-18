<template>
  <ContentWrap v-if="!canQuery">
    <el-result
      icon="warning"
      title="暂无成本查询权限"
      sub-title="价格、成本和毛利属于内部经营数据，请联系管理员分配成本权限。"
    />
  </ContentWrap>
  <ContentWrap v-else class="cost-center">
    <div class="cost-heading"
      ><div
        ><h1>产品价格与成本中心</h1
        ><p>多级 BOM 标准成本、工单实际成本、采购价格与销售毛利 · 人民币 / 不含税</p></div
      ></div
    >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb-16px"
      title="标准、暂估和已结算成本分别展示。空缺依据保留“待核定”，明确录入的零值才显示为 0；所有核算结果以服务端保存的业务数据为准。"
    />
    <el-tabs v-model="tab">
      <el-tab-pane label="产品定价与多级 BOM" name="product" lazy
        ><ProductCostPanel :item-id="itemId"
      /></el-tab-pane>
      <el-tab-pane label="工单成本核算" name="workorder" lazy
        ><WorkorderCostPanel :workorder-id="workorderId"
      /></el-tab-pane>
      <el-tab-pane label="采购入库价格" name="receipt" lazy
        ><ReceiptCostPanel :receipt-id="receiptId"
      /></el-tab-pane>
      <el-tab-pane label="销售价格与毛利" name="sales" lazy
        ><SalesCostPanel :sales-id="salesId"
      /></el-tab-pane>
    </el-tabs>
  </ContentWrap>
</template>
<script setup lang="ts">
import { checkPermi } from '@/utils/permission'
import ProductCostPanel from './components/ProductCostPanel.vue'
import WorkorderCostPanel from './components/WorkorderCostPanel.vue'
import ReceiptCostPanel from './components/ReceiptCostPanel.vue'
import SalesCostPanel from './components/SalesCostPanel.vue'

defineOptions({ name: 'MesCost' })
const route = useRoute()
const canQuery = computed(() => checkPermi(['mes:cost:query']))
const validId = (value: unknown) => {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return undefined
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : undefined
}
const itemId = computed(() => validId(route.query.itemId))
const workorderId = computed(() => validId(route.query.workorderId))
const receiptId = computed(() => validId(route.query.receiptId))
const salesId = computed(() => validId(route.query.salesId))
const tab = ref('product')
watch(
  () => route.query,
  () => {
    tab.value = workorderId.value
      ? 'workorder'
      : receiptId.value
        ? 'receipt'
        : salesId.value
          ? 'sales'
          : 'product'
  },
  { immediate: true }
)
</script>
<style scoped lang="scss">
.cost-heading h1 {
  margin: 0 0 8px;
  font-size: 24px;
}

.cost-heading p {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary);
}

:deep(.cost-toolbar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

:deep(.cost-toolbar h2) {
  margin: 0;
  font-size: 20px;
}

:deep(.el-input-number) {
  max-width: 100%;
}
</style>
