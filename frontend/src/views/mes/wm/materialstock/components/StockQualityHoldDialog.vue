<template>
  <Dialog title="历史质量核查（只读）" v-model="dialogVisible" width="90%">
    <el-alert
      title="须实物核对及受控质量处置，普通解冻不能解除。此记录不代表已完成处置。"
      type="warning"
      :closable="false"
      show-icon
      class="!mb-12px"
    />
    <div class="mb-12px flex items-center justify-between gap-12px">
      <span>{{
        queryParams.materialStockId == null
          ? '当前租户全部核查证据，包含需人工定位库存的异常。'
          : `库存记录 #${queryParams.materialStockId} 的核查证据`
      }}</span>
      <el-button :loading="loading" @click="getList">刷新</el-button>
    </div>
    <el-alert
      v-if="loadFailed"
      title="核查记录加载失败，请刷新重试。"
      type="error"
      :closable="false"
      show-icon
      class="!mb-12px"
    />
    <el-table v-loading="loading" :data="list" stripe border :show-overflow-tooltip="true">
      <el-table-column label="核查记录 ID" prop="id" min-width="110" />
      <el-table-column label="库存 ID" min-width="140">
        <template #default="{ row }">
          <el-tag v-if="row.materialStockId == null" type="warning">需人工定位</el-tag>
          <span v-else>{{ row.materialStockId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="源流水 ID" prop="sourceTransactionId" min-width="110" />
      <el-table-column label="业务类型" min-width="100">
        <template #default="{ row }">{{ row.sourceBizType ?? '未关联' }}</template>
      </el-table-column>
      <el-table-column label="源业务单 ID" min-width="120">
        <template #default="{ row }">{{ row.sourceBizId ?? '未关联' }}</template>
      </el-table-column>
      <el-table-column label="源业务行 ID" min-width="120">
        <template #default="{ row }">{{ row.sourceBizLineId ?? '未关联' }}</template>
      </el-table-column>
      <el-table-column label="实际到达流水 ID" prop="arrivalTransactionId" min-width="140" />
      <el-table-column label="发现时余量" min-width="120">
        <template #default="{ row }">{{ row.observedQuantity ?? '未记录' }}</template>
      </el-table-column>
      <el-table-column label="核查原因" prop="reason" min-width="280" />
      <el-table-column label="记录状态" min-width="140">
        <template #default="{ row }">
          {{ row.status === 'PENDING_REVIEW' ? '待核对' : row.status }}
        </template>
      </el-table-column>
      <el-table-column label="发现时间" prop="createTime" :formatter="dateFormatter2" width="180" />
    </el-table>
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
    <template #footer>
      <el-button @click="dialogVisible = false">关 闭</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { WmMaterialStockApi, type WmStockQualityHoldVO } from '@/api/mes/wm/materialstock'
import { dateFormatter2 } from '@/utils/formatTime'

defineOptions({ name: 'StockQualityHoldDialog' })

const dialogVisible = ref(false)
const loading = ref(false)
const loadFailed = ref(false)
const list = ref<WmStockQualityHoldVO[]>([])
const total = ref(0)
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  materialStockId: undefined as number | undefined
})
let requestVersion = 0

const getList = async () => {
  const current = ++requestVersion
  loading.value = true
  loadFailed.value = false
  list.value = []
  total.value = 0
  try {
    const data = await WmMaterialStockApi.getQualityHoldPage({ ...queryParams })
    if (current !== requestVersion || !dialogVisible.value) return
    list.value = data.list
    total.value = data.total
  } catch {
    if (current === requestVersion) loadFailed.value = true
  } finally {
    if (current === requestVersion) loading.value = false
  }
}

watch(
  dialogVisible,
  (visible) => {
    if (!visible) {
      requestVersion++
      loading.value = false
    }
  },
  { flush: 'sync' }
)

const open = async (materialStockId?: number) => {
  dialogVisible.value = true
  queryParams.materialStockId = materialStockId
  queryParams.pageNo = 1
  queryParams.pageSize = 10
  await getList()
}

defineExpose({ open })
</script>
