<script setup lang="ts">
import { createFaceCamera, type CameraState } from './faceCamera'
const props = withDefaults(
  defineProps<{ active?: boolean; disabled?: boolean; captureLabel?: string }>(),
  { active: true, disabled: false, captureLabel: '采集当前画面' }
)
const emit = defineEmits<{ capture: [image: string] }>()
const video = ref<HTMLVideoElement>()
const state = ref<CameraState>({
  ready: false,
  loading: false,
  message: '点击开启摄像头后，仅在本机核验画面'
})
const camera = createFaceCamera(
  () => video.value,
  (value) => {
    state.value = value
  }
)
const capture = () => {
  try {
    emit('capture', camera.capture())
  } catch (error) {
    state.value.message = (error as Error).message
  }
}
watch(
  () => props.active,
  (value) => {
    if (!value) camera.stop()
  }
)
onDeactivated(camera.stop)
onBeforeUnmount(camera.dispose)
defineExpose({ stop: camera.stop })
</script>
<template>
  <div class="face-capture">
    <div class="camera-frame"
      ><video ref="video" autoplay playsinline muted aria-label="本人人脸摄像头预览"></video
      ><span class="face-guide" aria-hidden="true"></span
    ></div>
    <p role="status">{{ state.message }}</p>
    <div class="capture-actions">
      <el-button
        :loading="state.loading"
        :disabled="disabled || state.loading"
        @click="camera.start()"
        >开启摄像头</el-button
      >
      <el-button type="primary" :disabled="!state.ready || disabled" @click="capture">{{
        captureLabel
      }}</el-button>
      <el-button :disabled="disabled" @click="camera.stop()">关闭摄像头</el-button>
    </div>
  </div>
</template>
<style scoped>
.camera-frame {
  position: relative;
  width: 100%;
  max-width: 640px;
  aspect-ratio: 4 / 3;
  margin: auto;
  overflow: hidden;
  background: #0c192a;
  border-radius: 12px;
}

.camera-frame video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  transform: scaleX(-1);
}

.face-guide {
  position: absolute;
  inset: 14% 27%;
  pointer-events: none;
  border: 2px dashed #54c9dc;
  border-radius: 48%;
}

.face-capture p {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.capture-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
</style>
