<template>
  <el-dialog
    v-model="visible"
    title="核定模型调用用量"
    width="680px"
    :close-on-click-modal="false"
    :before-close="beforeClose"
  >
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <template v-if="row">
      <p
        >{{ purposeNames[row.purpose] }} · {{ row.provider }} / {{ row.model }}<br />调用ID：{{
          row.id
        }}
        · 版本 {{ row.version }}</p
      >
      <p
        >服务原报输入／输出Token：{{ measured(row.actualInputTokens) }}／{{
          measured(row.actualOutputTokens)
        }}。原报数据会保留，核定追加审计记录。请根据服务端账单或其他可核实证据操作。</p
      >
      <el-form label-width="145px" :disabled="saving" @submit.prevent>
        <el-form-item label="核定方式"
          ><el-select v-model="action"
            ><el-option label="关闭未完成调用，保留待核定额度" value="CLOSE_AS_UNKNOWN" /><el-option
              label="确认实际用量"
              value="CONFIRM_ACTUAL" /><el-option
              label="有依据确认未发送"
              value="CONFIRM_NOT_DISPATCHED" /></el-select
        ></el-form-item>
        <el-alert :title="actionNotice" type="info" :closable="false" show-icon />
        <template v-if="action === 'CONFIRM_ACTUAL'">
          <el-form-item label="实际输入Token" :required="row.channel === 'CHAT'"
            ><el-input
              v-model="inputTokens"
              inputmode="numeric"
              maxlength="19"
              placeholder="留空为未知，0须明确填写"
          /></el-form-item>
          <el-form-item label="实际输出Token" :required="row.channel === 'CHAT'"
            ><el-input v-model="outputTokens" inputmode="numeric" maxlength="19"
          /></el-form-item>
          <el-form-item label="确认人民币金额"
            ><el-input
              v-model="amount"
              inputmode="decimal"
              maxlength="25"
              placeholder="留空保留未知；0表示有依据确认为0"
          /></el-form-item>
        </template>
        <el-form-item label="核定依据" required
          ><el-input
            v-model="reason"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="说明证据和核定依据，勿填写密钥或业务内容"
        /></el-form-item>
      </el-form>
    </template>
    <template #footer
      ><el-button :disabled="saving" @click="close">取消</el-button
      ><el-button
        type="primary"
        :loading="saving"
        :disabled="!row?.canReconcile || !canConfig"
        @click="save"
        >提交核定</el-button
      ></template
    >
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { AssistantApi } from '@/api/mes/assistant'
import type { UsageReconcile, UsageRow } from '@/api/mes/assistant/usage'
import { checkPermi } from '@/utils/permission'
import { measured, purposeNames, reconcileRequest } from './usagePolicy'
const emit = defineEmits<{ saved: [] }>()
const canConfig = computed(() => checkPermi(['mes:assistant:config']))
const visible = ref(false),
  saving = ref(false),
  error = ref(''),
  row = ref<UsageRow>()
const action = ref<UsageReconcile['action']>('CLOSE_AS_UNKNOWN')
const inputTokens = ref(''),
  outputTokens = ref(''),
  amount = ref(''),
  reason = ref('')
let epoch = 0
const actionNotice = computed(() =>
  action.value === 'CLOSE_AS_UNKNOWN'
    ? '仅允许关闭已超时且不在传输中的调用；释放并发占用，保留Token和金额占用。'
    : action.value === 'CONFIRM_ACTUAL'
      ? '服务原报与人工核定分别保留；两者不一致时，额度按较大值计入。金额缺失时不会自动释放未知金额。'
      : '仅在能够证明未发送且没有相反服务响应时使用；每分钟已接纳次数保留。'
)
function close() {
  epoch++
  visible.value = false
  row.value = undefined
  error.value = ''
  inputTokens.value = ''
  outputTokens.value = ''
  amount.value = ''
  reason.value = ''
}
function beforeClose(done: () => void) {
  if (!saving.value) {
    close()
    done()
  }
}
function open(current: UsageRow) {
  if (!canConfig.value || saving.value || !current.canReconcile) return
  close()
  row.value = { ...current }
  action.value = 'CLOSE_AS_UNKNOWN'
  visible.value = true
}
async function save() {
  if (!canConfig.value || saving.value || !visible.value || !row.value) return
  const token = epoch
  error.value = ''
  try {
    const data = reconcileRequest(
      row.value,
      action.value,
      inputTokens.value,
      outputTokens.value,
      amount.value,
      reason.value
    )
    saving.value = true
    await AssistantApi.reconcileUsage(data)
    if (token === epoch && canConfig.value) {
      close()
      emit('saved')
    }
  } catch (e) {
    if (token === epoch) error.value = e instanceof Error ? e.message : '核定失败，请刷新记录后重试'
  } finally {
    saving.value = false
  }
}
watch(canConfig, (allowed) => {
  if (!allowed) close()
})
onBeforeUnmount(close)
defineExpose({ open, close })
</script>
