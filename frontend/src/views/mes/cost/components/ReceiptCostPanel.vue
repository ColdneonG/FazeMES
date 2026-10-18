<template>
  <el-form inline @submit.prevent>
    <el-form-item label="采购入库单"
      ><CostDocumentSelect
        v-model="selectedId"
        kind="receipt"
        class="!w-360px"
        @change="selectDocument"
    /></el-form-item>
    <el-form-item
      ><el-button type="primary" :loading="loading" @click="load">查询价格</el-button></el-form-item
    >
  </el-form>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mb-16px" />
  <el-empty v-if="!receipt && !loading" description="选择真实采购入库单，逐行录入实际采购单价" />
  <div v-loading="loading">
    <template v-if="receipt">
      <div class="cost-toolbar"
        ><h2>{{ receipt.code }} · {{ receipt.name }}</h2
        ><el-tag :type="editable ? 'warning' : 'info'">{{
          editable ? '草稿 · 价格可维护' : '价格已锁定'
        }}</el-tag></div
      >
      <p
        >供应商：{{ receipt.vendorName || '—' }} · 采购订单：{{
          receipt.purchaseOrderCode || '—'
        }}
        · 金额为人民币、不含税</p
      >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="mb-16px"
        title="实际单价仅属于这张采购单，提交后锁定。缺价显示待核定，不使用参考价格补成实际成本；后续参考调价不改写已发生的库存成本。"
      />
      <el-table :data="lines" border>
        <el-table-column prop="itemCode" label="物料编码" min-width="150" />
        <el-table-column prop="itemName" label="物料名称" min-width="200" />
        <el-table-column prop="batchCode" label="批次" min-width="130" />
        <el-table-column label="入库数量" min-width="120"
          ><template #default="{ row }"
            >{{ costNumber(row.receivedQuantity) }} {{ row.unitMeasureName }}</template
          ></el-table-column
        >
        <el-table-column label="实际采购单价" min-width="140"
          ><template #default="{ row }">{{
            money(row.price?.unitPrice)
          }}</template></el-table-column
        >
        <el-table-column label="实际采购金额" min-width="140"
          ><template #default="{ row }">{{ money(row.price?.amount) }}</template></el-table-column
        >
        <el-table-column label="定价依据" min-width="180" show-overflow-tooltip
          ><template #default="{ row }">{{ row.price?.remark || '—' }}</template></el-table-column
        >
        <el-table-column
          v-if="editable && checkPermi(['mes:cost:update'])"
          label="操作"
          width="110"
          fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" :disabled="row.price?.locked" @click="editPrice(row)"
              >录入单价</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <Pagination
        :total="total"
        v-model:page="pageNo"
        v-model:limit="pageSize"
        @pagination="load"
      />
    </template>
  </div>
  <LinePriceDialog ref="priceDialog" @saved="load" />
</template>
<script setup lang="ts">
import { CostApi, type CostDecimal, type LinePrice } from '@/api/mes/cost'
import { WmItemReceiptApi, type WmItemReceiptVO } from '@/api/mes/wm/itemreceipt'
import { WmItemReceiptLineApi, type WmItemReceiptLineVO } from '@/api/mes/wm/itemreceipt/line'
import { checkPermi } from '@/utils/permission'
import { MesWmItemReceiptStatusEnum } from '@/views/mes/utils/constants'
import CostDocumentSelect from './CostDocumentSelect.vue'
import LinePriceDialog from './LinePriceDialog.vue'
import { costNumber, money } from '../format'

const props = defineProps<{ receiptId?: number }>()
type ReceiptLine = WmItemReceiptLineVO & {
  price: (LinePrice & { amount?: CostDecimal; locked?: boolean }) | null
}
const selectedId = ref<number>()
const receipt = ref<WmItemReceiptVO>()
const lines = ref<ReceiptLine[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const priceDialog = ref<InstanceType<typeof LinePriceDialog>>()
const editable = computed(() => receipt.value?.status === MesWmItemReceiptStatusEnum.PREPARE)
let loadVersion = 0
const load = async () => {
  const version = ++loadVersion
  const id = selectedId.value
  receipt.value = undefined
  lines.value = []
  error.value = ''
  loading.value = false
  if (!id) return
  loading.value = true
  try {
    const [header, page] = await Promise.all([
      WmItemReceiptApi.getItemReceipt(id),
      WmItemReceiptLineApi.getItemReceiptLinePage({
        receiptId: id,
        pageNo: pageNo.value,
        pageSize: pageSize.value
      })
    ])
    const pricedLines = await Promise.all(
      (page.list as WmItemReceiptLineVO[]).map(async (line) => ({
        ...line,
        price: await CostApi.getReceiptLinePrice(line.id)
      }))
    )
    if (version !== loadVersion) return
    receipt.value = header
    lines.value = pricedLines
    total.value = page.total
  } catch {
    if (version === loadVersion) error.value = '采购单及行价格加载失败，请重试。'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
const editPrice = (line: ReceiptLine) =>
  priceDialog.value?.open('receipt', {
    lineId: line.id,
    itemCode: line.itemCode,
    itemName: line.itemName,
    unitPrice: line.price?.unitPrice,
    remark: line.price?.remark
  })
const selectDocument = () => {
  pageNo.value = 1
  load()
}
watch(
  () => props.receiptId,
  (id) => {
    selectedId.value = id
    selectDocument()
  },
  { immediate: true }
)
</script>
