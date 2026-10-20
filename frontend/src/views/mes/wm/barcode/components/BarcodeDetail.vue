<template><LabelPrintDialog ref="printDialogRef" /></template>
<script setup lang="ts">
import { ref } from 'vue'
import type { WmBarcodeVO } from '@/api/mes/wm/barcode'
import { positiveId } from '../labelPrint'
import LabelPrintDialog from './LabelPrintDialog.vue'

defineOptions({ name: 'BarcodeDetail' })
const message = useMessage()
const printDialogRef = ref<InstanceType<typeof LabelPrintDialog>>()
const open = (row: Partial<WmBarcodeVO>) => {
  if (positiveId(row.id)) return printDialogRef.value?.open([{ barcodeId: row.id }])
  if (positiveId(row.bizId) && row.bizType) return openByBusiness(row.bizId, row.bizType)
  message.warning('缺少真实条码或业务编号，请刷新后重试')
}
const openByBusiness = (bizId: number, bizType: number, _bizCode?: string, _bizName?: string) =>
  printDialogRef.value?.open([{ bizType, bizId }])
defineExpose({ open, openByBusiness })
</script>
