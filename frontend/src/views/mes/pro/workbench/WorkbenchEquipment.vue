<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div class="equipment-toolbar">
        <strong>工作站设备 · 点检与保养</strong>
        <el-button :loading="loading" @click="loadTasks">刷新设备任务</el-button>
      </div>
    </template>
    <el-empty v-if="!machines.length" description="当前工作站尚未关联设备" />
    <template v-else>
      <el-select
        v-model="machineId"
        size="large"
        placeholder="选择工作站设备"
        @change="loadTasks"
        class="machine-select"
      >
        <el-option
          v-for="machine in machines"
          :key="machine.id"
          :value="machine.id"
          :label="`${machine.name} · ${machine.code}`"
        />
      </el-select>
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
        class="mt-16px"
      />
      <el-empty v-if="!tasks.length && !loading" description="当前设备没有任务" />
      <div class="equipment-tasks">
        <article v-for="task in tasks" :key="task.id" class="equipment-task">
          <div>
            <h3>{{ task.planName }}</h3>
            <p>{{ task.machineryName }} · {{ formatDate(task.dueTime, 'YYYY-MM-DD HH:mm') }}</p>
            <el-tag :type="task.status === 2 ? 'success' : 'warning'">{{
              ['待领用', '执行中', '已完成'][task.status]
            }}</el-tag>
            <span v-if="task.repairId" class="ml-8px">异常已关联报修 #{{ task.repairId }}</span>
          </div>
          <el-button
            v-hasPermi="['mes:dv-check-record:update', 'mes:dv-mainten-record:update']"
            size="large"
            :type="task.status === 2 ? 'default' : 'primary'"
            @click="openTask(task)"
          >
            {{ task.status === 0 ? '领用并处理' : task.status === 1 ? '继续填写' : '查看结果' }}
          </el-button>
        </article>
      </div>
      <Pagination
        :total="total"
        v-model:page="page"
        v-model:limit="pageSize"
        @pagination="loadTasks"
      />
    </template>
  </el-card>

  <el-dialog v-model="visible" title="设备任务执行" width="min(900px, 96vw)" destroy-on-close>
    <div v-if="current" v-loading="saving">
      <h2>{{ current.planName }}</h2>
      <p>{{ current.machineryName }} · {{ formatDate(current.dueTime, 'YYYY-MM-DD HH:mm') }}</p>
      <article v-for="line in current.lines" :key="line.id" class="equipment-line">
        <h3>{{ line.subjectName }}</h3>
        <p>{{ line.content }}</p>
        <p class="standard">标准：{{ line.standard || '未设置' }}</p>
        <el-radio-group v-model="line.result" size="large" :disabled="current.status === 2">
          <el-radio-button :value="1">正常</el-radio-button>
          <el-radio-button :value="2">异常</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="line.description"
          type="textarea"
          :rows="2"
          :disabled="current.status === 2"
          :placeholder="line.result === 2 ? '请描述异常（必填）' : '填写执行说明（选填）'"
          class="mt-12px"
        />
      </article>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" />
    </div>
    <template #footer>
      <el-button size="large" @click="visible = false">关闭</el-button>
      <el-button
        v-if="current?.status !== 2"
        type="primary"
        size="large"
        :loading="saving"
        @click="complete"
        >提交执行结果</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { WorkbenchApi, type WorkstationOption } from '@/api/mes/pro/workbench'
import { DvTaskApi, type DvTask } from '@/api/mes/dv/task'
import { formatDate } from '@/utils/formatTime'

const props = defineProps<{ stationId?: number }>()
const message = useMessage()
const machines = ref<WorkstationOption[]>([])
const machineId = ref<number>()
const tasks = ref<DvTask[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const current = ref<DvTask>()
const error = ref('')
const formError = ref('')
let stationSequence = 0
let taskSequence = 0
const errorText = (value: unknown) =>
  typeof value === 'string' ? value : (value as Error)?.message || '操作失败，请刷新后重试'

const loadTasks = async () => {
  if (!machineId.value) return
  const sequence = ++taskSequence
  const requestedMachine = machineId.value
  loading.value = true
  error.value = ''
  try {
    const result = await DvTaskApi.page({
      pageNo: page.value,
      pageSize: pageSize.value,
      machineryId: requestedMachine
    })
    if (sequence !== taskSequence || requestedMachine !== machineId.value) return
    tasks.value = result.list
    total.value = result.total
  } catch (cause) {
    if (sequence === taskSequence) error.value = errorText(cause)
  } finally {
    if (sequence === taskSequence) loading.value = false
  }
}

const openTask = async (task: DvTask) => {
  error.value = ''
  try {
    if (task.status === 0) await DvTaskApi.claim(task.id)
    current.value = await DvTaskApi.get(task.id)
    formError.value = ''
    visible.value = true
    await loadTasks()
  } catch (cause) {
    error.value = errorText(cause)
  }
}

const complete = async () => {
  if (!current.value) return
  if (
    !current.value.lines?.length ||
    current.value.lines.some(
      (line) =>
        ![1, 2].includes(line.result || 0) || (line.result === 2 && !line.description?.trim())
    )
  ) {
    formError.value = '请完成全部项目；异常项目必须填写异常描述'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const recordId = await DvTaskApi.complete({
      id: current.value.id,
      lines: current.value.lines.map(({ id, result, description }) => ({ id, result, description }))
    })
    message.success(`执行结果已保存，记录 #${recordId}`)
    visible.value = false
    await loadTasks()
  } catch (cause) {
    formError.value = errorText(cause)
  } finally {
    saving.value = false
  }
}

watch(
  () => props.stationId,
  async (id) => {
    const sequence = ++stationSequence
    ++taskSequence
    machines.value = []
    tasks.value = []
    machineId.value = undefined
    page.value = 1
    if (!id) return
    loading.value = true
    error.value = ''
    try {
      const options = await WorkbenchApi.getMachines(id)
      if (sequence !== stationSequence) return
      machines.value = options
      machineId.value = machines.value[0]?.id
      await loadTasks()
    } catch (cause) {
      if (sequence === stationSequence) error.value = errorText(cause)
    } finally {
      if (sequence === stationSequence) loading.value = false
    }
  },
  { immediate: true }
)
</script>

<style scoped lang="scss">
.equipment-toolbar,
.equipment-task {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.machine-select {
  width: 100%;
  max-width: 480px;
}

.equipment-tasks {
  display: grid;
  gap: 16px;
  margin-top: 16px;
}

.equipment-task,
.equipment-line {
  padding: 20px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
}

.equipment-line {
  margin-bottom: 16px;
}

.equipment-task h3,
.equipment-line h3 {
  margin-top: 0;
}

.standard {
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
}

@media (width <= 600px) {
  .equipment-task {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
