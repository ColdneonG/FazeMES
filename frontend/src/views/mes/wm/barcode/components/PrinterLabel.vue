<template>
  <el-button type="primary" link @click="handlePrint" v-hasPermi="['mes:wm-barcode:query']">
    {{ labelText }}
  </el-button>
  <LabelPrintDialog ref="printDialogRef" />
</template>
<script lang="ts" setup>
import { ref, watch } from 'vue'
import { barcodeBusinessType, positiveId } from '../labelPrint'
import LabelPrintDialog from './LabelPrintDialog.vue'

defineOptions({ name: 'PrinterLabel' })
const props = defineProps({
  bizId: { type: Number, default: undefined },
  bizCode: { type: String, default: undefined },
  bizType: { type: [String, Number], default: undefined },
  labelText: { type: String, default: '标签打印' }
})
const message = useMessage()
const printDialogRef = ref<InstanceType<typeof LabelPrintDialog>>()
const handlePrint = () => {
  const bizType = props.bizType === undefined ? undefined : barcodeBusinessType(props.bizType)
  if (!positiveId(props.bizId) || !bizType) {
    message.warning('请选择有效业务对象后打印标签')
    return
  }
  return printDialogRef.value?.open([{ bizType, bizId: props.bizId }])
}
watch(
  () => [props.bizId, props.bizType],
  () => printDialogRef.value?.close()
)
</script>
