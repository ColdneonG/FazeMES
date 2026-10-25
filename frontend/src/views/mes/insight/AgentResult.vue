<template>
  <section class="agent-result">
    <template v-if="table">
      <div class="agent-heading">
        <strong>{{ table.title }} · {{ table.total }} 条</strong>
        <el-button
          v-if="table.module === 'WORKORDER' && canExport"
          type="primary"
          plain
          :loading="downloading"
          @click="downloadReport"
          >下载工单 Excel 报表</el-button
        >
      </div>
      <p class="agent-note">{{ table.basis }} 快照时间：{{ table.asOf }}</p>
      <el-table :data="table.list" row-key="id" max-height="420" empty-text="当前查询范围没有记录">
        <el-table-column prop="id" label="编号" width="95" />
        <el-table-column prop="code" label="编码" min-width="160" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column
          v-if="table.module === 'WORKORDER'"
          prop="productName"
          label="产品"
          min-width="160"
        />
        <el-table-column
          v-if="table.module === 'WORKORDER'"
          prop="quantity"
          label="计划数量"
          width="110"
        />
        <el-table-column
          v-if="table.module === 'WORKORDER'"
          prop="producedQuantity"
          label="完成数量"
          width="110"
        />
        <el-table-column prop="statusName" label="状态" width="100" />
        <el-table-column
          v-if="table.module === 'WORKORDER'"
          prop="dueAt"
          label="需求日期"
          min-width="180"
        />
      </el-table>
      <el-pagination
        v-if="table.total > 20"
        v-model:current-page="page"
        :page-size="20"
        :total="table.total"
        layout="total, prev, pager, next"
        @current-change="loadPage"
      />
    </template>
    <template v-if="answer.proposal">
      <el-alert
        :title="
          receipt
            ? '本次业务操作已执行，执行回执已保存。'
            : '以下为待确认的业务操作，尚未写入。请核对标题、关联记录、负责人和状态。'
        "
        :type="receipt ? 'success' : 'info'"
        :closable="false"
        show-icon
      />
      <el-button
        v-if="canManage && !receipt"
        type="primary"
        class="agent-review"
        @click="openReview"
      >
        核对并执行业务操作
      </el-button>
    </template>
    <el-alert
      v-if="receipt"
      :title="`${receipt.message}，异常编号 #${receipt.businessId}`"
      type="success"
      :closable="false"
    />
    <el-button v-if="receipt" link type="primary" @click="router.push('/mes/insight?tab=incidents')"
      >查看异常闭环记录</el-button
    >
    <el-dialog
      v-model="visible"
      title="Agent 业务执行确认"
      width="min(720px,95vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!executing"
      :show-close="!executing"
    >
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form v-if="!preview" label-position="top" @submit.prevent>
        <el-form-item label="操作类型"
          ><el-tag>{{
            form.kind === 'CREATE_INCIDENT' ? '创建真实异常记录' : '推进异常处理状态'
          }}</el-tag></el-form-item
        >
        <template v-if="form.kind === 'CREATE_INCIDENT'">
          <el-form-item label="异常标题" required
            ><el-input v-model="form.title" maxlength="160"
          /></el-form-item>
          <el-form-item label="详细描述"
            ><el-input v-model="form.description" type="textarea" maxlength="2000"
          /></el-form-item>
          <el-form-item label="关联工单编码（可选）"
            ><el-input v-model="form.orderCode" maxlength="100"
          /></el-form-item>
          <el-form-item label="分类" required>
            <el-select v-model="form.category"
              ><el-option v-for="(label, key) in categories" :key="key" :value="key" :label="label"
            /></el-select>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="异常编号" required
            ><el-input-number v-model="form.incidentId" :min="1" :precision="0"
          /></el-form-item>
          <el-form-item label="目标状态" required
            ><el-select v-model="form.status"
              ><el-option
                v-for="(label, key) in states"
                :key="key"
                :label="label"
                :value="key" /></el-select
          ></el-form-item>
          <el-form-item label="已确认的原因"
            ><el-input v-model="form.rootCause" type="textarea" maxlength="2000"
          /></el-form-item>
          <el-form-item label="已实施的措施"
            ><el-input v-model="form.correctiveAction" type="textarea" maxlength="2000"
          /></el-form-item>
          <el-form-item label="验证通过的真实证据"
            ><el-input v-model="form.evidence" type="textarea" maxlength="2000"
          /></el-form-item>
          <el-form-item label="操作说明" required
            ><el-input v-model="form.note" maxlength="2000"
          /></el-form-item>
        </template>
        <el-form-item label="负责人（本租户已启用账号）" required
          ><el-select v-model="form.ownerId" filterable
            ><el-option
              v-for="owner in owners"
              :key="owner.id"
              :value="owner.id"
              :label="owner.name" /></el-select
        ></el-form-item>
      </el-form>
      <template v-else>
        <p>请核对以下最终执行内容，有效期至 {{ preview.expiresAt }}。确认后会真实写入项目业务。</p>
        <el-descriptions :column="1" border>
          <el-descriptions-item
            v-for="field in reviewFields"
            :key="field.key"
            :label="field.label"
            >{{ field.value }}</el-descriptions-item
          >
        </el-descriptions>
      </template>
      <template #footer>
        <el-button :disabled="executing" @click="visible = false">暂不执行</el-button>
        <el-button v-if="preview" :disabled="executing" @click="resetPreview"
          >重新核对内容</el-button
        >
        <el-button v-if="!preview" type="primary" :loading="preparing" @click="prepare"
          >生成执行预览</el-button
        >
        <el-button v-else type="danger" :loading="executing" @click="execute">{{
          uncertain ? '重试当前确认并查询结果' : '确认执行上述业务操作'
        }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { BusinessAnswer } from '@/api/mes/insight/business'
import {
  AgentApi,
  type AgentOperation,
  type AgentPreview,
  type AgentReceipt,
  type AgentTable
} from '@/api/mes/insight/agent'
import { InsightApi } from '@/api/mes/insight'
import { checkPermi } from '@/utils/permission'
import download from '@/utils/download'
const props = defineProps<{ answer: BusinessAnswer }>()
const emit = defineEmits<{ failure: [error: unknown]; executed: [receipt: AgentReceipt] }>()
const router = useRouter()
const table = ref<AgentTable>()
const page = ref(1)
const downloading = ref(false)
const visible = ref(false)
const preview = ref<AgentPreview>()
const receipt = ref<AgentReceipt>()
const owners = ref<{ id: number; name: string }[]>([])
const form = reactive<AgentOperation>({ kind: 'CREATE_INCIDENT', category: 'OTHER' })
const preparing = ref(false)
const executing = ref(false)
const uncertain = ref(false)
const error = ref('')
const canManage = computed(() => checkPermi(['mes:insight:manage']))
const canExport = computed(() => checkPermi(['mes:pro-work-order:export']))
const categories: Record<string, string> = {
  DELIVERY: '交期',
  MATERIAL: '物料',
  QUALITY: '质量',
  DEVICE: '设备',
  PROCESS: '工艺',
  OTHER: '其他'
}
const states: Record<string, string> = {
  IN_PROGRESS: '处理中',
  VERIFYING: '待验证',
  CLOSED: '已关闭'
}
const labels: Record<string, string> = {
  kind: '操作类型',
  title: '异常标题',
  description: '描述',
  category: '分类',
  orderCode: '关联工单',
  incidentId: '异常编号',
  ownerId: '负责人',
  fromStatus: '原状态',
  status: '目标状态',
  rootCause: '确认原因',
  correctiveAction: '处理措施',
  evidence: '验证证据',
  note: '操作说明',
  dueAt: '保留的截止日期'
}
const reviewFields = computed(() =>
  Object.entries(preview.value?.payload || {})
    .filter(([key, value]) => labels[key] && value != null && value !== '')
    .map(([key, value]) => ({
      key,
      label: labels[key],
      value:
        key === 'kind'
          ? value === 'CREATE_INCIDENT'
            ? '创建异常记录'
            : '推进异常状态'
          : key === 'ownerId'
            ? owners.value.find((item) => item.id === value)?.name || value
            : key === 'category'
              ? categories[String(value)] || String(value)
              : value === 'OPEN'
                ? '待处理'
                : states[String(value)] || String(value)
    }))
)
watch(
  () => props.answer,
  (answer) => {
    receipt.value = answer.executionReceipt
    table.value =
      answer.evidence.kind === 'AGENT_TABLE'
        ? (answer.evidence as unknown as AgentTable)
        : undefined
  },
  { immediate: true }
)
async function loadPage() {
  try {
    table.value = await AgentApi.evidence(props.answer.turnId, page.value)
  } catch (cause) {
    table.value = undefined
    emit('failure', cause)
  }
}
async function downloadReport() {
  downloading.value = true
  try {
    download.excel(
      await AgentApi.report(props.answer.turnId),
      `生产工单报表-${props.answer.turnId}.xlsx`
    )
  } catch (cause) {
    emit('failure', cause)
  } finally {
    downloading.value = false
  }
}
async function openReview() {
  visible.value = true
  if (preview.value) return
  error.value = ''
  Object.assign(form, props.answer.proposal)
  try {
    owners.value = (await InsightApi.incidents()).owners
  } catch {
    error.value = '负责人列表读取失败，请检查权限并重新打开。'
  }
}
async function prepare() {
  if (preparing.value) return
  preparing.value = true
  error.value = ''
  try {
    preview.value = await AgentApi.prepare({ ...form }, props.answer.turnId)
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : '执行预览生成失败，请补齐字段并检查权限。'
  } finally {
    preparing.value = false
  }
}
function resetPreview() {
  preview.value = undefined
  uncertain.value = false
}
async function execute() {
  if (!preview.value || executing.value) return
  executing.value = true
  error.value = ''
  try {
    receipt.value = await AgentApi.execute({ id: preview.value.id, token: preview.value.token })
    emit('executed', receipt.value)
    uncertain.value = false
    visible.value = false
  } catch (cause) {
    uncertain.value = true
    error.value =
      (cause instanceof Error ? cause.message : '执行结果尚未确认。') +
      ' 可重试当前确认查询同一次结果；如需重新核对，系统也会先检查本轮是否已执行。'
  } finally {
    executing.value = false
  }
}
</script>
<style scoped>
.agent-result {
  margin: 16px 0;
}

.agent-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.agent-note {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.agent-review {
  margin-top: 12px;
}
</style>
