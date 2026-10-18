<template>
  <div v-loading="loading" class="sop-execution">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-else-if="pkg">
      <el-alert
        v-if="!pkg.required"
        title="本任务未配置受控SOP，可按现有作业要求报工。"
        type="info"
        :closable="false"
      />
      <template v-else>
        <div class="sop-heading">
          <div
            ><strong>{{ pkg.title }}</strong
            ><el-tag class="ml-10px">版本 {{ pkg.versionNo }}</el-tag></div
          >
          <el-button @click="load">刷新进度</el-button>
        </div>
        <el-alert
          title="此任务使用创建时锁定的版本。请先查看步骤，按要求记录参数及照片，再逐项确认。"
          type="info"
          :closable="false"
        />
        <div class="sop-files">
          <el-link
            v-for="(url, index) in pkg.attachments"
            :key="url"
            :href="url"
            target="_blank"
            rel="noopener noreferrer"
            type="primary"
            >资料 {{ index + 1 }}</el-link
          >
          <el-link
            v-if="pkg.modelUrl"
            :href="pkg.modelUrl"
            target="_blank"
            rel="noopener noreferrer"
            type="primary"
            >下载GLB模型</el-link
          >
        </div>
        <el-progress :percentage="progress" :status="progress === 100 ? 'success' : undefined" />
        <el-card v-for="(step, index) in pkg.steps" :key="step.id" shadow="never" class="sop-step">
          <template #header>
            <div class="sop-heading">
              <strong>{{ index + 1 }}. {{ step.title }}</strong>
              <div
                ><el-tag :type="step.required ? 'warning' : 'info'">{{
                  step.required ? '必做' : '选做'
                }}</el-tag
                ><el-tag v-if="record(step.id)?.confirmedAt" type="success" class="ml-8px"
                  >已确认</el-tag
                ></div
              >
            </div>
          </template>
          <div class="sop-instruction">{{ step.instruction }}</div>
          <div class="sop-files"
            ><el-link
              v-for="(url, fileIndex) in step.attachments"
              :key="url"
              :href="url"
              target="_blank"
              rel="noopener noreferrer"
              type="primary"
              >步骤资料 {{ fileIndex + 1 }}</el-link
            ></div
          >
          <template v-if="record(step.id)?.confirmedAt">
            <p
              >确认人：{{ record(step.id)?.confirmedBy }} ·
              {{ formatDate(record(step.id)?.confirmedAt) }}</p
            >
            <p v-for="param in step.parameters" :key="param.key">
              {{ param.label }}：{{ confirmedValues(step.id)[param.key] ?? '未填写' }}
              {{ param.unit || '' }}
            </p>
            <div class="sop-files"
              ><el-link
                v-for="(url, photoIndex) in confirmedPhotos(step.id)"
                :key="url"
                :href="url"
                target="_blank"
                rel="noopener noreferrer"
                type="primary"
                >现场照片 {{ photoIndex + 1 }}</el-link
              ></div
            >
          </template>
          <template v-else>
            <p class="sop-timing"
              >最短查看 {{ step.minViewSeconds }} 秒<span v-if="record(step.id)?.viewedAt">
                · 已开始查看，剩余 {{ remaining(step) }} 秒</span
              ></p
            >
            <el-button
              v-if="!record(step.id)?.viewedAt"
              v-hasPermi="['mes:sop:execute']"
              type="primary"
              plain
              :loading="busy === step.id"
              @click="start(step)"
              >开始查看此步骤</el-button
            >
            <el-form v-else label-position="top" class="mt-12px">
              <el-form-item
                v-for="param in step.parameters"
                :key="param.key"
                :label="`${param.label}${param.unit ? ` (${param.unit})` : ''}${param.required ? ' *' : ''}`"
              >
                <el-input-number
                  v-model="values[step.id!][param.key]"
                  :min="param.min"
                  :max="param.max"
                  :precision="4"
                />
                <span class="ml-8px">{{ param.min ?? '不限' }} ～ {{ param.max ?? '不限' }}</span>
              </el-form-item>
              <el-form-item :label="step.photoRequired ? '现场照片（必填）' : '现场照片（选填）'">
                <UploadFile
                  v-model="photos[step.id!]"
                  :file-type="['jpg', 'jpeg', 'png', 'webp']"
                  :limit="10"
                />
              </el-form-item>
              <el-button
                v-hasPermi="['mes:sop:execute']"
                type="success"
                :disabled="remaining(step) > 0"
                :loading="busy === step.id"
                @click="confirm(step)"
                >确认完成步骤</el-button
              >
            </el-form>
          </template>
        </el-card>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { SopApi, type SopTaskPackage, type SopStep } from '@/api/mes/pro/sop'
import { UploadFile } from '@/components/UploadFile'
import { formatDate } from '@/utils/formatTime'

const props = defineProps<{ taskId: number }>()
const emit = defineEmits<{ changed: []; completed: [] }>()
const message = useMessage()
const loading = ref(false)
const busy = ref<string>()
const error = ref('')
const pkg = ref<SopTaskPackage>()
const values = reactive<Record<string, Record<string, number>>>({})
const photos = reactive<Record<string, string[]>>({})
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
const record = (id?: string) => pkg.value?.records.find((row) => row.stepId === id)
const remaining = (step: SopStep) => {
  const viewedAt = record(step.id)?.viewedAt
  return Math.max(
    0,
    step.minViewSeconds -
      (viewedAt ? Math.max(0, Math.floor((now.value - new Date(viewedAt).getTime()) / 1000)) : 0)
  )
}
const confirmedPhotos = (id?: string): string[] => JSON.parse(record(id)?.photosJson || '[]')
const confirmedValues = (id?: string): Record<string, number> =>
  JSON.parse(record(id)?.parametersJson || '{}')
const progress = computed(() => {
  const required = pkg.value?.steps.filter((step) => step.required) || []
  return required.length
    ? Math.round(
        (required.filter((step) => record(step.id)?.confirmedAt).length / required.length) * 100
      )
    : 100
})
const load = async () => {
  const taskId = props.taskId
  loading.value = true
  error.value = ''
  try {
    const result = await SopApi.task(taskId)
    if (taskId !== props.taskId) return
    pkg.value = result
    for (const step of result.steps) {
      values[step.id!] ||= {}
      photos[step.id!] ||= []
    }
    emit('changed')
    if (!result.required || progress.value === 100) emit('completed')
  } catch (err: any) {
    if (taskId === props.taskId) {
      pkg.value = undefined
      error.value = err?.message || 'SOP加载失败，请刷新重试。'
    }
  } finally {
    loading.value = false
  }
}
const start = async (step: SopStep) => {
  busy.value = step.id
  try {
    await SopApi.view(props.taskId, step.id!)
    await load()
  } finally {
    busy.value = undefined
  }
}
const confirm = async (step: SopStep) => {
  busy.value = step.id
  try {
    const parameters = Object.fromEntries(
      Object.entries(values[step.id!] || {}).filter(([, value]) => typeof value === 'number')
    )
    await SopApi.confirm(props.taskId, step.id!, parameters, photos[step.id!])
    message.success('步骤已确认')
    await load()
  } finally {
    busy.value = undefined
  }
}
watch(
  () => props.taskId,
  () => {
    pkg.value = undefined
    load()
  },
  { immediate: true }
)
onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
defineExpose({ refresh: load })
</script>

<style scoped>
.sop-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.sop-files {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 12px 0;
}

.sop-step {
  margin-top: 16px;
}

.sop-instruction {
  line-height: 1.8;
  white-space: pre-wrap;
}

.sop-timing {
  color: var(--el-text-color-secondary);
}
</style>
