<template>
  <el-form inline @submit.prevent>
    <el-form-item label="销售出库单"
      ><CostDocumentSelect v-model="selectedId" kind="sales" class="!w-360px" @change="load"
    /></el-form-item>
    <el-form-item
      ><el-button type="primary" :loading="loading" @click="load"
        >查询价格与毛利</el-button
      ></el-form-item
    >
  </el-form>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mb-16px" />
  <el-empty
    v-if="!sales && !loading"
    description="选择真实销售出库单查看成交价格与对应成本；示例售价不会生成虚拟销售数据"
  />
  <div v-loading="loading">
    <template v-if="sales && header">
      <div class="cost-toolbar">
        <h2
          >{{ header.code }} · {{ header.name }}
          <el-tag :type="sales.status === 'COMPLETE' ? 'success' : 'warning'">{{
            sales.status === 'COMPLETE' ? '核算完整' : '核算未完整'
          }}</el-tag></h2
        >
        <el-button
          @click="exportCostSnapshot(`${header.code}-销售毛利`, { document: header, cost: sales })"
          >导出销售核算</el-button
        >
      </div>
      <p
        >客户：{{ header.clientName || '—' }} · 销售订单：{{ header.salesOrderCode || '—' }} ·
        金额为人民币、不含税</p
      >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="mb-16px"
        title="成交价格须依据真实客户订单逐行录入，只能在草稿期修改。成本取实际出库时冻结的来源库存成本；缺失实际依据时不以标准成本补齐。"
      />
      <el-alert
        v-if="header.status !== MesWmProductSalesStatusEnum.FINISHED"
        type="warning"
        :closable="false"
        show-icon
        class="mb-16px"
        title="该单尚未完成销售出库。当前销售金额为约定价格金额，不表示已经完成交付或确认收入。"
      />
      <el-alert
        v-for="issue in sales.issues"
        :key="issue"
        :title="issue"
        type="warning"
        :closable="false"
        show-icon
        class="mb-8px"
      />
      <el-descriptions :column="3" border class="my-16px">
        <el-descriptions-item label="单据数量">{{
          costNumber(sales.quantity)
        }}</el-descriptions-item>
        <el-descriptions-item label="销售金额">{{ money(sales.salesAmount) }}</el-descriptions-item>
        <el-descriptions-item label="对应出库成本">{{
          money(sales.costAmount)
        }}</el-descriptions-item>
        <el-descriptions-item label="毛利">{{ money(sales.grossProfit) }}</el-descriptions-item>
        <el-descriptions-item label="毛利率">{{ margin(sales.grossMargin) }}</el-descriptions-item>
        <el-descriptions-item label="单据状态"
          ><dict-tag :type="DICT_TYPE.MES_WM_PRODUCT_SALES_STATUS" :value="header.status"
        /></el-descriptions-item>
      </el-descriptions>
      <el-table :data="sales.lines" border>
        <el-table-column prop="lineId" label="行号" width="80" />
        <el-table-column prop="itemCode" label="产品编码" min-width="150" />
        <el-table-column prop="itemName" label="产品名称" min-width="180" />
        <el-table-column label="数量" min-width="100"
          ><template #default="{ row }">{{ costNumber(row.quantity) }}</template></el-table-column
        >
        <el-table-column label="成交单价" min-width="120"
          ><template #default="{ row }">{{ money(row.unitPrice) }}</template></el-table-column
        >
        <el-table-column label="销售金额" min-width="130"
          ><template #default="{ row }">{{ money(row.salesAmount) }}</template></el-table-column
        >
        <el-table-column label="出库成本" min-width="130"
          ><template #default="{ row }">{{ money(row.costAmount) }}</template></el-table-column
        >
        <el-table-column label="毛利" min-width="120"
          ><template #default="{ row }">{{ money(row.grossProfit) }}</template></el-table-column
        >
        <el-table-column label="毛利率" min-width="100"
          ><template #default="{ row }">{{ margin(row.grossMargin) }}</template></el-table-column
        >
        <el-table-column label="核算提示" min-width="220"
          ><template #default="{ row }"
            ><span v-if="row.issues?.length">{{ row.issues.join('；') }}</span
            ><el-tag v-else :type="row.status === 'COMPLETE' ? 'success' : 'warning'">{{
              row.status === 'COMPLETE' ? '完整' : '未完整'
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column
          v-if="
            header.status === MesWmProductSalesStatusEnum.PREPARE && checkPermi(['mes:cost:update'])
          "
          label="定价"
          width="100"
          fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="priceDialog?.open('sales', row)"
              >录入成交价</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </template>
  </div>
  <LinePriceDialog ref="priceDialog" @saved="load" />
</template>
<script setup lang="ts">
import { CostApi, type SalesCost } from '@/api/mes/cost'
import { WmProductSalesApi, type WmProductSalesVO } from '@/api/mes/wm/productsales'
import { checkPermi } from '@/utils/permission'
import { DICT_TYPE } from '@/utils/dict'
import { MesWmProductSalesStatusEnum } from '@/views/mes/utils/constants'
import CostDocumentSelect from './CostDocumentSelect.vue'
import LinePriceDialog from './LinePriceDialog.vue'
import { costNumber, money, margin, exportCostSnapshot } from '../format'

const props = defineProps<{ salesId?: number }>()
const selectedId = ref<number>()
const sales = ref<SalesCost>()
const header = ref<WmProductSalesVO>()
const loading = ref(false)
const error = ref('')
const priceDialog = ref<InstanceType<typeof LinePriceDialog>>()
let loadVersion = 0
const load = async () => {
  const version = ++loadVersion
  const id = selectedId.value
  sales.value = undefined
  header.value = undefined
  error.value = ''
  loading.value = false
  if (!id) return
  loading.value = true
  try {
    const [document, result] = await Promise.all([
      WmProductSalesApi.getProductSales(id),
      CostApi.getSales(id)
    ])
    if (version !== loadVersion) return
    header.value = document
    sales.value = result
  } catch {
    if (version === loadVersion) error.value = '销售价格与成本加载失败，请重试。'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
watch(
  () => props.salesId,
  (id) => {
    selectedId.value = id
    load()
  },
  { immediate: true }
)
</script>
