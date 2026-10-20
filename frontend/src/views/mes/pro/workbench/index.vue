<template>
  <div class="workbench">
    <el-card shadow="never">
      <div class="station-toolbar">
        <div>
          <div class="brand">FazeMES · 工位工作台</div>
          <h1>生产任务与作业指导</h1>
        </div>
        <div class="station-controls">
          <el-select
            v-model="stationId"
            placeholder="选择工作站"
            size="large"
            filterable
            aria-label="选择工作站"
            @change="loadTasks"
          >
            <el-option
              v-for="station in stations"
              :key="station.id"
              :value="station.id"
              :label="`${station.name} · ${station.code}`"
            />
          </el-select>
          <el-button size="large" :loading="loading" @click="loadTasks">刷新任务</el-button>
        </div>
      </div>
    </el-card>

    <el-card v-if="stationId" shadow="never" class="mt-16px">
      <template #header>
        <div class="task-header">
          <strong>当前工位资源</strong>
          <el-tag :type="availability?.available ? 'success' : 'warning'">
            {{
              availabilityLoading ? '检查中' : availability?.available ? '可以作业' : '暂不可作业'
            }}
          </el-tag>
        </div>
      </template>
      <el-alert
        v-if="availabilityError"
        :title="availabilityError"
        type="warning"
        show-icon
        :closable="false"
      />
      <template v-else-if="availability">
        <el-alert
          v-if="!availability.available"
          title="工位资源暂不可用，请处理以下原因后刷新任务"
          type="warning"
          show-icon
          :closable="false"
        />
        <ul v-if="availability.reasons.length" class="resource-reasons">
          <li v-for="reason in availability.reasons" :key="reason">{{ reason }}</li>
        </ul>
        <ul v-if="availability.notes.length" class="resource-notes">
          <li v-for="note in availability.notes" :key="note">{{ note }}</li>
        </ul>
        <p class="form-hint"
          >最近检查 {{ time(availability.checkedAt) }} · 刷新任务可重新检查资源</p
        >
      </template>
    </el-card>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mt-16px"
    />

    <el-tabs v-model="activeTab" type="border-card" class="mt-16px">
      <el-tab-pane label="生产作业" name="production">
        <div class="workbench-grid" v-loading="loading">
          <el-card shadow="never" class="tasks-card">
            <template #header>待执行任务 · 按计划开始时间排序</template>
            <el-empty v-if="!tasks.length" description="当前工作站没有待执行任务" />
            <div v-else class="task-list">
              <button
                v-for="(task, index) in tasks"
                :key="task.id"
                class="task-button"
                :class="{ selected: selectedTaskId === task.id }"
                @click="selectTask(task.id)"
              >
                <span class="task-sequence">{{ index + 1 }}</span>
                <span class="task-summary">
                  <strong>{{ task.itemName }} · {{ task.processName }}</strong>
                  <span>{{ task.workOrderCode }}</span>
                  <span>{{ time(task.startTime) }} → {{ time(task.endTime) }}</span>
                  <span>已生产 {{ task.producedQuantity || 0 }} / 排产 {{ task.quantity }}</span>
                </span>
              </button>
            </div>
          </el-card>

          <div class="task-detail" v-loading="contextLoading">
            <template v-if="context">
              <el-card shadow="never">
                <template #header>
                  <div class="task-header">
                    <strong>{{ context.task.itemName }} · {{ context.task.processName }}</strong>
                    <el-tag>{{ context.task.checkFlag ? '需质量检验' : '自主报工' }}</el-tag>
                  </div>
                </template>
                <el-descriptions :column="1" border>
                  <el-descriptions-item label="工单"
                    >{{ context.task.workOrderCode }} ·
                    {{ context.task.workOrderName }}</el-descriptions-item
                  >
                  <el-descriptions-item label="产品"
                    >{{ context.task.itemCode }} ·
                    {{ context.task.itemSpecification || '—' }}</el-descriptions-item
                  >
                  <el-descriptions-item label="工作站">{{
                    context.task.workstationName
                  }}</el-descriptions-item>
                  <el-descriptions-item label="任务">{{ context.task.code }}</el-descriptions-item>
                  <el-descriptions-item label="进度"
                    >{{ context.task.producedQuantity || 0 }} /
                    {{ context.task.quantity }}</el-descriptions-item
                  >
                </el-descriptions>
                <el-button
                  v-hasPermi="['mes:qc-ipqc:query']"
                  size="large"
                  class="mt-16px"
                  @click="openQuality"
                  >进入当前任务质检</el-button
                >
                <el-button
                  v-hasPermi="['mes:sn-trace:query']"
                  size="large"
                  class="mt-16px"
                  @click="
                    router.push({
                      path: '/mes/sntrace',
                      query: { workOrderId: context.task.workOrderId }
                    })
                  "
                  >单件 SN 用料追溯</el-button
                >
                <el-button
                  v-hasPermi="['mes:kitting:query']"
                  size="large"
                  class="mt-16px"
                  @click="
                    router.push({
                      path: '/mes/kitting',
                      query: { workOrderId: context.task.workOrderId }
                    })
                  "
                  >工单齐套分析</el-button
                >
              </el-card>

              <el-card shadow="never">
                <template #header>受控 SOP · 任务执行记录</template>
                <TaskExecution :key="context.task.id" :task-id="context.task.id" />
              </el-card>
              <el-card shadow="never">
                <template #header>产品工序参考资料</template>
                <el-empty
                  v-if="!context.sops.length"
                  description="当前产品与工序尚未配置作业指导书"
                />
                <article v-for="sop in context.sops" :key="sop.id" class="sop">
                  <h2>{{ sop.title }}</h2>
                  <p v-if="sop.description">{{ sop.description }}</p>
                  <el-image
                    v-if="sop.url"
                    :src="sop.url"
                    :preview-src-list="sopImages"
                    fit="contain"
                    preview-teleported
                    class="sop-image"
                  >
                    <template #error
                      ><div class="sop-error">SOP 图片加载失败，请检查附件</div></template
                    >
                  </el-image>
                </article>
              </el-card>

              <el-card shadow="never">
                <template #header>提交生产报工</template>
                <p class="form-hint"
                  >报工人自动使用当前登录账号。提交后由指定审核人审批，审批成功后更新库存和生产进度。</p
                >
                <el-form
                  ref="formRef"
                  :model="form"
                  :rules="rules"
                  label-position="top"
                  size="large"
                >
                  <div class="quantity-grid">
                    <el-form-item
                      v-if="context.task.checkFlag"
                      label="本次报工数量"
                      prop="feedbackQuantity"
                    >
                      <el-input v-model="form.feedbackQuantity" inputmode="decimal" />
                    </el-form-item>
                    <template v-else>
                      <el-form-item label="合格品数量" prop="qualifiedQuantity">
                        <el-input v-model="form.qualifiedQuantity" inputmode="decimal" />
                      </el-form-item>
                      <el-form-item label="不良品数量" prop="unqualifiedQuantity">
                        <el-input v-model="form.unqualifiedQuantity" inputmode="decimal" />
                      </el-form-item>
                    </template>
                  </div>
                  <FeedbackSources
                    ref="sourcesRef"
                    v-model="form.sources"
                    :task-id="context.task.id"
                    :quantity="reportedQuantity"
                  />
                  <el-form-item label="指定审核人" prop="approveUserId">
                    <el-select v-model="form.approveUserId" placeholder="选择审核人" filterable>
                      <el-option
                        v-for="user in context.approvers"
                        :key="user.id"
                        :value="user.id"
                        :label="user.name"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="备注"
                    ><el-input v-model="form.remark" type="textarea" :rows="2"
                  /></el-form-item>
                  <el-alert
                    v-if="submitError"
                    :title="submitError"
                    type="error"
                    show-icon
                    :closable="false"
                    class="mb-16px"
                  />
                  <el-button
                    v-hasPermi="['mes:pro-feedback:create']"
                    type="primary"
                    size="large"
                    :loading="submitting"
                    :disabled="availabilityLoading || !availability?.available"
                    @click="submit"
                  >
                    提交给审核人
                  </el-button>
                </el-form>
              </el-card>
            </template>
            <el-card v-else-if="!contextLoading" shadow="never"
              ><el-empty description="选择一个任务开始作业"
            /></el-card>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane v-if="canQueryEquipment" label="设备点检 / 保养" name="equipment" lazy>
        <WorkbenchEquipment :station-id="stationId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import {
  WorkbenchApi,
  type WorkbenchContext,
  type WorkbenchTask,
  type WorkstationOption,
  type WorkstationAvailability
} from '@/api/mes/pro/workbench'
import { formatDate } from '@/utils/formatTime'
import { checkPermi } from '@/utils/permission'
import WorkbenchEquipment from './WorkbenchEquipment.vue'
import TaskExecution from '@/views/mes/pro/sop/TaskExecution.vue'
import type { FormInstance, FormRules } from 'element-plus'
import FeedbackSources from '@/views/mes/pro/feedback/FeedbackSources.vue'
import {
  sumQuantities,
  quantityRule,
  quantityUnits
} from '@/views/mes/pro/feedback/sourceQuantities'
import type { FeedbackSource } from '@/api/mes/pro/feedback'

