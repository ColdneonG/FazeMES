<template>
  <Dialog v-model="dialogVisible" title="批次追溯" width="960">
    <el-descriptions :column="3" border>
      <el-descriptions-item label="批次编号">
        {{ detailData.code }}
      </el-descriptions-item>
      <el-descriptions-item label="物资编码">
        {{ detailData.itemCode }}
      </el-descriptions-item>
      <el-descriptions-item label="物资名称">
        {{ detailData.itemName }}
      </el-descriptions-item>
      <el-descriptions-item label="规格型号" :span="3">
        {{ detailData.itemSpecification }}
      </el-descriptions-item>
      <el-descriptions-item label="采购订单编号">
        {{ detailData.purchaseOrderCode }}
      </el-descriptions-item>
      <el-descriptions-item label="供应商编码">
        {{ detailData.vendorCode }}
      </el-descriptions-item>
      <el-descriptions-item label="供应商名称">
        {{ detailData.vendorName }}
      </el-descriptions-item>
      <el-descriptions-item label="销售订单编号">
        {{ detailData.salesOrderCode }}
      </el-descriptions-item>
      <el-descriptions-item label="客户编码">
        {{ detailData.clientCode }}
      </el-descriptions-item>
      <el-descriptions-item label="客户名称">
        {{ detailData.clientName }}
      </el-descriptions-item>
      <el-descriptions-item label="生产批号">
        {{ detailData.lotNumber }}
      </el-descriptions-item>
      <el-descriptions-item label="生产工单">
        {{ detailData.workOrderCode }}
      </el-descriptions-item>
      <el-descriptions-item label="工作站编码">
        {{ detailData.workstationCode }}
      </el-descriptions-item>
    </el-descriptions>
    <el-alert
      class="mt-10px"
      type="info"
      :closable="false"
      show-icon
      title="追溯依据"
      description="关系来自同次已完成报工的实际耗料与产出、已冻结的前序报工来源链，或已完成 SN 分配。相同批次可能汇总多次报工，本页展示批次范围的关联；历史未登记来源不自动推断，无结果不代表未被使用。"
    />
    <div v-loading="coverageLoading" class="mt-10px">
      <el-alert
        v-if="coverage"
        :type="coverage.complete ? 'success' : 'warning'"
        :title="coverageTitle"
        :description="coverage.notes.join('；')"
        :closable="false"
        show-icon
      />
      <el-alert v-if="coverageError" :title="coverageError" type="error" :closable="false" />
      <el-button v-if="coverageError" link type="primary" @click="loadCoverage"
        >重试证据核查</el-button
      >
    </div>
    <el-tabs type="border-card" class="mt-10px">
      <el-tab-pane label="向前追溯">
        <BatchTraceDetailList
          :key="`forward-${queryVersion}`"
          :batchId="detailData.id"
          :batchCode="detailData.code"
          direction="forward"
        />
      </el-tab-pane>
      <el-tab-pane label="向后追溯">
        <BatchTraceDetailList
          :key="`backward-${queryVersion}`"
          :batchId="detailData.id"
          :batchCode="detailData.code"
          direction="backward"
        />
      </el-tab-pane>
    </el-tabs>
  </Dialog>
</template>

<script lang="ts" setup>
import { BatchApi, type BatchVO, type BatchTraceCoverage } from '@/api/mes/wm/batch'
import BatchTraceDetailList from './BatchTraceDetailList.vue'

defineOptions({ name: 'BatchTraceDetail' })

const dialogVisible = ref(false) // 弹窗的是否展示
const detailData = ref<BatchVO>({} as BatchVO) // 详情数据
const queryVersion = ref(0)
const coverage = ref<BatchTraceCoverage>()
const coverageLoading = ref(false)
const coverageError = ref('')
let coverageSequence = 0
const coverageTitle = computed(() =>
  coverage.value?.truncated
    ? '追溯达到查询深度上限，结果不完整'
    : coverage.value?.complete
      ? '已登记来源链证据完整（批次范围）'
      : '来源证据不完整，请结合历史缺证提示核查'
)

const loadCoverage = async () => {
  const sequence = ++coverageSequence
  const code = detailData.value.code
  coverage.value = undefined
  coverageError.value = ''
  coverageLoading.value = false
  if (!code) return
  coverageLoading.value = true
  try {
    const result = await BatchApi.getTraceCoverage(code)
    if (sequence === coverageSequence && dialogVisible.value) coverage.value = result
  } catch {
    if (sequence === coverageSequence && dialogVisible.value)
      coverageError.value = '来源完整性核查失败，不能据此判定追溯完整。'
  } finally {
    if (sequence === coverageSequence) coverageLoading.value = false
  }
}

/** 打开弹窗 */
const open = async (data: BatchVO) => {
  detailData.value = data
  queryVersion.value++
  dialogVisible.value = true
  await loadCoverage()
}
watch(dialogVisible, (visible) => {
  if (!visible) {
    coverageSequence++
    coverageLoading.value = false
  }
})
onBeforeUnmount(() => {
  coverageSequence++
})
defineExpose({ open }) // 提供 open 方法，用于打开弹窗
</script>
