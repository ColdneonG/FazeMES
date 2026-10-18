<template>
  <ContentWrap>
    <div class="kitting-heading"
      ><div><h2>齐套与缺料管理</h2><p>按工单BOM、净领料和可用实物库存计算物料缺口</p></div
      ><el-button :loading="boardLoading" @click="refreshAll">刷新分析</el-button></div
    >
    <el-form inline @submit.prevent>
      <el-form-item label="工单"
        ><ProWorkOrderSelect
          v-model="selectedOrderId"
          class="!w-240px"
          placeholder="选择工单（含已关闭单据）"
          @change="selectOrder"
      /></el-form-item>
      <el-form-item label="工单关键字"
        ><el-input
          v-model="query.keyword"
          clearable
          placeholder="编码 / 名称"
          @keyup.enter="loadBoard"
      /></el-form-item>
      <el-form-item
        ><el-switch v-model="query.onlyShortage" active-text="仅缺料工单" @change="loadBoard"
      /></el-form-item>
      <el-form-item><el-button type="primary" @click="loadBoard">查询看板</el-button></el-form-item>
    </el-form>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-12px"
    />
    <el-alert
      title="预占仅锁定工单可用量，不产生出入库、不修改实物库存；跟进“已到货”只作沟通记录，实际可用量以仓库入库、冻结及库位状态为准。"
      type="info"
      show-icon
      :closable="false"
    />
  </ContentWrap>

  <div class="kitting-layout">
    <ContentWrap>
      <div class="board-heading"
        ><h3>已确认工单看板</h3><span>{{ board.length }} 单 · 当前筛选</span></div
      >
      <el-table
        v-loading="boardLoading"
        :data="visibleBoard"
        highlight-current-row
        row-key="workOrderId"
        empty-text="当前筛选下无已确认工单"
        @row-click="openAnalysis"
      >
        <el-table-column label="工单" min-width="160"
          ><template #default="{ row }"
            ><strong>{{ row.workOrderCode }}</strong
            ><div class="muted">{{ row.workOrderName }}</div></template
          ></el-table-column
        >
        <el-table-column label="齐套状态" min-width="100"
          ><template #default="{ row }"
            ><el-tag :type="statusTone(row.status)">{{ statusText(row.status) }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="shortageItemCount" label="缺料种类" width="90" />
        <el-table-column width="65"
          ><template #default="{ row }"
            ><el-button link type="primary" @click.stop="openAnalysis(row)"
              >查看</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <el-pagination
        v-if="board.length > 12"
        v-model:current-page="boardPage"
        :page-size="12"
        :total="board.length"
        layout="prev, pager, next"
        class="mt-16px"
      />
    </ContentWrap>

    <ContentWrap>
      <el-empty
        v-if="!analysis && !detailLoading"
        description="选择工单查看齐套分析、预占和缺料跟进"
      />
      <div v-loading="detailLoading">
        <template v-if="analysis">
          <div class="analysis-heading"
            ><div
              ><h3
                >{{ analysis.workOrderCode }}
                <el-tag :type="statusTone(analysis.status)">{{
                  statusText(analysis.status)
                }}</el-tag></h3
              ><span class="muted"
                >{{ analysis.workOrderName }} · {{ orderStatusText(analysis.workOrderStatus) }} ·
                分析时间 {{ formatDate(analysis.analyzedAt) }}</span
              ></div
            ><div class="analysis-actions"
              ><el-button
                v-hasPermi="['mes:kitting:reserve']"
                type="primary"
                :disabled="analysis.workOrderStatus !== 1 || analysis.status === 'NO_BOM'"
                :loading="mutating"
                @click="reserve"
                >按先入先出预占</el-button
              ><el-button
                v-hasPermi="['mes:kitting:release']"
                type="warning"
                :disabled="analysis.workOrderStatus !== 1"
                :loading="mutating"
                @click="release"
                >释放剩余预占</el-button
              ></div
            ></div
          >
          <el-alert
            v-if="analysis.status === 'NO_BOM'"
            title="该工单未维护BOM，无法判断齐套，不视为已齐套。请先维护工单BOM。"
            type="warning"
            :closable="false"
            class="mt-16px"
          />
          <div class="analysis-summary"
            ><article
              ><span>物料种类</span><strong>{{ analysis.materials.length }}</strong></article
            ><article
              ><span>缺料种类</span
              ><strong class="shortage">{{ analysis.shortageItemCount }}</strong></article
            ><article
              ><span>预计到料待跟进</span
              ><strong>{{
                analysis.materials.filter((m) => Number(m.shortageQuantity) > 0 && !m.expectedDate)
                  .length
              }}</strong></article
            ></div
          >
          <el-tabs v-model="activeTab">
            <el-tab-pane label="物料齐套明细" name="materials">
              <el-table
                :data="analysis.materials"
                border
                stripe
                empty-text="无BOM物料"
                :row-class-name="materialRowClass"
              >
                <el-table-column
                  prop="itemCode"
                  label="物料编码"
                  min-width="135"
                  fixed
                /><el-table-column
                  prop="itemName"
                  label="物料名称"
                  min-width="150"
                /><el-table-column prop="unitName" label="单位" width="70" />
                <el-table-column
                  v-for="c in quantityColumns"
                  :key="c.key"
                  :label="c.label"
                  :min-width="c.width || 105"
                  align="right"
                  ><template #header
                    ><el-tooltip :content="c.tip" placement="top"
                      ><span>{{ c.label }} ⓘ</span></el-tooltip
                    ></template
                  ><template #default="{ row }"
                    ><span
                      :class="{ shortage: c.key === 'shortageQuantity' && Number(row[c.key]) > 0 }"
                      >{{ quantity(row[c.key]) }}</span
                    ></template
                  ></el-table-column
                >
                <el-table-column label="跟进状态" min-width="105"
                  ><template #default="{ row }">{{
                    trackingText(row.trackingStatus)
                  }}</template></el-table-column
                ><el-table-column
                  prop="expectedDate"
                  label="预计到料日"
                  min-width="120"
                /><el-table-column
                  prop="remark"
                  label="跟进备注"
                  min-width="180"
                  show-overflow-tooltip
                />
                <el-table-column label="操作" width="85" fixed="right"
                  ><template #default="{ row }"
                    ><el-button
                      v-hasPermi="['mes:kitting:followup']"
                      link
                      type="primary"
                      :disabled="analysis.workOrderStatus !== 1"
                      @click="openFollowup(row)"
                      >跟进</el-button
                    ></template
                  ></el-table-column
                >
              </el-table>
              <p class="material-note"
                >缺口 = max(待领量 − 可用量,
                0)；可用量包含本工单有效预占及扣除全部预占后的自由库存。各物料使用自己的计量单位，不跨物料加总数量。</p
              >
            </el-tab-pane>
            <el-tab-pane :label="`预占明细（${reservations.length}）`" name="reservations"
              ><el-table :data="reservations" border empty-text="暂无预占记录"
                ><el-table-column label="物料" min-width="150"
                  ><template #default="{ row }">{{
                    materialLabel(row.itemId)
                  }}</template></el-table-column
                ><el-table-column prop="batchCode" label="批次" min-width="135" /><el-table-column
                  label="仓库 / 库区 / 库位"
                  min-width="210"
                  ><template #default="{ row }">{{
                    [row.warehouseName, row.locationName, row.areaName]
                      .filter(Boolean)
                      .join(' / ') || '—'
                  }}</template></el-table-column
                ><el-table-column
                  v-for="c in reservationColumns"
                  :key="c.key"
                  :label="c.label"
                  min-width="110"
                  align="right"
                  ><template #default="{ row }">{{
                    quantity(row[c.key])
                  }}</template></el-table-column
                ><el-table-column label="库存可用状态" width="120"
                  ><template #default="{ row }"
                    ><el-tag :type="row.eligible ? 'success' : 'warning'">{{
                      row.eligible ? '可领用' : '当前不可领用'
                    }}</el-tag></template
                  ></el-table-column
                ></el-table
              ></el-tab-pane
            >
            <el-tab-pane label="预占流水（最近200条）" name="history"
              ><el-table :data="history" border empty-text="暂无预占流水"
                ><el-table-column prop="createTime" label="时间" min-width="170">
                  <template #default="{ row }">{{
                    formatDate(row.createTime)
                  }}</template> </el-table-column
                ><el-table-column label="动作" min-width="125"
                  ><template #default="{ row }">{{
                    actionText(row.action)
                  }}</template></el-table-column
                ><el-table-column label="物料" min-width="150"
                  ><template #default="{ row }">{{
                    materialLabel(row.itemId)
                  }}</template></el-table-column
                ><el-table-column
                  prop="materialStockId"
                  label="库存记录ID"
                  min-width="110" /><el-table-column label="数量" min-width="100" align="right"
                  ><template #default="{ row }">{{
                    quantity(row.quantity)
                  }}</template></el-table-column
                ><el-table-column
                  prop="referenceId"
                  label="来源单据ID"
                  min-width="110" /><el-table-column
                  prop="operatorId"
                  label="操作人ID"
                  min-width="100" /></el-table
            ></el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </ContentWrap>
  </div>

  <el-dialog v-model="followupVisible" title="缺料跟进" width="520px" destroy-on-close
    ><el-form ref="followupForm" :model="followup" label-width="110px"
      ><el-form-item label="物料">{{ followupItemName }}</el-form-item
      ><el-form-item
        label="跟进状态"
        prop="trackingStatus"
        :rules="[{ required: true, message: '请选择跟进状态', trigger: 'change' }]"
        ><el-select v-model="followup.trackingStatus"
          ><el-option
            v-for="s in trackingOptions"
            :key="s.value"
            :label="s.label"
            :value="s.value" /></el-select></el-form-item
      ><el-form-item label="预计到料日期"
        ><el-date-picker
          v-model="followup.expectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          clearable
          placeholder="可暂不填写" /></el-form-item
      ><el-form-item label="备注"
        ><el-input
          v-model="followup.remark"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit /></el-form-item></el-form
    ><el-alert
      title="跟进状态和预计日期不会改变库存或自动消除缺口。"
      type="info"
      :closable="false"
    /><template #footer
      ><el-button @click="followupVisible = false">取消</el-button
      ><el-button type="primary" :loading="followupSaving" @click="saveFollowup"
        >保存跟进</el-button
      ></template
    ></el-dialog
  >