defineOptions({ name: 'MesProWorkbench' })
const message = useMessage()
const router = useRouter()
const route = useRoute()
const activeTab = ref('production')
const canQueryEquipment = computed(() => checkPermi(['mes:dv-check-plan:query']))
const openQuality = () => {
  if (!context.value) return
  const task = context.value.task
  router.push({
    path: '/mes/quality-workbench',
    query: {
      stage: 'IPQC',
      workstationId: task.workstationId,
      workOrderId: task.workOrderId,
      taskId: task.id,
      itemId: task.itemId,
      routeId: task.routeId,
      processId: task.processId
    }
  })
}
const stations = ref<WorkstationOption[]>([])
const stationId = ref<number>()
const tasks = ref<WorkbenchTask[]>([])
const selectedTaskId = ref<number>()
const context = ref<WorkbenchContext>()
const loading = ref(false)
const availability = ref<WorkstationAvailability>()
const availabilityLoading = ref(false)
const availabilityError = ref('')
const contextLoading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const submitError = ref('')
const formRef = ref<FormInstance>()
const sourcesRef = ref<InstanceType<typeof FeedbackSources>>()
const form = reactive({
  approveUserId: undefined as number | undefined,
  feedbackQuantity: '0',
  qualifiedQuantity: '0',
  unqualifiedQuantity: '0',
  remark: '',
  sources: [] as FeedbackSource[]
})
const reportedQuantity = computed(() =>
  context.value?.task.checkFlag
    ? String(form.feedbackQuantity)
    : (sumQuantities(form.qualifiedQuantity, form.unqualifiedQuantity) ?? '')
)
const rules: FormRules = {
  feedbackQuantity: [quantityRule()],
  qualifiedQuantity: [quantityRule()],
  unqualifiedQuantity: [quantityRule()],
  approveUserId: [{ required: true, message: '请选择审核人', trigger: 'change' }]
}
const sopImages = computed(
  () => context.value?.sops.map((sop) => sop.url).filter((url): url is string => !!url) || []
)
const time = (value?: string | number) => (value ? formatDate(value, 'MM-DD HH:mm') : '未设置')
const errorText = (error: unknown, fallback: string) =>
  typeof error === 'string' ? error : (error as Error)?.message || fallback
