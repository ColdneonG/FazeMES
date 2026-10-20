<template>
  <section v-if="current" class="stock-evidence" v-loading="loading">
    <template v-if="inventory">
      <p>
        {{ inventory.period.year }}年{{ inventory.period.month }}月收发存
        <el-tag v-if="inventory.period.partialMonth" type="warning" size="small"
          >当月截至查询时点</el-tag
        >
      </p>
      <p class="note">
        {{ inventory.period.start }}（含）至 {{ inventory.period.effectiveEndExclusive }}（不含） ·
        {{ inventory.period.timezone }}
      </p>
      <p class="note">
        物料：{{ inventory.filters.item ? entityLabel(inventory.filters.item) : '全部' }}；分类：{{
          inventory.filters.category ? entityLabel(inventory.filters.category) : '全部'
        }}
        {{
          inventory.filters.category && inventory.filters.includeCategoryChildren
            ? '（含子分类）'
            : ''
        }}； 仓库：{{
          inventory.filters.warehouse ? entityLabel(inventory.filters.warehouse) : '全部'
        }}；批次：{{ inventory.filters.batchCode || '全部' }}
      </p>
      <p class="note">实物库存范围含线边、隔离及冻结库存；按当前物料分类和单位分组。</p>
      <el-table :data="inventory.unitGroups" border size="small" empty-text="本次范围没有数量汇总">
        <el-table-column label="单位" min-width="100"
          ><template #default="{ row }">{{
            row.unitName || row.unitKey
          }}</template></el-table-column
        >
        <el-table-column
          v-for="column in stockColumns"
          :key="column.key"
          :label="column.label"
          min-width="145"
        >
          <template #default="{ row }">{{ exactQuantity(row[column.key]) }}</template>
        </el-table-column>
        <el-table-column prop="rowCount" label="全部分组数" width="110" />
        <el-table-column label="依据"
          ><template #default="{ row }">{{
            row.complete ? '完整' : '需核对'
          }}</template></el-table-column
        >
      </el-table>
      <el-table :data="inventory.list" border size="small" empty-text="当前范围没有库存记录">
        <el-table-column label="物料" min-width="180"
          ><template #default="{ row }">{{ entityLabel(row.item) }}</template></el-table-column
        >
        <el-table-column label="分类" min-width="130"
          ><template #default="{ row }">{{ entityLabel(row.category) }}</template></el-table-column
        >
        <el-table-column v-if="inventory.filters.groupBy === 'BATCH'" label="批次" min-width="160"
          ><template #default="{ row }">{{
            row.batch?.code || '无批次'
          }}</template></el-table-column
        >
        <el-table-column
          v-if="inventory.filters.groupBy === 'WAREHOUSE'"
          label="仓库"
          min-width="150"
          ><template #default="{ row }">{{ entityLabel(row.warehouse) }}</template></el-table-column
        >
        <el-table-column label="单位" min-width="90"
          ><template #default="{ row }">{{
            row.unitName || row.unitKey
          }}</template></el-table-column
        >
        <el-table-column
          v-for="column in stockColumns"
          :key="column.key"
          :label="column.label"
          min-width="145"
          ><template #default="{ row }">{{
            exactQuantity(row[column.key])
          }}</template></el-table-column
        >
        <el-table-column label="校核" min-width="220"
          ><template #default="{ row }">
            {{ row.complete ? '依据完整' : '依据需核对' }}；{{
              row.balanced === null
                ? '数量等式待核对'
                : row.balanced
                  ? '数量等式相符'
                  : '数量等式不符'
            }}
            <p v-for="warning in row.warnings" :key="warning.code">{{ warning.message }}</p>
          </template></el-table-column
        >
      </el-table>
    </template>
    <template v-else-if="document">
      <p>
        {{ document.document.bizTypeName }} · {{ document.document.bizCode }}
        <el-tag
          :type="document.document.postingStatus === 'POSTED' ? 'success' : 'info'"
          size="small"
        >
          {{
            document.document.postingStatus === 'POSTED' ? '已有库存过账' : '未查到已过账库存流水'
          }}
        </el-tag>
        <el-button
          v-if="canOpen(document.document.source)"
          link
          type="primary"
          :loading="openingSource"
          @click="openSource(document.document.source)"
          >查看原单据</el-button
        >
      </p>
      <p v-if="!document.document.headerKnown" class="note">当前查询未提供可查看的单据表头信息。</p>
      <el-table :data="document.unitGroups" border size="small">
        <el-table-column label="单位"
          ><template #default="{ row }">{{
            row.unitName || row.unitKey
          }}</template></el-table-column
        >
        <el-table-column prop="inboundQuantity" label="全部入库" min-width="150" />
        <el-table-column prop="outboundQuantity" label="全部出库" min-width="150" />
        <el-table-column prop="netQuantity" label="净变动" min-width="150" />
      </el-table>
      <el-table :data="document.list" border size="small" empty-text="没有已过账库存流水">
        <el-table-column prop="id" label="流水ID" width="100" />
        <el-table-column prop="transactionAt" label="过账时间" min-width="200" />
        <el-table-column label="物料" min-width="180"
          ><template #default="{ row }">{{ entityLabel(row.item) }}</template></el-table-column
        >
        <el-table-column label="批次" min-width="140"
          ><template #default="{ row }">{{
            row.batch?.code || '无批次'
          }}</template></el-table-column
        >
        <el-table-column label="仓库 / 库区 / 库位" min-width="230"
          ><template #default="{ row }">{{
            [row.warehouse, row.location, row.area].map(entityLabel).join(' / ')
          }}</template></el-table-column
        >
        <el-table-column label="单位"
          ><template #default="{ row }">{{
            row.unitName || row.unitKey
          }}</template></el-table-column
        >
        <el-table-column prop="quantity" label="有符号数量" min-width="160" />
        <el-table-column label="方向"
          ><template #default="{ row }">{{
            row.direction === 'IN' ? '入库' : '出库'
          }}</template></el-table-column
        >
        <el-table-column prop="relatedTransactionId" label="关联流水" min-width="100" />
        <el-table-column label="原始单据" min-width="110"
          ><template #default="{ row }">
            <el-button
              v-if="canOpen(row.source)"
              link
              type="primary"
              :loading="openingSource"
              @click="openSource(row.source)"
              >查看单据</el-button
            >
            <span v-else>#{{ row.source?.bizId || '未知' }}</span>
          </template></el-table-column
        >
      </el-table>
    </template>
    <p class="note"
      >证据快照：{{ current.asOf }}；各单位汇总覆盖全部
      {{ current.total }} 个分组／流水，翻页保持同一快照。</p
    >
    <p class="note">{{ current.basis }}</p>
    <el-alert
      v-if="!current.complete"
      title="部分历史依据不足，请结合下方提示核对；待核对数量没有按零计算。"
      type="warning"
      :closable="false"
    />
    <p v-for="warning in current.warnings" :key="warning.code" class="note">{{
      warning.message
    }}</p>
    <el-pagination
      v-if="current.total > current.pageSize"
      layout="total, prev, pager, next"
      :total="current.total"
      :current-page="current.pageNo"
      :page-size="current.pageSize"
      :disabled="loading"
      @current-change="pageEvidence"
    />
    <component :is="sourceComponent" v-if="sourceComponent" ref="sourceForm" />
  </section>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import { InsightApi } from '@/api/mes/insight'
