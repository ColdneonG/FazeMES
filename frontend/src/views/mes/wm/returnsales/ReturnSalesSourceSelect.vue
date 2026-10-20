<template>
  <div class="return-source-select">
    <el-input
      :model-value="selectedLabel"
      readonly
      placeholder="选择客户实际销售出库流水"
      :disabled="disabled"
      @click="openDialog"
    >
      <template #append
        ><el-button :disabled="disabled" @click="openDialog">选择来源</el-button></template
      >
    </el-input>
    <el-button v-if="modelValue" link :disabled="disabled" @click="clear">清除选择</el-button>
    <el-dialog
      v-model="dialogOpen"
      title="选择原销售出库流水"
      width="min(1250px,95vw)"
      append-to-body
    >
      <el-alert
        title="只显示本退货单客户的实际出库凭据。不同库存位置分别列示；金额不在此处显示。"
        type="info"
        :closable="false"
      />
      <el-form inline class="source-search" @submit.prevent="search">
        <el-form-item label="销售单号"
          ><el-input v-model="filters.salesCode" clearable maxlength="128"
        /></el-form-item>
        <el-form-item label="物料编码"
          ><el-input v-model="filters.itemCode" clearable maxlength="128"
        /></el-form-item>
        <el-form-item label="批次"
          ><el-input v-model="filters.batchCode" clearable maxlength="128"
        /></el-form-item>
        <el-button :loading="loading" @click="search">查询</el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table
        :data="rows"
        v-loading="loading"
        highlight-current-row
        border
        @row-click="selectCurrent"
        @row-dblclick="chooseAndConfirm"
      >
        <el-table-column width="52"
          ><template #default="{ row }">
            <el-radio
              :model-value="staged?.originTransactionId"
              :value="row.originTransactionId"
              :disabled="!canSelectReturnSource(row)"
              :aria-label="`选择原流水 ${row.originTransactionId}`"
              @change="selectCurrent(row)"
            /> </template
        ></el-table-column>
        <el-table-column prop="salesCode" label="原销售出库单" min-width="150" />
        <el-table-column prop="originTransactionId" label="原流水ID" width="100" />
        <el-table-column prop="itemCode" label="物料编码" min-width="130" />
        <el-table-column prop="itemName" label="物料" min-width="130" />
        <el-table-column prop="batchCode" label="批次" min-width="150" />
        <el-table-column label="库存位置" min-width="180"
          ><template #default="{ row }">{{
            [row.warehouseName, row.locationName, row.areaName].filter(Boolean).join(' / ')
          }}</template></el-table-column
        >
        <el-table-column prop="unitMeasureName" label="单位" width="80" />
        <el-table-column
          v-for="column in balanceColumns"
          :key="column.key"
          :label="column.label"
          min-width="130"
          ><template #default="{ row }">{{
            returnBalanceText(row[column.key])
          }}</template></el-table-column
        >
        <el-table-column prop="outboundAt" label="原出库时间" min-width="180" />
        <el-table-column label="核验" min-width="220"
          ><template #default="{ row }">{{
            row.sourceWarning || (row.sourceReliable ? '凭据已核对' : '待核对')
          }}</template></el-table-column
        >
      </el-table>
      <el-pagination
        class="source-pagination"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="20"
        :current-page="pageNo"
        :disabled="loading"
        @current-change="loadRows"
      />
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="confirming"
          :disabled="!staged || loading || confirming"
          @click="confirm"
          >核对并选择</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {
  WmReturnSalesLineApi,
  type ReturnSalesSource,
  type ReturnSourceBalance
} from '@/api/mes/wm/returnsales/line'
import { canSelectReturnSource, returnBalanceText } from './sourceQuantity'

