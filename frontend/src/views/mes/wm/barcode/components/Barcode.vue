<template>
  <div v-loading="loading" class="inline-block" :style="wrapStyle">
    <img
      v-if="imageData"
      :src="imageData"
      :style="imageStyle"
      alt="条码"
      class="block max-w-full"
    />
    <div v-if="imageData && format !== 1 && displayValue" class="text-center text-xs break-all">{{
      content
    }}</div>
    <span v-else-if="errorText" class="text-xs text-red-500">{{ errorText }}</span>
  </div>
</template>
<script lang="ts" setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { encodeBarcode } from '../labelPrint'

defineOptions({ name: 'Barcode' })
const props = withDefaults(
  defineProps<{
    content?: string
    format?: number
    width?: number
    height?: number
    displayValue?: boolean
  }>(),
  { content: '', format: 1, width: 200, height: 100, displayValue: true }
)
const emit = defineEmits<{ done: [value: string]; error: [message: string] }>()
const loading = ref(false),
  imageData = ref(''),
  errorText = ref('')
let version = 0
const wrapStyle = computed(() => ({ width: `${props.width}px` }))
const imageStyle = computed(() => ({
  width: '100%',
  height: props.format === 1 ? 'auto' : `${props.height}px`
}))
const generateBarcode = async () => {
  const current = ++version
  imageData.value = ''
  errorText.value = ''
  loading.value = false
  emit('done', '')
  if (!props.content) return
  loading.value = true
  try {
    if (
      !Number.isFinite(props.width) ||
      !Number.isFinite(props.height) ||
      props.width <= 0 ||
      props.height <= 0
    )
      throw new Error('条码尺寸必须大于零')
    const encoded = await encodeBarcode(props.content, props.format)
    if (current !== version) return
    imageData.value = encoded.dataUrl
    emit('done', encoded.dataUrl)
  } catch (error) {
    if (current === version) {
      errorText.value = error instanceof Error ? error.message : '条码编码失败'
      emit('error', errorText.value)
    }
  } finally {
    if (current === version) loading.value = false
  }
}
watch(
  () => [props.content, props.format, props.width, props.height, props.displayValue],
  generateBarcode,
  { immediate: true }
)
onBeforeUnmount(() => {
  version++
  imageData.value = ''
})
const getImageBase64 = () => imageData.value
defineExpose({ getImageBase64 })
</script>
