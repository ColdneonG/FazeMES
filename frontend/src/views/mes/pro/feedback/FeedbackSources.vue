<template>
  <section v-if="taskId" v-loading="loading" class="feedback-sources">
    <el-divider content-position="left">前序报工来源</el-divider>
    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" />
    <template v-else-if="context">
      <el-alert
        v-if="context.firstProcess"
        type="info"
        title="这是首道工序，无需分配前序报工；实际原料批次由本次耗料记录确定。"
        :closable="false"
      />
      <template v-else>
        <el-alert
          :type="context.legacy || hasIncompleteSource ? 'warning' : 'info'"
          :title="
            context.legacy
              ? '历史报工缺少前序来源证据，不能据此认定链路完整。'
              : '请选择本次实际使用的前一道工序报工，来源合计须等于本次报工数量。'
          "
          description="可用量会随其他报工审核而变化，审核时服务器会再次核对。来源链缺证时会保留提示，不会自动推断。"
          :closable="false"
        />
        <el-table v-if="readonly" :data="context.allocations" class="mt-10px" border>
          <el-table-column label="来源报工" prop="sourceFeedbackCode" />
          <el-table-column label="使用数量" prop="quantity" />
          <el-table-column label="来源证据">
            <template #default="{ row }">{{ row.complete ? '完整' : '历史缺证' }}</template>
          </el-table-column>
        </el-table>
        <el-table v-else :data="context.options" class="mt-10px" border>
          <el-table-column label="使用" width="65">
            <template #default="{ row }">
              <el-checkbox
                :model-value="isSelected(row.id)"
                :disabled="loading"
                @change="toggleSource(row, Boolean($event))"
              />
            </template>
          </el-table-column>
          <el-table-column label="前序报工" prop="code" min-width="140" />
          <el-table-column label="工序" prop="processName" min-width="100" />
          <el-table-column label="合格量" prop="qualified" />
          <el-table-column label="其他报工已占用" prop="used" min-width="120" />
          <el-table-column label="可用量" prop="available" />
          <el-table-column label="本次使用数量" min-width="160">
            <template #default="{ row }">
              <el-input
                v-if="isSelected(row.id)"
                :model-value="selectedQuantity(row.id)"
                inputmode="decimal"
                placeholder="最多 6 位小数"
                :disabled="loading"
                @update:model-value="updateQuantity(row.id, String($event))"
              />
            </template>
          </el-table-column>
          <el-table-column label="来源证据" width="100">
            <template #default="{ row }">{{ row.complete ? '完整' : '历史缺证' }}</template>
          </el-table-column>
        </el-table>
        <p v-if="!readonly" class="source-total"
          >已分配 {{ allocatedTotal }} / 本次 {{ quantity }}</p
        >
        <p v-if="hasIncompleteSource" class="source-warning"
          >所选来源包含历史缺证记录，本次追溯也将标记为不完整。</p
        >
      </template>
    </template>
    <el-button
      v-if="!readonly || loadError"
      class="mt-10px"
      :disabled="loading"
      @click="loadSources(false)"
      >刷新来源</el-button
    >
  </section>
</template>

<script setup lang="ts">
import {
  ProFeedbackApi,
  type FeedbackSource,
  type FeedbackSourceContext,
  type FeedbackSourceOption
} from '@/api/mes/pro/feedback'
import {
  quantityUnits,
  quantityText,
  sourceValidationError,
  sumQuantities
} from './sourceQuantities'

const props = withDefaults(
  defineProps<{
    taskId?: number
    feedbackId?: number
    modelValue: FeedbackSource[]
    quantity: number | string
    readonly?: boolean
  }>(),
  { readonly: false }
)
const emit = defineEmits<{ 'update:modelValue': [value: FeedbackSource[]] }>()
const message = useMessage()
const loading = ref(false)
const context = ref<FeedbackSourceContext>()
const loadError = ref('')
let requestSequence = 0
let loadedKey = ''
const currentKey = () => `${props.taskId || ''}:${props.feedbackId || ''}`
const allocatedTotal = computed(
  () => sumQuantities(...props.modelValue.map((source) => source.quantity)) ?? '数量无效'
)
const hasIncompleteSource = computed(
  () =>
    context.value?.allocations.some((source) => !source.complete) ||
    props.modelValue.some(
      (source) =>
        !context.value?.options.find((option) => option.id === source.sourceFeedbackId)?.complete
    )
)

const loadSources = async (resetSelection: boolean) => {
  const sequence = ++requestSequence
  const key = currentKey()
  loadedKey = ''
  loadError.value = ''
  context.value = undefined
  if (resetSelection) emit('update:modelValue', [])
  if (!props.taskId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await ProFeedbackApi.getSourceOptions(props.taskId, props.feedbackId)
    if (sequence !== requestSequence || key !== currentKey()) return
    context.value = result
    loadedKey = key
    if (resetSelection)
      emit(
        'update:modelValue',
        result.allocations.map(({ sourceFeedbackId, quantity }) => ({ sourceFeedbackId, quantity }))
      )
  } catch {
    if (sequence === requestSequence) loadError.value = '前序来源加载失败，请刷新后重试。'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

const isSelected = (id: number) => props.modelValue.some((source) => source.sourceFeedbackId === id)
const selectedQuantity = (id: number) =>
  props.modelValue.find((source) => source.sourceFeedbackId === id)?.quantity || ''
const updateQuantity = (id: number, quantity: string) =>
  emit(
    'update:modelValue',
    props.modelValue.map((source) =>
      source.sourceFeedbackId === id ? { ...source, quantity } : source
    )
  )
const toggleSource = (option: FeedbackSourceOption, selected: boolean) => {
  const remaining = props.modelValue.filter((source) => source.sourceFeedbackId !== option.id)
  if (selected) {
    const target = quantityUnits(props.quantity) ?? 0n
    const used = quantityUnits(sumQuantities(...remaining.map((source) => source.quantity))) ?? 0n
    const available = quantityUnits(option.available) ?? 0n
    const wanted = target > used ? target - used : 0n
    remaining.push({
      sourceFeedbackId: option.id,
      quantity: quantityText(wanted < available ? wanted : available)
    })
  }
  emit('update:modelValue', remaining)
}

const validate = (requireTotal = true): boolean => {
  let error = ''
  if (loading.value || loadedKey !== currentKey() || !context.value)
    error = loadError.value || '请先加载当前任务的前序来源'
  else if (!context.value.firstProcess && !context.value.legacy)
    error = sourceValidationError(
      props.modelValue,
      context.value.options,
      props.quantity,
      requireTotal
    )
  if (error) message.warning(error)
  return !error
}
watch(
  () => [props.taskId, props.feedbackId],
  () => loadSources(true),
  { immediate: true }
)
onBeforeUnmount(() => {
  requestSequence++
})
defineExpose({ validate })
</script>

<style scoped>
.source-total {
  margin: 12px 0;
}

.source-warning {
  color: var(--el-color-warning);
}
</style>
