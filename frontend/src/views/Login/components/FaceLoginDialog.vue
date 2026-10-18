<script setup lang="ts">
import FaceCapture from '@/views/mes/face/FaceCapture.vue'
import { FaceApi } from '@/api/mes/face'
import type { TokenType } from '@/api/login/types'
const props = defineProps<{ modelValue: boolean; username: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; success: [value: TokenType] }>()
const working = ref(false)
const error = ref('')
let generation = 0
const capture = async (image: string) => {
  working.value = true
  error.value = ''
  const current = ++generation
  try {
    const challenge = await FaceApi.challenge(props.username.trim())
    if (current !== generation || !props.modelValue) return
    const result = await FaceApi.login(props.username.trim(), challenge.challenge, image)
    if (current === generation && props.modelValue) {
      emit('update:modelValue', false)
      emit('success', result)
    }
  } catch {
    if (current === generation)
      error.value = '核验未通过：请确认已录入本人人脸、画面清晰，或使用密码登录。'
  } finally {
    if (current === generation) working.value = false
  }
}
watch(
  () => props.modelValue,
  (value) => {
    if (!value) {
      generation++
      working.value = false
      error.value = ''
    }
  }
)
onBeforeUnmount(() => {
  generation++
})
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="本人人脸登录"
    width="min(600px, 94vw)"
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p
      >核验账号：<strong>{{ username }}</strong
      >。请使用已录入的本人人脸；本次画面仅送至本地服务处理。</p
    >
    <FaceCapture
      :active="modelValue"
      :disabled="working"
      :capture-label="working ? '正在核验…' : '开始人脸核验'"
      @capture="capture"
    />
    <el-alert v-if="error" class="mt-16px" :title="error" type="error" :closable="false" />
    <p class="face-login-note"
      >尚未录入时，请先密码登录，在 MES 系统 → 本人人脸登录中完成绑定。该实现不含硬件活体检测。</p
    >
  </el-dialog>
</template>
<style scoped>
.face-login-note {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
</style>
