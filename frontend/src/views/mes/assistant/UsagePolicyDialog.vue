<template>
  <el-dialog
    v-model="visible"
    title="模型调用预算"
    width="900px"
    :close-on-click-modal="false"
    :before-close="beforeClose"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" v-if="form">
      <p
        >知识问答、业务模型分析、Jev及连接测试共享次数、频率和并发配额。Token配额仅覆盖聊天模型；价格按管理员配置估算，币种为人民币。</p
      >
      <el-checkbox v-model="form.enabled" :disabled="saving"
        >启用预算并允许符合配额的外部调用</el-checkbox
      >
      <el-form label-width="155px" @submit.prevent :disabled="saving">
        <div class="usage-limit-groups">
          <section v-for="group in groups" :key="group.key">
            <h4>{{ group.name }}</h4>
            <el-form-item
              v-for="field in countFields"
              :key="field.key"
              :label="field.name"
              required
            >
              <el-input v-model="form[group.key][field.key]" inputmode="numeric" maxlength="19" />
            </el-form-item>
            <el-form-item v-for="field in moneyFields" :key="field.key" :label="field.name">
              <el-checkbox
                :model-value="form[group.key][field.key] !== null"
                @change="toggleAmount(group.key, field.key, $event)"
                >启用</el-checkbox
              >
              <el-input
                v-if="form[group.key][field.key] !== null"
                v-model="form[group.key][field.key]"
                inputmode="decimal"
                maxlength="25"
                placeholder="人民币金额，最多8位小数"
              />
            </el-form-item>
            <el-form-item label="最大并发调用数" required
              ><el-input-number
                v-model="form[group.key].maxConcurrency"
                :min="1"
                :max="128"
                :precision="0"
            /></el-form-item>
            <el-form-item label="每分钟调用数" required
              ><el-input-number
                v-model="form[group.key].callsPerMinute"
                :min="1"
                :max="10000"
                :precision="0"
            /></el-form-item>
          </section>
        </div>
        <h4>明确配置价格卡</h4>
        <p
          >启用金额上限时，当前服务和模型须有匹配价格。未知用量保留预留金额；修改模型、密钥或预算不会清空累计用量。零单价表示管理员明确配置为免费。</p
        >
        <section v-for="(rate, i) in form.rates" :key="i" class="usage-price-card">
          <el-form-item label="通道"
            ><el-select v-model="rate.channel" @change="changeChannel(rate)"
              ><el-option label="聊天模型" value="CHAT" /><el-option
                label="Jev"
                value="JEV" /></el-select
          ></el-form-item>
          <el-form-item label="服务"
            ><el-select v-model="rate.provider"
              ><template v-if="rate.channel === 'CHAT'"
                ><el-option label="OpenAI兼容" value="openai" /><el-option
                  label="Ollama"
                  value="ollama" /></template
              ><el-option v-else label="TypeSafe" value="typesafe" /></el-select
          ></el-form-item>
          <el-form-item label="准确模型名称" required
            ><el-input v-model="rate.model" maxlength="200"
          /></el-form-item>
          <template v-if="rate.channel === 'CHAT'"
            ><el-form-item label="输入价格／百万Token" required
              ><el-input
                v-model="rate.inputPerMillion"
                inputmode="decimal"
                maxlength="25" /></el-form-item
            ><el-form-item label="输出价格／百万Token" required
              ><el-input
                v-model="rate.outputPerMillion"
                inputmode="decimal"
                maxlength="25" /></el-form-item
          ></template>
          <el-form-item v-else label="单次预留金额" required
            ><el-input v-model="rate.perCallReservationAmount" inputmode="decimal" maxlength="25"
          /></el-form-item>
          <el-button type="danger" plain :disabled="saving" @click="form.rates.splice(i, 1)"
            >移除此价格卡</el-button
          >
        </section>
        <el-button :disabled="saving || form.rates.length >= 100" @click="addRate"
          >添加价格卡</el-button
        >
        <el-form-item label="变更依据" required class="usage-reason"
          ><el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="记录本次设置的依据，勿填写密钥或业务明细"
        /></el-form-item>
      </el-form>
      <p
        >统计时区：Asia/Shanghai。计量启用前的历史用量未知。当前版本：{{
          form.expectedVersion
        }}。</p
      >
    </div>
    <template #footer
      ><el-button :disabled="saving" @click="close">取消</el-button
      ><el-button
        type="primary"
        :loading="saving"
        :disabled="loading || !form || !canConfig"
        @click="save"
        >保存预算</el-button
      ></template
    >
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { AssistantApi } from '@/api/mes/assistant'
import type { UsagePolicySave, UsagePriceCard } from '@/api/mes/assistant/usage'
import { checkPermi } from '@/utils/permission'
import { policyForm, policyRequest } from './usagePolicy'

