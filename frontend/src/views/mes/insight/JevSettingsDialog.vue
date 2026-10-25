<template>
  <el-dialog v-model="visible" title="Jev 异常分流配置" width="min(640px,95vw)">
    <el-alert
      title="Jev使用独立TypeSafe API Key。后端自动读取Windows系统HTTP代理；Clash开启系统代理后，无需开启虚拟网卡。修改系统代理后约3秒生效，无需重启项目。"
      type="info"
      :closable="false"
    />
    <el-form label-position="top" class="mt-4" @submit.prevent>
      <el-form-item label="当前连接方式"
        ><span>{{ settings.transport || '正在读取' }}</span></el-form-item
      >
      <el-form-item label="官方决策接口"
        ><el-input :model-value="settings.endpoint" disabled
      /></el-form-item>
      <el-form-item label="模型版本"
        ><el-select v-model="settings.model"
          ><el-option
            v-for="model in models"
            :key="model"
            :value="model"
            :label="model" /></el-select
        ><p>试点建议固定版本；更换版本后应重新评估效果。</p></el-form-item
      >
      <el-form-item label="TypeSafe API Key"
        ><el-input
          v-model="apiKey"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="填写Jev专用密钥，留空保留已保存密钥"
        /><p>{{
          settings.apiKeyConfigured
            ? '已加密保存密钥，页面不会读取密钥原文。'
            : '尚未保存密钥。千问API Key不能用于Jev。'
        }}</p></el-form-item
      >
      <el-form-item
        ><el-checkbox v-model="clearApiKey">清除已保存的Jev密钥</el-checkbox></el-form-item
      >
      <el-form-item label="启用异常建议"><el-switch v-model="settings.enabled" /></el-form-item>
    </el-form>
    <el-alert
      v-if="message"
      :title="message"
      :type="success ? 'success' : 'error'"
      :closable="false"
    />
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button :loading="testing" :disabled="saving || loading" @click="test"
        >测试已保存配置</el-button
      >
      <el-button type="primary" :loading="saving" :disabled="testing || loading" @click="save"
        >保存配置</el-button
      >
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { InsightApi, type JevSettings } from '@/api/mes/insight'
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ saved: [] }>()
const models = ['jev-1.13.0', 'jev-1.14.0', 'jev-latest', 'jev-preview']
const settings = ref<JevSettings>({
  endpoint: 'https://api.typesafe.ai/v1/systemone',
  model: 'jev-1.13.0',
  enabled: false,
  configured: false,
  apiKeyConfigured: false
})
const apiKey = ref(''),
  clearApiKey = ref(false),
  saving = ref(false),
  testing = ref(false),
  loading = ref(false),
  message = ref(''),
  success = ref(false)
const errorMessage = (e: unknown) =>
  e instanceof Error ? e.message : typeof e === 'string' ? e : '操作失败，请检查网络和配置'
watch(visible, async (open) => {
  apiKey.value = ''
  clearApiKey.value = false
  message.value = ''
  if (!open) return
  loading.value = true
  try {
    settings.value = await InsightApi.jevSettings()
  } catch (e) {
    message.value = errorMessage(e)
    success.value = false
  } finally {
    loading.value = false
  }
})
async function save() {
  saving.value = true
  message.value = ''
  try {
    settings.value = await InsightApi.saveJevSettings({
      model: settings.value.model,
      apiKey: apiKey.value,
      clearApiKey: clearApiKey.value,
      enabled: settings.value.enabled
    })
    apiKey.value = ''
    clearApiKey.value = false
    success.value = true
    message.value = '配置已保存；连接是否可用请点击测试。'
    emit('saved')
  } catch (e) {
    success.value = false
    message.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
async function test() {
  testing.value = true
  message.value = ''
  try {
    const result = await InsightApi.testJev()
    success.value = true
    message.value = `${result.message}（${result.model}）`
  } catch (e) {
    success.value = false
    message.value = errorMessage(e)
  } finally {
    testing.value = false
  }
}
</script>