const props = defineProps<{
  modelValue?: number | null
  returnId: number
  lineId?: number
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [id: number | undefined]
  change: [source: ReturnSalesSource | undefined]
}>()
const message = useMessage()
const selected = ref<ReturnSalesSource>()
const staged = ref<ReturnSalesSource>()
const rows = ref<ReturnSalesSource[]>([])
const total = ref(0)
const pageNo = ref(1)
const dialogOpen = ref(false)
const loading = ref(false)
const confirming = ref(false)
const error = ref('')
const filters = reactive({ salesCode: '', itemCode: '', batchCode: '' })
let contextVersion = 0
let listVersion = 0
let confirmationVersion = 0
const selectedLabel = computed(() =>
  selected.value
    ? `${selected.value.salesCode} · 流水#${selected.value.originTransactionId} · ${selected.value.itemCode} · ${selected.value.batchCode || '无批次'}`
    : props.modelValue
      ? `原流水#${props.modelValue}（待刷新）`
      : ''
)
const balanceColumns: { key: keyof ReturnSourceBalance; label: string }[] = [
  { key: 'outboundQuantity', label: '原出库量' },
  { key: 'returnedQuantity', label: '累计已退' },
  { key: 'reservedQuantity', label: '未入库预留' },
  { key: 'editableAvailableQuantity', label: '本行可用额度' }
]
function clear() {
  contextVersion++
  selected.value = undefined
  staged.value = undefined
  emit('update:modelValue', undefined)
  emit('change', undefined)
}
async function refreshSelected() {
  const id = props.modelValue,
    version = contextVersion
  if (!id || !props.returnId) return undefined
  const data = await WmReturnSalesLineApi.getSourceOptions({
    returnId: props.returnId,
    lineId: props.lineId,
    originTransactionId: id,
    pageNo: 1,
    pageSize: 1
  })
  if (version !== contextVersion || props.modelValue !== id) return undefined
  const source = data.list.find((row) => row.originTransactionId === id)
  selected.value = source
  emit('change', source)
  return source
}
async function loadRows(page = pageNo.value) {
  if (!props.returnId || !dialogOpen.value) return
  const version = contextVersion,
    request = ++listVersion
  loading.value = true
  error.value = ''
  try {
    const data = await WmReturnSalesLineApi.getSourceOptions({
      returnId: props.returnId,
      lineId: props.lineId,
      ...filters,
      pageNo: page,
      pageSize: 20
    })
    if (version !== contextVersion || request !== listVersion || !dialogOpen.value) return
    rows.value = data.list
    total.value = data.total
    pageNo.value = page
  } catch {
    if (version === contextVersion && request === listVersion) {
      rows.value = []
      total.value = 0
      staged.value = undefined
      error.value = '无法核对原出库凭据，请检查当前退货单客户和访问权限后重试'
    }
  } finally {
    if (version === contextVersion && request === listVersion) loading.value = false
  }
}
async function openDialog() {
  if (props.disabled || !props.returnId) return
  dialogOpen.value = true
  staged.value = canSelectReturnSource(selected.value) ? selected.value : undefined
  await loadRows(1)
}
function search() {
  staged.value = undefined
  void loadRows(1)
}
function selectCurrent(row: ReturnSalesSource) {
  if (loading.value || confirming.value || !canSelectReturnSource(row)) {
    staged.value = undefined
    return
  }
  staged.value = row
}
async function chooseAndConfirm(row: ReturnSalesSource) {
  selectCurrent(row)
  if (staged.value) await confirm()
}
async function confirm() {
  const candidate = staged.value
  if (
    !candidate ||
    !canSelectReturnSource(candidate) ||
    confirming.value ||
    loading.value ||
    !dialogOpen.value
  )
    return
  const version = contextVersion,
    request = ++confirmationVersion
  confirming.value = true
  try {
    const data = await WmReturnSalesLineApi.getSourceOptions({
      returnId: props.returnId,
      lineId: props.lineId,
      originTransactionId: candidate.originTransactionId,
      pageNo: 1,
      pageSize: 1
    })
    if (version !== contextVersion || request !== confirmationVersion || !dialogOpen.value) return
    const fresh = data.list.find((row) => row.originTransactionId === candidate.originTransactionId)
    if (!fresh || !canSelectReturnSource(fresh)) {
      staged.value = undefined
      message.warning(fresh?.sourceWarning || '原出库可退额度已变更，请重新选择')
      await loadRows()
      return
    }
    selected.value = fresh
    emit('update:modelValue', fresh.originTransactionId)
    emit('change', fresh)
    dialogOpen.value = false
  } catch {
    if (version === contextVersion && request === confirmationVersion) {
      staged.value = undefined
      error.value = '来源核对失败，未选择该出库流水，请重试'
    }
  } finally {
    if (version === contextVersion && request === confirmationVersion) confirming.value = false
  }
}
watch(
  () => [props.returnId, props.lineId, props.modelValue] as const,
  async () => {
    const version = ++contextVersion
    listVersion++
    confirmationVersion++
    confirming.value = false
    loading.value = false
    staged.value = undefined
    selected.value = undefined
    try {
      if (props.modelValue) await refreshSelected()
      if (version === contextVersion && dialogOpen.value) await loadRows(1)
    } catch {
      if (version === contextVersion) {
        selected.value = undefined
        emit('change', undefined)
        error.value = '当前原出库凭据无法核对，请重新选择'
      }
    }
  },
  { immediate: true }
)
watch(dialogOpen, (open) => {
  if (!open) {
    confirmationVersion++
    confirming.value = false
    staged.value = undefined
  }
})
onBeforeUnmount(() => {
  contextVersion++
  listVersion++
  confirmationVersion++
})
defineExpose({ refreshSelected })
</script>

<style scoped>
.return-source-select {
  width: 100%;
}

.source-search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}

.source-pagination {
  margin-top: 16px;
}
</style>