let selectionSequence = 0
let taskLoadSequence = 0
let availabilitySequence = 0

const refreshAvailability = async () => {
  const requestedStation = stationId.value
  const sequence = ++availabilitySequence
  availability.value = undefined
  availabilityError.value = ''
  if (!requestedStation) {
    availabilityLoading.value = false
    return
  }
  availabilityLoading.value = true
  try {
    const result = await WorkbenchApi.getAvailability(requestedStation)
    if (requestedStation === stationId.value && sequence === availabilitySequence)
      availability.value = result
  } catch (error) {
    if (requestedStation === stationId.value && sequence === availabilitySequence)
      availabilityError.value = errorText(error, '工位资源检查失败，请刷新后再报工')
  } finally {
    if (sequence === availabilitySequence) availabilityLoading.value = false
  }
}

const selectTask = async (id: number) => {
  const sequence = ++selectionSequence
  selectedTaskId.value = id
  context.value = undefined
  contextLoading.value = true
  submitError.value = ''
  try {
    const data = await WorkbenchApi.getContext(id)
    if (sequence !== selectionSequence) return
    context.value = data
    form.feedbackQuantity = '0'
    form.qualifiedQuantity = '0'
    form.unqualifiedQuantity = '0'
    form.remark = ''
    form.sources = []
    formRef.value?.clearValidate()
  } catch (error) {
    if (sequence === selectionSequence)
      loadError.value = errorText(error, '任务详情加载失败，请刷新重试')
  } finally {
    if (sequence === selectionSequence) contextLoading.value = false
  }
}

