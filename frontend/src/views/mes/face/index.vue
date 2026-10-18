<script setup lang="ts">
import FaceCapture from './FaceCapture.vue'
import { FaceApi, type FaceStatus } from '@/api/mes/face'
defineOptions({ name: 'MesFaceEnrollment' })
const status = ref<FaceStatus>()
const password = ref('')
const consent = ref(false)
const images = ref<string[]>([])
const saving = ref(false)
const message = useMessage()
const load = async () => {
  status.value = await FaceApi.status()
}
const capture = (image: string) => {
  if (images.value.length < 3) images.value.push(image)
}
const save = async () => {
  if (!password.value || !consent.value || !images.value.length) {
    message.warning('请采集本人画面、输入当前密码并确认授权')
    return
  }
  saving.value = true
  try {
    await FaceApi.enroll(password.value, images.value, consent.value)
    images.value = []
    password.value = ''
    message.success('本人人脸已录入')
    await load()
  } finally {
    saving.value = false
  }
}
const disable = async () => {
  if (!password.value) {
    message.warning('请输入当前账号密码确认停用')
    return
  }
  saving.value = true
  try {
    await FaceApi.disable(password.value)
    password.value = ''
    images.value = []
    message.success('人脸特征已清除')
    await load()
  } finally {
    saving.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => {
  images.value = []
  password.value = ''
})
</script>
<template>
  <ContentWrap title="本人人脸登录">
    <el-alert
      title="先使用密码登录，再为当前账号录入本人人脸。登录时填写账号后进行一对一核验。"
      type="info"
      :closable="false"
    />
    <div class="face-settings">
      <div
        ><FaceCapture :disabled="saving || images.length >= 3" @capture="capture" /><div
          class="sample-actions"
          ><span>已采集 {{ images.length }} / 3 张，仅暂存在本页面</span
          ><el-button text @click="images = []">重新采集</el-button></div
        ></div
      >
      <section class="enrollment-settings">
        <h3>当前账号绑定</h3>
        <p
          ><el-tag :type="status?.ready ? 'success' : 'warning'">{{
            status?.ready ? '本地模型已就绪' : '本地模型未就绪'
          }}</el-tag>
          <el-tag :type="status?.enrolled ? 'success' : 'info'">{{
            status?.enrolled ? '已录入' : '尚未录入'
          }}</el-tag></p
        >
        <p>采用 SFace + YuNet，只核验你填写的账号，不检索其他人员身份。该实现不含硬件活体检测。</p>
        <p>画面发送至本地服务提取特征后即丢弃；特征加密保存，可随时停用并清除。</p>
        <el-input
          v-model="password"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="输入当前账号密码确认操作"
          :disabled="saving"
        />
        <el-checkbox v-model="consent" class="consent"
          >我确认画面为本人，并授权将本人人脸特征用于此账号登录核验。</el-checkbox
        >
        <div class="enrollment-actions"
          ><el-button type="primary" :loading="saving" :disabled="!status?.ready" @click="save"
            >录入 / 更新本人绑定</el-button
          ><el-button
            type="danger"
            plain
            :loading="saving"
            :disabled="!status?.enrolled"
            @click="disable"
            >停用并清除特征</el-button
          ></div
        >
      </section>
    </div>
  </ContentWrap>
</template>
<style scoped>
.face-settings {
  display: grid;
  grid-template-columns: minmax(300px, 1.2fr) minmax(280px, 1fr);
  gap: 30px;
  padding: 24px 0;
}

.sample-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.enrollment-settings p {
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.consent {
  height: auto;
  margin: 18px 0;
}

.consent :deep(.el-checkbox__label) {
  line-height: 1.7;
  white-space: normal;
}

.enrollment-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

@media (width <= 900px) {
  .face-settings {
    grid-template-columns: 1fr;
  }
}
</style>