</template>

<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { formatDate } from '@/utils/formatTime'
import ProWorkOrderSelect from '@/views/mes/pro/workorder/components/ProWorkOrderSelect.vue'
import {
  KittingApi,
  type KittingAnalysis,
  type KittingMaterial,
  type KittingReservation,
  type KittingEvent,
  type KittingFollowup
} from '@/api/mes/pro/kitting'
defineOptions({ name: 'MesProKitting' })
const route = useRoute()
const router = useRouter()
const message = useMessage()
const board = ref<KittingAnalysis[]>([])
const boardPage = ref(1)
const visibleBoard = computed(() =>
  board.value.slice((boardPage.value - 1) * 12, boardPage.value * 12)
)
const query = reactive({ keyword: '', onlyShortage: true })
const boardLoading = ref(false)
const detailLoading = ref(false)
const mutating = ref(false)
const error = ref('')
const selectedOrderId = ref<number>()
const analysis = ref<KittingAnalysis>()
const reservations = ref<KittingReservation[]>([])
const history = ref<KittingEvent[]>([])
const activeTab = ref('materials')
const statusLabels: Record<string, string> = {
  NO_BOM: '未维护BOM',
  CLOSED: '工单已关闭',
  ISSUED: '已领齐',
  READY: '可齐套',
  SHORTAGE: '缺料'
}
const statusText = (s: string) => statusLabels[s] || s
const statusTone = (s: string): 'success' | 'warning' | 'danger' | 'info' =>
  s === 'READY' || s === 'ISSUED'
    ? 'success'
    : s === 'SHORTAGE'
      ? 'danger'
      : s === 'NO_BOM'
        ? 'warning'
        : 'info'
