<!--
  MES 发货通知单行弹窗选择器（支持单选/多选）

  Props:
    multiple — true 多选（checkbox），false 单选（radio）；默认 true
    noticeId — 发货通知单 ID（必传，行数据按此过滤）
  Events:
    selected(rows: WmSalesNoticeLineVO[]) — 确认选择后触发，单选时数组长度为 1
  Expose:
    open(selectedIds?: number[]) — 打开弹窗，可传入已选 ID 用于预选高亮
-->
<template>
  <Dialog title="发货通知单行选择" v-model="dialogVisible" width="70%">
    <!-- 数据表格：单选 radio / 多选 checkbox 统一在一个 table 内 -->
    <ContentWrap>
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="list"
        :stripe="true"
        :show-overflow-tooltip="true"
        row-key="id"
        :highlight-current-row="!multiple"
        @selection-change="handleSelectionChange"
        @row-click="handleRowClick"
        @row-dblclick="handleRowDblClick"
      >
        <!-- 多选：checkbox（reserve-selection 保证跨页勾选不丢失） -->
        <el-table-column
          v-if="multiple"
          type="selection"
          :reserve-selection="true"
          :selectable="canSelectNoticeLine"
          width="50"
          align="center"
        />
        <!-- 单选：radio -->
        <el-table-column v-else width="50" align="center">
          <template #default="{ row }">
            <el-radio
              v-model="selectedRadioId"
              :value="row.id"
              :disabled="loading || !canSelectNoticeLine(row)"
              class="radio-no-label"
              @change="handleRadioChange(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="物料编码" align="center" prop="itemCode" width="150" />
        <el-table-column label="物料名称" align="left" prop="itemName" min-width="200" />
        <el-table-column label="规格型号" align="center" prop="specification" min-width="120" />
        <el-table-column label="单位" align="center" prop="unitMeasureName" width="80" />
        <el-table-column label="批次号" align="center" prop="batchCode" width="130" />
        <el-table-column label="通知计划" align="right" prop="noticeQuantity" min-width="130" />
        <el-table-column label="已发货" align="right" min-width="130">
          <template #default="{ row }">{{ row.shippedQuantity ?? '待核对' }}</template>
        </el-table-column>
        <el-table-column label="未过账占用" align="right" min-width="130">
          <template #default="{ row }">{{ row.reservedQuantity ?? '待核对' }}</template>
        </el-table-column>
        <el-table-column
          label="当前可填"
          align="right"
          prop="editableAvailableQuantity"
          min-width="130"
        />
        <el-table-column label="履约核对" min-width="160">
          <template #default="{ row }">{{
            row.fulfillmentReliable ? '已核对' : row.fulfillmentWarning || '待核对'
          }}</template>
        </el-table-column>
        <el-table-column label="是否检验" align="center" prop="oqcCheckFlag" width="90">
          <template #default="scope">
            <dict-tag :type="DICT_TYPE.INFRA_BOOLEAN_STRING" :value="scope.row.oqcCheckFlag" />
          </template>
        </el-table-column>
        <el-table-column label="备注" align="center" prop="remark" min-width="120" />
      </el-table>
      <Pagination
        :total="total"
        v-model:page="queryParams.pageNo"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </ContentWrap>
    <template #footer>
      <el-button type="primary" :disabled="loading || confirming" @click="confirmSelect"
        >确 定</el-button
      >
      <el-button @click="dialogVisible = false">取 消</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { DICT_TYPE } from '@/utils/dict'
import { WmSalesNoticeLineApi, WmSalesNoticeLineVO } from '@/api/mes/wm/salesnotice/line'
import { canSelectNoticeLine } from '../fulfillment'

defineOptions({ name: 'WmSalesNoticeLineSelectDialog' })

const props = withDefaults(
  defineProps<{
    multiple?: boolean // true 多选（checkbox），false 单选（radio）
    noticeId?: number // 发货通知单 ID（行数据按此过滤）
    editingSalesLineId?: number
  }>(),
  {
    multiple: true
  }
)

const message = useMessage()
const emit = defineEmits<{
  selected: [rows: WmSalesNoticeLineVO[]]
}>()

const dialogVisible = ref(false) // 弹窗是否展示
const loading = ref(false) // 列表加载中
const confirming = ref(false)
let requestVersion = 0
const list = ref<WmSalesNoticeLineVO[]>([]) // 列表
const total = ref(0) // 总条数

// ==================== 选中状态 ====================
const tableRef = ref() // 表格 Ref
const selectedRows = ref<WmSalesNoticeLineVO[]>([]) // 多选模式：选中行
const selectedRadioId = ref<number>() // 单选模式：选中 ID
const currentRadioRow = ref<WmSalesNoticeLineVO>() // 单选模式：选中行对象
const preSelectedIds = ref<number[]>([]) // 打开弹窗时传入的已选 ID

/** 多选：checkbox 变化 */
const handleSelectionChange = (rows: WmSalesNoticeLineVO[]) => {
  if (props.multiple) {
    selectedRows.value = rows.filter(canSelectNoticeLine)
  }
}