const emit = defineEmits<{ saved: [] }>()
const canConfig = computed(() => checkPermi(['mes:assistant:config']))
const visible = ref(false),
  loading = ref(false),
  saving = ref(false),
  error = ref('')
const form = ref<UsagePolicySave>()
let epoch = 0
const groups = [
  { key: 'tenantLimits', name: '当前租户总配额' },
  { key: 'defaultUserLimits', name: '每位用户的配额' }
] as const
const countFields = [
  { key: 'dayCalls', name: '每日调用次数' },
  { key: 'monthCalls', name: '每月调用次数' },
  { key: 'dayTokens', name: '每日聊天Token' },
  { key: 'monthTokens', name: '每月聊天Token' }
] as const
const moneyFields = [
  { key: 'dayAmount', name: '每日金额上限' },
  { key: 'monthAmount', name: '每月金额上限' }
] as const
function close() {
  epoch++
  visible.value = false
  form.value = undefined
  error.value = ''
  loading.value = false
}
function beforeClose(done: () => void) {
  if (!saving.value) {
    close()
    done()
  }
}
async function open() {
  if (!canConfig.value || saving.value) return
  close()
  visible.value = true
  loading.value = true
  const token = ++epoch
  try {
    const policy = await AssistantApi.usagePolicy()
    if (token === epoch && visible.value && canConfig.value) form.value = policyForm(policy)
  } catch (e) {
    if (token === epoch) error.value = e instanceof Error ? e.message : '预算加载失败'
  } finally {
    if (token === epoch) loading.value = false
  }
}
function toggleAmount(
  group: 'tenantLimits' | 'defaultUserLimits',
  field: 'dayAmount' | 'monthAmount',
  enabled: unknown
) {
  if (form.value && !saving.value) form.value[group][field] = enabled === true ? '' : null
}
function addRate() {
  if (form.value && !saving.value && form.value.rates.length < 100)
    form.value.rates.push({
      channel: 'CHAT',
      provider: 'openai',
      model: '',
      inputPerMillion: '',
      outputPerMillion: '',
      perCallReservationAmount: null
    })
}
function changeChannel(rate: UsagePriceCard) {
  rate.provider = rate.channel === 'CHAT' ? 'openai' : 'typesafe'
  rate.inputPerMillion = rate.channel === 'CHAT' ? '' : null
  rate.outputPerMillion = rate.channel === 'CHAT' ? '' : null
  rate.perCallReservationAmount = rate.channel === 'JEV' ? '' : null
}
async function save() {
  if (!canConfig.value || saving.value || loading.value || !visible.value || !form.value) return
  const token = epoch
  error.value = ''
  try {
    const data = policyRequest(form.value)
    saving.value = true
    await AssistantApi.saveUsagePolicy(data)
    if (token === epoch && canConfig.value) {
      close()
      emit('saved')
    }
  } catch (e) {
    if (token === epoch)
      error.value = e instanceof Error ? e.message : '预算保存失败，请重新加载核对版本'
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

<style scoped>
.usage-limit-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.usage-price-card {
  padding: 16px;
  margin: 12px 0;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}

.usage-reason {
  margin-top: 24px;
}

p {
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

@media (width <= 700px) {
  .usage-limit-groups {
    grid-template-columns: 1fr;
  }
}
</style>
