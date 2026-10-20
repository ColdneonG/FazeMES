<template>
  <Dialog v-model="visible" :title="readonly ? '损耗结案记录' : '损耗结案'" width="620px">
    <div v-loading="loading">
      <el-alert
        v-if="!readonly"
        title="仅在各工序流转数量核对完成、待检已处理、线边库存已清理后结案。原计划和报工记录保留，成本需另外核定。"
        type="warning"
        :closable="false"
        class="mb-4"
      />
      <el-descriptions v-if="preview" :column="1" border>
        <el-descriptions-item label="原计划数量">{{
          preview.plannedQuantity
        }}</el-descriptions-item>
        <el-descriptions-item label="末道完成报工数量">{{
          preview.producedQuantity
        }}</el-descriptions-item>
        <el-descriptions-item label="末道合格数量">{{
          preview.qualifiedQuantity
        }}</el-descriptions-item>
        <el-descriptions-item label="前序损耗数量">{{ preview.lossQuantity }}</el-descriptions-item>
        <template v-if="readonly && record">
          <el-descriptions-item label="结案原因">{{ record.reason }}</el-descriptions-item>
          <el-descriptions-item label="结案人编号">{{ record.closedBy }}</el-descriptions-item>
          <el-descriptions-item label="结案时间">{{
            formatDate(record.closedAt)
          }}</el-descriptions-item>
        </template>
      </el-descriptions>
      <el-empty v-else-if="readonly && !loading" description="该工单没有损耗结案记录" />
      <el-form v-if="!readonly && preview" ref="formRef" :model="form" class="mt-4">
        <el-form-item
          label="结案原因"
          prop="reason"
          :rules="[
            {
              required: true,
              whitespace: true,
              message: '请填写损耗原因及处理依据',
              trigger: 'blur'
            }
          ]"
        >
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="填写损耗原因、处置结果和核对依据"
          />
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button v-if="!readonly" type="warning" :disabled="loading || !preview" @click="submit">
        确认损耗结案
      </el-button>
      <el-button :disabled="loading" @click="visible = false">关闭</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import {
  ProWorkOrderApi,
  type LossClosurePreview,
  type LossClosureRecord
} from '@/api/mes/pro/workorder'
import { formatDate } from '@/utils/formatTime'

const emit = defineEmits(['success'])
const message = useMessage()
const visible = ref(false)
const readonly = ref(false)
const loading = ref(false)
const orderId = ref<number>()
const preview = ref<LossClosurePreview>()
const record = ref<LossClosureRecord>()
const form = reactive({ reason: '' })
const formRef = ref()

const open = async (id: number, viewOnly = false) => {
  orderId.value = id
  readonly.value = viewOnly
  preview.value = undefined
  record.value = undefined
  form.reason = ''
  visible.value = true
  loading.value = true
  try {
    if (viewOnly) {
      record.value = (await ProWorkOrderApi.getLossRecord(id)) || undefined
      preview.value = record.value
    } else {
      preview.value = await ProWorkOrderApi.getLossPreview(id)
    }
  } catch (error) {
    visible.value = false
    throw error
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  if (!preview.value || !orderId.value) return
  await formRef.value.validate()
  const confirmed = await message
    .confirm('确认按上述数量损耗结案？结案后不能继续报工，原计划与损耗原因将保留。')
    .then(
      () => true,
      () => false
    )
  if (!confirmed) return
  loading.value = true
  try {
    await ProWorkOrderApi.closeWithLoss({
      id: orderId.value,
      expectedProducedQuantity: preview.value.producedQuantity,
      reason: form.reason.trim()
    })
    message.success('损耗结案完成')
    visible.value = false
    emit('success')
  } finally {
    loading.value = false
  }
}

defineExpose({ open })
</script>