import type { StockEvidence, SourceReference } from '@/api/mes/insight/business'
import { checkPermi } from '@/utils/permission'
import { canOpenSource, exactQuantity, entityLabel } from './businessEvidence'

const props = defineProps<{ turnId: number; evidence: StockEvidence }>()
const emit = defineEmits<{ failure: [cause: unknown] }>()
const current = ref<StockEvidence>()
const loading = ref(false)
const openingSource = ref(false)
const sourceComponent = shallowRef<Component>()
const sourceForm = ref<{ open: (type: string, id: number) => Promise<void> }>()
let requestEpoch = 0
let sourceEpoch = 0
const inventory = computed(() =>
  current.value?.kind === 'INVENTORY_LEDGER' ? current.value : undefined
)
const document = computed(() =>
  current.value?.kind === 'STOCK_DOCUMENT' ? current.value : undefined
)
const stockColumns: {
  key: 'openingQuantity' | 'inboundQuantity' | 'outboundQuantity' | 'closingQuantity'
  label: string
}[] = [
  { key: 'openingQuantity', label: '期初' },
  { key: 'inboundQuantity', label: '本期入库' },
  { key: 'outboundQuantity', label: '本期出库' },
  { key: 'closingQuantity', label: '期末' }
]
const sourceLoaders = {
  110: () => import('@/views/mes/wm/itemreceipt/ItemReceiptForm.vue'),
  111: () => import('@/views/mes/wm/transfer/TransferForm.vue'),
  112: () => import('@/views/mes/wm/transfer/TransferForm.vue'),
  113: () => import('@/views/mes/wm/miscissue/MiscIssueForm.vue'),
  114: () => import('@/views/mes/wm/miscreceipt/MiscReceiptForm.vue'),
  115: () => import('@/views/mes/wm/productissue/ProductIssueForm.vue'),
  116: () => import('@/views/mes/wm/returnissue/ReturnIssueForm.vue'),
  117: () => import('@/views/mes/wm/productreceipt/ProductReceiptForm.vue'),
  118: () => import('@/views/mes/wm/productsales/ProductSalesForm.vue'),
  119: () => import('@/views/mes/wm/returnsales/ReturnSalesForm.vue'),
  120: () => import('@/views/mes/wm/outsourceissue/OutsourceIssueForm.vue'),
  121: () => import('@/views/mes/wm/outsourcereceipt/OutsourceReceiptForm.vue'),
  124: () => import('@/views/mes/wm/returnvendor/ReturnVendorForm.vue')
}
const canOpen = (source: SourceReference) => canOpenSource(source, checkPermi)
watch(
  () => [props.turnId, props.evidence] as const,
  () => {
    requestEpoch++
    sourceEpoch++
    current.value = props.evidence
    loading.value = false
    openingSource.value = false
    sourceComponent.value = undefined
  },
  { immediate: true }
)
async function pageEvidence(pageNo: number) {
  if (!current.value || loading.value || pageNo === current.value.pageNo) return
  const pageSize = current.value.pageSize
  if (!Number.isSafeInteger(pageNo) || pageNo < 1 || (pageNo - 1) * pageSize >= current.value.total)
    return
  const request = ++requestEpoch
  const expected = current.value
  const turnId = props.turnId
  loading.value = true
  try {
    const data = await InsightApi.conversationEvidence(turnId, pageNo, pageSize)
    if (request !== requestEpoch || turnId !== props.turnId) return
    if (
      data.asOf !== expected.asOf ||
      data.kind !== expected.kind ||
      data.total !== expected.total ||
      data.pageNo !== pageNo
    ) {
      throw new Error('证据快照已改变，请重新载入会话')
    }
    current.value = data
  } catch (cause) {
    if (request === requestEpoch) {
      current.value = undefined
      emit('failure', cause)
    }
  } finally {
    if (request === requestEpoch) loading.value = false
  }
}
async function openSource(source: SourceReference) {
  if (!canOpen(source) || openingSource.value) return
  const request = ++sourceEpoch
  openingSource.value = true
  try {
    const loaded = await sourceLoaders[source.bizType as keyof typeof sourceLoaders]()
    if (request !== sourceEpoch || !canOpen(source)) return
    sourceComponent.value = markRaw(loaded.default)
    await nextTick()
    if (request !== sourceEpoch || !canOpen(source)) {
      sourceComponent.value = undefined
      return
    }
    await sourceForm.value?.open('detail', source.bizId!)
  } catch (cause) {
    if (request === sourceEpoch) {
      sourceComponent.value = undefined
      emit('failure', cause)
    }
  } finally {
    if (request === sourceEpoch) openingSource.value = false
  }
}
onBeforeUnmount(() => {
  requestEpoch++
  sourceEpoch++
})
</script>

<style scoped>
.stock-evidence {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
}

.stock-evidence p {
  margin: 0;
}

.note {
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}
</style>