const orderLabels: Record<number, string> = {
  0: '草稿',
  1: '已确认',
  2: '审批中',
  3: '已审批',
  4: '已完成',
  5: '已取消'
}
const orderStatusText = (s: number) => orderLabels[s] || `状态 ${s}`
const trackingOptions = [
  { value: 'OPEN', label: '待跟进' },
  { value: 'ORDERED', label: '已采购 / 催料中' },
  { value: 'ARRIVED', label: '已到货（待库存确认）' }
]
const trackingText = (s: string) => trackingOptions.find((x) => x.value === s)?.label || s
const actionLabels: Record<string, string> = {
  RESERVE: '新增预占',
  RELEASE: '释放预占',
  ISSUE: '领料消耗',
  RELEASE_EXCESS: '释放超额',
  AUTO_RELEASE: '工单关闭释放'
}
const actionText = (s: string) => actionLabels[s] || s
const quantity = (v: unknown) =>
  Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
const materialLabel = (id: number) => {
  const material = analysis.value?.materials.find((m) => m.itemId === id)
  return material ? `${material.itemCode} · ${material.itemName}` : `物料 ${id}`
}
const materialRowClass = ({ row }: { row: KittingMaterial }) =>
  Number(row.shortageQuantity) > 0 ? 'material-shortage-row' : ''
