<template>
  <Dialog
    v-model="visible"
    :title="kind === 'receipt' ? '录入采购入库行实际单价' : '录入销售出库行成交单价'"
    width="600px"
  >
    <p>{{ itemLabel }}</p>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="
        kind === 'receipt'
          ? '请依据实际采购单据填写人民币不含税单价，仅草稿可修改；此处不使用物料参考价自动填充。'
          : '请依据客户订单填写人民币不含税成交价，仅草稿可修改；标准售价不会自动当作实际成交价。'
      "
      class="mb-16px"
    />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="单价（元）" prop="unitPrice"
        ><el-input
          v-model="form.unitPrice"
          inputmode="decimal"
          clearable
          placeholder="最多 6 位小数"
      /></el-form-item>
      <el-form-item label="定价依据" prop="remark"
        ><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存单据行价格</el-button>
    </template>
  </Dialog>
</template>
<script setup lang="ts">
import { CostApi, type CostDecimal } from '@/api/mes/cost'
import { optionalAmount, decimalRule } from '../format'

const emit = defineEmits<{ saved: [] }>()
const message = useMessage()
const visible = ref(false)
const saving = ref(false)
const kind = ref<'receipt' | 'sales'>('receipt')
const itemLabel = ref('')
const formRef = ref()
const form = reactive<{ lineId: number; unitPrice?: string; remark: string }>({
  lineId: 0,
  remark: ''
})
const rules = {
  unitPrice: [
    decimalRule(12, 6, true),
    {
      required: true,
      message: '请填写单价；赠品等零价情形请明确填 0 并说明依据',
      trigger: 'change'
    }
  ],
  remark: [{ required: true, whitespace: true, message: '请填写实际价格依据', trigger: 'blur' }]
}
const open = (
  mode: 'receipt' | 'sales',
  line: {
    lineId: number
    itemCode: string
    itemName: string
    unitPrice: CostDecimal
    remark?: string
  }
) => {
  kind.value = mode
  itemLabel.value = `${line.itemCode} · ${line.itemName}`
  Object.assign(form, {
    lineId: line.lineId,
    unitPrice: optionalAmount(line.unitPrice),
    remark: line.remark ?? ''
  })
  visible.value = true
  nextTick(() => formRef.value?.clearValidate())
}
const save = async () => {
  if (saving.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = { lineId: form.lineId, unitPrice: form.unitPrice!, remark: form.remark.trim() }
    if (kind.value === 'receipt') await CostApi.saveReceiptLinePrice(payload)
    else await CostApi.saveSalesLinePrice(payload)
    visible.value = false
    message.success('单据行价格已保存')
    emit('saved')
  } catch {
    // Keep the original input available after a rejected save or lost response.
  } finally {
    saving.value = false
  }
}
defineExpose({ open })
</script>
