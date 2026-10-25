<template>
  <el-input
    :model-value="modelValue == null ? '' : String(modelValue)"
    inputmode="decimal"
    maxlength="21"
    @update:model-value="update"
    @change="(value) => emit('change', String(value))"
  />
</template>

<script setup lang="ts">
/** DECIMAL(20,6) input keeps the original decimal text through save and edit. */
defineProps<{ modelValue?: string | number | null }>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()
const update = (value: string | number) => emit('update:modelValue', String(value))
</script>