const quantityColumns = [
  { key: 'requiredQuantity', label: 'BOM需求', tip: '工单BOM需求数量' },
  { key: 'issuedQuantity', label: '净已领', tip: '该工单线边已领料减退料' },
  { key: 'outstandingQuantity', label: '待领量', tip: 'BOM需求减净已领料，不低于0' },
  {
    key: 'physicalQuantity',
    label: '可领实物库存',
    tip: '实物仓库未冻结且库位可用的库存量；排除线边仓库存',
    width: 135
  },
  { key: 'otherReservedQuantity', label: '他单预占', tip: '其他工单占用的有效库存量' },
  { key: 'reservedQuantity', label: '本单预占', tip: '本工单剩余预占量，可能包含当前不可领库存' },
  {
    key: 'usableReservedQuantity',
    label: '本单有效预占',
    tip: '本工单在当前可领库存上的剩余预占量',
    width: 135
  },
  { key: 'freeQuantity', label: '自由库存', tip: '可领实物库存扣除全部工单预占后的自由量' },
  { key: 'availableQuantity', label: '本单可用', tip: '自由库存加本工单有效预占' },
  { key: 'shortageQuantity', label: '缺口', tip: '待领量减本单可用量，不低于0' }
]
const reservationColumns = [
  { key: 'allocatedQuantity', label: '累计预占' },
  { key: 'quantity', label: '剩余预占' },
  { key: 'consumedQuantity', label: '已消耗' },
  { key: 'releasedQuantity', label: '已释放' }
]
async function loadBoard() {
  boardLoading.value = true
  error.value = ''
  try {
    board.value = await KittingApi.board({ ...query })
    boardPage.value = 1
  } catch {
    error.value = '齐套看板加载失败，请稍后重试。'
  } finally {
    boardLoading.value = false
  }
}
let detailRequest = 0
async function loadDetail(id: number) {
  const request = ++detailRequest
  if (analysis.value?.workOrderId !== id) {
    analysis.value = undefined
    reservations.value = []
    history.value = []
  }
  detailLoading.value = true
  error.value = ''
  try {
    const [a, r, h] = await Promise.all([
      KittingApi.analyze(id),
      KittingApi.reservations(id),
      KittingApi.history(id)
    ])
    if (request !== detailRequest) return
    analysis.value = a
    reservations.value = r
    history.value = h
  } catch {
    if (request === detailRequest)
      error.value = '工单齐套明细加载失败，请检查权限及工单状态后重试。'
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}
async function openAnalysis(row: KittingAnalysis) {
  selectedOrderId.value = row.workOrderId
  await router.replace({ query: { ...route.query, workOrderId: String(row.workOrderId) } })
  await loadDetail(row.workOrderId)
}
async function selectOrder() {
  if (selectedOrderId.value) {
    await router.replace({ query: { ...route.query, workOrderId: String(selectedOrderId.value) } })
    await loadDetail(selectedOrderId.value)
  } else {
    detailRequest++
    analysis.value = undefined
    reservations.value = []
    history.value = []
    detailLoading.value = false
    const nextQuery = { ...route.query }
    delete nextQuery.workOrderId
    await router.replace({ query: nextQuery })
  }
}
async function refreshAll() {
  await Promise.all([
    loadBoard(),
    selectedOrderId.value ? loadDetail(selectedOrderId.value) : Promise.resolve()
  ])
}
async function reserve() {
  if (!analysis.value || analysis.value.workOrderStatus !== 1) return
  const id = analysis.value.workOrderId
  mutating.value = true
  try {
    await KittingApi.reserve(id)
    await refreshAll()
    message.success('已按可用库存与先入先出规则完成预占')
  } finally {
    mutating.value = false
  }
}
async function release() {
  if (!analysis.value || analysis.value.workOrderStatus !== 1) return
  const id = analysis.value.workOrderId
  mutating.value = true
  try {
    await KittingApi.release(id)
    await refreshAll()
    message.success('剩余预占已释放')
  } finally {
    mutating.value = false
  }
}
const followupVisible = ref(false)
const followupSaving = ref(false)
const followupForm = ref<FormInstance>()
const followupItemName = ref('')
const followup = reactive<KittingFollowup>({
  workOrderId: 0,
  itemId: 0,
  trackingStatus: 'OPEN',
  expectedDate: null,
  remark: ''
})
const openFollowup = (row: KittingMaterial) => {
  if (!analysis.value) return
  Object.assign(followup, {
    workOrderId: analysis.value.workOrderId,
    itemId: row.itemId,
    trackingStatus: row.trackingStatus || 'OPEN',
    expectedDate: row.expectedDate || null,
    remark: row.remark || ''
  })
  followupItemName.value = `${row.itemCode} · ${row.itemName}`
  followupVisible.value = true
}
async function saveFollowup() {
  if (!(await followupForm.value?.validate())) return
  followupSaving.value = true
  try {
    await KittingApi.followup({ ...followup })
    followupVisible.value = false
    await refreshAll()
    message.success('缺料跟进已更新')
  } finally {
    followupSaving.value = false
  }
}
onMounted(async () => {
  const id = Number(route.query.workOrderId)
  if (Number.isSafeInteger(id) && id > 0) selectedOrderId.value = id
  await refreshAll()
})
watch(
  () => route.query.workOrderId,
  (value) => {
    const id = Number(value)
    if (Number.isSafeInteger(id) && id > 0 && id !== selectedOrderId.value) {
      selectedOrderId.value = id
      loadDetail(id)
    }
  }
)
</script>

<style scoped lang="scss">
.kitting-heading,
.analysis-heading,
.board-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 16px;
}

h2,
h3 {
  margin: 0 0 6px;
}

h2 {
  font-size: 20px;
}

h3 {
  font-size: 16px;
}

.kitting-heading p,
.muted,
.board-heading > span {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kitting-layout {
  display: grid;
  grid-template-columns: 390px minmax(0, 1fr);
  gap: 16px;
}

.analysis-actions {
  display: flex;
  gap: 10px;
}

.analysis-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 48px;
  padding: 20px 0;
  margin: 16px 0;
  border-top: 1px solid var(--el-border-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
}

.analysis-summary span {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.analysis-summary strong {
  display: block;
  margin-top: 7px;
  font-size: 26px;
}

.shortage {
  font-weight: 600;
  color: var(--el-color-danger);
}

.material-note {
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

:deep(.material-shortage-row) {
  --el-table-tr-bg-color: var(--el-color-danger-light-9);
}

@media (width <= 1300px) {
  .kitting-layout {
    grid-template-columns: 1fr;
  }
}
</style>
