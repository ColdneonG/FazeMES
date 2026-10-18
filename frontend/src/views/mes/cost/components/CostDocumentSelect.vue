<template>
  <el-select
    v-model="value"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="kind === 'receipt' ? '按采购入库单编号搜索' : '按销售出库单编号搜索'"
    @visible-change="onVisible"
  >
    <el-option
      v-for="option in options"
      :key="option.id"
      :value="option.id"
      :label="`${option.code} · ${option.name}`"
    >
      <span>{{ option.code }} · {{ option.name }}</span>
    </el-option>
  </el-select>
</template>
<script setup lang="ts">
import { WmItemReceiptApi } from '@/api/mes/wm/itemreceipt'
import { WmProductSalesApi } from '@/api/mes/wm/productsales'

const props = defineProps<{ modelValue?: number; kind: 'receipt' | 'sales' }>()
const emit = defineEmits<{ 'update:modelValue': [id: number | undefined]; change: [] }>()
interface Option {
  id: number
  code: string
  name: string
}
const options = ref<Option[]>([])
const loading = ref(false)
const value = computed({
  get: () => props.modelValue,
  set: (id) => {
    emit('update:modelValue', id)
    emit('change')
  }
})
let requestVersion = 0
const search = async (code = '') => {
  const version = ++requestVersion
  loading.value = true
  try {
    const page =
      props.kind === 'receipt'
        ? await WmItemReceiptApi.getItemReceiptPage({
            pageNo: 1,
            pageSize: 30,
            code: code || undefined
          })
        : await WmProductSalesApi.getProductSalesPage({
            pageNo: 1,
            pageSize: 30,
            code: code || undefined
          })
    if (version !== requestVersion) return
    const selected = options.value.find((option) => option.id === props.modelValue)
    options.value = page.list
    if (selected && !options.value.some((option) => option.id === selected.id))
      options.value.unshift(selected)
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
const onVisible = (visible: boolean) => {
  if (visible && !options.value.length) search()
}
watch(
  () => props.modelValue,
  async (id) => {
    if (!id || options.value.some((option) => option.id === id)) return
    const item: Option =
      props.kind === 'receipt'
        ? await WmItemReceiptApi.getItemReceipt(id)
        : await WmProductSalesApi.getProductSales(id)
    if (props.modelValue === id && item && !options.value.some((option) => option.id === id))
      options.value.unshift(item)
  },
  { immediate: true }
)
</script>