/** 单选：radio 变化 */
const handleRadioChange = (row: WmSalesNoticeLineVO) => {
  if (loading.value || !canSelectNoticeLine(row)) {
    selectedRadioId.value = currentRadioRow.value?.id
    return
  }
  currentRadioRow.value = row
}

/** 单击行：单选模式下点击整行即选中（降低操作成本），多选不处理（避免和 dblclick 冲突） */
const handleRowClick = (row: WmSalesNoticeLineVO) => {
  if (props.multiple || loading.value || !canSelectNoticeLine(row)) {
    return
  }
  selectedRadioId.value = row.id
  currentRadioRow.value = row
}

/** 双击行：多选模式切换勾选，单选模式直接确认 */
const handleRowDblClick = (row: WmSalesNoticeLineVO) => {
  if (loading.value || !canSelectNoticeLine(row)) return
  if (props.multiple) {
    tableRef.value?.toggleRowSelection(row)
    return
  }
  selectedRadioId.value = row.id
  currentRadioRow.value = row
  confirmSelect()
}

// ==================== 查询 ====================
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  noticeId: undefined as number | undefined,
  editingSalesLineId: undefined as number | undefined
})

/** 查询列表 */
const getList = async () => {
  const version = ++requestVersion
  loading.value = true
  try {
    const data = await WmSalesNoticeLineApi.getSalesNoticeLinePage({ ...queryParams })
    if (version !== requestVersion || !dialogVisible.value) return
    list.value = data.list
    total.value = data.total
    await nextTick()
    applyPreSelection()
  } catch {
    if (version !== requestVersion) return
    list.value = []
    total.value = 0
    selectedRows.value = []
    currentRadioRow.value = undefined
    selectedRadioId.value = undefined
    message.warning('无法读取最新履约额度，请重试')
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

/** 恢复预选状态（当前页可见范围内） */
const applyPreSelection = () => {
  if (preSelectedIds.value.length === 0) {
    return
  }
  if (props.multiple) {
    const table = tableRef.value
    if (!table) {
      return
    }
    list.value.forEach((row) => {
      if (preSelectedIds.value.includes(row.id) && canSelectNoticeLine(row)) {
        table.toggleRowSelection(row, true)
      }
    })
  } else {
    const match = list.value.find(
      (row) => preSelectedIds.value.includes(row.id) && canSelectNoticeLine(row)
    )
    if (match) {
      selectedRadioId.value = match.id
      currentRadioRow.value = match
    }
  }
  const visibleIds = new Set(list.value.map((row) => row.id))
  preSelectedIds.value = preSelectedIds.value.filter((id) => !visibleIds.has(id))
}

/** 确认选择 */
const confirmSelect = async () => {
  if (loading.value || confirming.value) return
  const selected = props.multiple
    ? selectedRows.value
    : currentRadioRow.value
      ? [currentRadioRow.value]
      : []
  if (!selected.length || selected.some((row) => !canSelectNoticeLine(row))) {
    message.warning('请选择有可用额度且履约核对正常的通知单行')
    return
  }
  const version = requestVersion
  confirming.value = true
  try {
    const fresh: WmSalesNoticeLineVO[] = await Promise.all(
      selected.map((row) =>
        WmSalesNoticeLineApi.getSalesNoticeLine(row.id, props.editingSalesLineId)
      )
    )
    if (version !== requestVersion || !dialogVisible.value) return
    if (fresh.some((row) => row.noticeId !== props.noticeId || !canSelectNoticeLine(row))) {
      message.warning('履约额度已变化，请重新选择')
      selectedRows.value = []
      currentRadioRow.value = undefined
      selectedRadioId.value = undefined
      await getList()
      return
    }
    emit('selected', fresh)
    dialogVisible.value = false
  } catch {
    if (version === requestVersion) message.warning('无法核对最新履约额度，请重试')
  } finally {
    confirming.value = false
  }
}

// ==================== 打开弹窗 ====================

/** 打开弹窗，可传入已选 ID 用于预选高亮 */
const open = async (selectedIds?: number[]) => {
  dialogVisible.value = true
  // 设置 noticeId 过滤（必须）
  queryParams.noticeId = props.noticeId
  queryParams.editingSalesLineId = props.editingSalesLineId
  queryParams.pageNo = 1
  // 清空上一次的选中状态
  selectedRows.value = []
  selectedRadioId.value = undefined
  currentRadioRow.value = undefined
  preSelectedIds.value = selectedIds ?? []
  // 多选模式清空跨页缓存的勾选
  await nextTick()
  tableRef.value?.clearSelection()
  await getList()
}
watch(
  () => [props.noticeId, props.editingSalesLineId],
  () => {
    if (dialogVisible.value) void open()
  }
)
watch(dialogVisible, (visible) => {
  if (!visible) ++requestVersion
})
defineExpose({ open })
</script>

<style lang="scss" scoped>
/* 隐藏 radio 的 label 文字，只保留圆圈 */
.radio-no-label {
  :deep(.el-radio__label) {
    display: none;
  }
}
</style>