const loadTasks = async () => {
  if (!stationId.value) return
  const loadSequence = ++taskLoadSequence
  ++selectionSequence
  context.value = undefined
  contextLoading.value = false
  loadError.value = ''
  loading.value = true
  const requestedStation = stationId.value
  try {
    const [taskResult] = await Promise.allSettled([
      WorkbenchApi.getTasks(requestedStation),
      refreshAvailability()
    ])
    if (requestedStation !== stationId.value || loadSequence !== taskLoadSequence) return
    if (taskResult.status === 'rejected') throw taskResult.reason
    const list = taskResult.value
    tasks.value = list
    const selected = list.find((task) => task.id === selectedTaskId.value) || list[0]
    if (selected) await selectTask(selected.id)
    else selectedTaskId.value = undefined
  } catch (error) {
    if (loadSequence === taskLoadSequence) {
      tasks.value = []
      loadError.value = errorText(error, '工作站任务加载失败，请重试')
    }
  } finally {
    if (loadSequence === taskLoadSequence) loading.value = false
  }
}

const submit = async () => {
  if (submitting.value) return
  await formRef.value?.validate()
  if (!context.value || !form.approveUserId) return
  const task = context.value.task
  const quantity = quantityUnits(reportedQuantity.value)
  if (quantity === undefined || quantity <= 0n) {
    submitError.value = '本次报工数量须大于0，最多 14 位整数和 6 位小数'
    return
  }
  if (!sourcesRef.value?.validate()) return
  submitting.value = true
  submitError.value = ''
  try {
    await refreshAvailability()
    if (context.value?.task.id !== task.id || stationId.value !== task.workstationId) {
      submitError.value = '工作站或任务已变化，请确认后重新提交'
      return
    }
    if (!availability.value?.available) {
      submitError.value = availabilityError.value || '当前工位资源不可用，请处理原因后重新提交'
      return
    }
    const id = await WorkbenchApi.submitFeedback({
      taskId: task.id,
      approveUserId: form.approveUserId,
      feedbackQuantity: reportedQuantity.value,
      qualifiedQuantity: form.qualifiedQuantity,
      unqualifiedQuantity: form.unqualifiedQuantity,
      remark: form.remark,
      sources: form.sources
    })
    message.success(`报工 #${id} 已提交，等待指定审核人审批`)
    await loadTasks()
  } catch (error) {
    submitError.value = errorText(error, '报工提交失败，请检查任务状态和填写内容')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    stations.value = await WorkbenchApi.getWorkstations()
    const requestedStation = Number(route.query.workstationId)
    stationId.value =
      stations.value.find((station) => station.id === requestedStation)?.id || stations.value[0]?.id
    const requestedTask = Number(route.query.taskId)
    if (Number.isSafeInteger(requestedTask) && requestedTask > 0)
      selectedTaskId.value = requestedTask
    await loadTasks()
  } catch (error) {
    loadError.value = errorText(error, '工作站列表加载失败')
  }
})
</script>

<style scoped lang="scss">
.workbench {
  max-width: 1600px;
  margin: 0 auto;
}

.station-toolbar,
.station-controls,
.task-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.station-controls {
  flex-wrap: wrap;
}

.station-controls .el-select {
  width: 300px;
}

.brand {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

h1 {
  margin: 8px 0 0;
  font-size: 24px;
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 16px;
  margin-top: 16px;
  align-items: start;
}

.task-detail {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.task-list {
  display: grid;
  gap: 12px;
}

.task-button {
  display: flex;
  width: 100%;
  padding: 16px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  gap: 12px;
}

.task-button.selected {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary);
}

.task-sequence {
  font-size: 20px;
  color: var(--el-color-primary);
}

.task-summary {
  display: grid;
  gap: 8px;
  line-height: 1.4;
}

.task-summary span {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.sop + .sop {
  margin-top: 24px;
  border-top: 1px solid var(--el-border-color);
}

.sop h2 {
  font-size: 18px;
}

.sop p {
  line-height: 1.7;
  white-space: pre-wrap;
}

.sop-image {
  display: block;
  width: 100%;
  max-height: 800px;
}

.sop-error {
  padding: 24px;
  text-align: center;
}

.form-hint {
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.resource-reasons,
.resource-notes {
  padding-left: 24px;
  line-height: 1.8;
}

.resource-reasons {
  color: var(--el-color-warning-dark-2);
}

.resource-notes {
  color: var(--el-text-color-secondary);
}

.quantity-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.el-input {
  width: 100%;
}

@media (width <= 900px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .station-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .station-controls .el-select {
    width: 100%;
    flex: 1;
    min-width: 180px;
  }

  .task-list {
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  }

  h1 {
    font-size: 22px;
  }
}

@media (width <= 480px) {
  .quantity-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
