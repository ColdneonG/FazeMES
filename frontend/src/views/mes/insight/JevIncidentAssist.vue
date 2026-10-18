<template>
  <section class="jev-assist">
    <div class="jev-heading"
      ><strong>Jev 异常分类与部门建议</strong
      ><el-tag :type="status?.configured ? 'success' : 'info'">{{
        status?.configured ? '已配置' : '未启用'
      }}</el-tag></div
    >
    <p>将本次异常标题、描述及候选部门名称提交至TypeSafe。建议经你确认后随异常保存。</p>
    <el-button :loading="busy" :disabled="!status?.configured || !title.trim()" @click="analyze"
      >分析分类与建议部门</el-button
    >
    <p v-if="status && !status.configured"
      >管理员可通过异常列表上方的“Jev分流配置”填写独立密钥。当前可以直接手动建立异常。</p
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="suggestion" class="jev-result">
      <p
        >分类建议：<strong>{{ categoryNames[suggestion.category] || '无法判断' }}</strong> ·
        分布置信度 {{ percent(suggestion.categoryConfidence) }}</p
      >
      <p
        >处理部门：<strong>{{ suggestion.departmentName }}</strong> · 分布置信度
        {{ percent(suggestion.departmentConfidence) }}</p
      >
      <p>{{ suggestion.message }}</p>
      <p>实际模型：{{ suggestion.model }} · 建议 #{{ suggestion.id }}</p>
      <el-button
        type="primary"
        plain
        :disabled="suggestion.category === 'UNKNOWN' && !suggestion.departmentId"
        @click="emit('apply', suggestion)"
        >将可用建议填入表单</el-button
      >
      <el-button @click="discard">清除建议，手动填写</el-button>
    </div>
    <p v-if="status"
      >我的试点记录：生成 {{ status.stats.generated }} 次，人工确认保存
      {{ status.stats.reviewed }} 次，分类一致 {{ status.stats.categoryAgreed }} 次、部门一致
      {{ status.stats.departmentAgreed }} 次。一致次数不代表模型准确率。</p
    >
  </section>
</template>
<script setup lang="ts">
import { InsightApi, type JevStatus, type JevSuggestion } from '@/api/mes/insight'
const props = defineProps<{ title: string; description?: string }>()
const emit = defineEmits<{
  evaluated: [id: number | undefined]
  apply: [suggestion: JevSuggestion]
}>()
const status = ref<JevStatus>(),
  suggestion = ref<JevSuggestion>(),
  busy = ref(false),
  error = ref('')
const categoryNames: Record<string, string> = {
  DELIVERY: '交期',
  MATERIAL: '物料',
  QUALITY: '质量',
  DEVICE: '设备',
  PROCESS: '工艺流程',
  OTHER: '其他',
  UNKNOWN: '无法判断，请人工选择'
}
const percent = (n: number) => `${Math.round(n * 100)}%`
let generation = 0
function discard() {
  generation++
  suggestion.value = undefined
  emit('evaluated', undefined)
}
watch(
  () => [props.title, props.description],
  () => {
    discard()
    error.value = ''
  }
)
onMounted(async () => {
  try {
    status.value = await InsightApi.jevStatus()
  } catch {
    error.value = '暂时无法获取Jev状态，可继续手动建立异常。'
  }
})
onBeforeUnmount(() => {
  generation++
})
async function analyze() {
  const ticket = ++generation
  suggestion.value = undefined
  emit('evaluated', undefined)
  error.value = ''
  busy.value = true
  try {
    const result = await InsightApi.suggestIncident({
      title: props.title,
      description: props.description
    })
    if (ticket !== generation) return
    suggestion.value = result
    emit('evaluated', result.id)
    if (status.value) status.value.stats.generated++
  } catch (e) {
    if (ticket === generation)
      error.value =
        e instanceof Error
          ? e.message
          : typeof e === 'string'
            ? e
            : 'Jev分析失败，可继续手动建立异常。'
  } finally {
    busy.value = false
  }
}
</script>
<style scoped>
.jev-assist {
  padding: 16px;
  margin: 12px 0;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
}

.jev-heading {
  display: flex;
  gap: 12px;
  align-items: center;
}

.jev-assist p {
  margin: 10px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.jev-result {
  margin-top: 12px;
}
</style>
