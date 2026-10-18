<template>
  <div class="app-container">
    <el-alert v-if="error" class="mb-10px" type="error" :closable="false" :title="error">
      <el-button link type="primary" @click="getList">重试</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="batchList">
      <el-table-column label="生产工单号" width="150px" align="center" prop="workOrderCode" />
      <el-table-column label="批次编号" align="center" prop="code" />
      <el-table-column label="产品物料编码" align="center" prop="itemCode" />
      <el-table-column label="产品物料名称" align="center" prop="itemName" />
      <el-table-column label="规格型号" align="center" prop="itemSpecification" />
      <el-table-column label="单位" align="center" prop="unitName" />
    </el-table>
  </div>
</template>

<script lang="ts" setup>
import { BatchApi, BatchVO } from '@/api/mes/wm/batch'

defineOptions({ name: 'BatchTraceDetailList' })

const props = defineProps<{
  batchId?: number
  batchCode?: string
  direction: 'forward' | 'backward' // 追溯方向：forward=向前追溯，backward=向后追溯
}>()

const loading = ref(false) // 列表的加载中
const batchList = ref<BatchVO[]>([]) // 列表的数据
const error = ref('')
let requestSequence = 0

/** 查询列表 */
const getList = async () => {
  const sequence = ++requestSequence
  const code = props.batchCode
  batchList.value = []
  error.value = ''
  if (!code) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result =
      props.direction === 'forward'
        ? await BatchApi.getForwardList(code)
        : await BatchApi.getBackwardList(code)
    if (sequence === requestSequence) batchList.value = result
  } catch {
    if (sequence === requestSequence) error.value = '批次追溯查询失败，请重新打开或重试。'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

/** 监听批次编号变化 */
watch(() => [props.batchCode, props.direction], getList, { immediate: true })

/** Invalidate pending responses when the dialog content is disposed. */
onBeforeUnmount(() => {
  requestSequence++
})
</script>
