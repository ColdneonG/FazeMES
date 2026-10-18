<template>
  <ContentWrap>
    <div class="sop-toolbar">
      <div><h2>受控SOP</h2><p>按产品与工序管理版本，发布后为生产任务冻结作业内容。</p></div>
      <el-input
        v-model="keyword"
        placeholder="搜索文档名称"
        clearable
        class="!w-220px"
        @keyup.enter="loadDocuments"
      />
      <el-button @click="loadDocuments">查询</el-button>
      <el-button v-hasPermi="['mes:sop:create']" type="primary" @click="documentDialog = true"
        >新建文档</el-button
      >
    </div>
    <el-alert
      title="提交后内容永久冻结；驳回或变更时请复制为新版本。停用会同时阻断已绑定该版本任务的执行与报工。"
      type="info"
      :closable="false"
    />
    <div class="sop-layout">
      <div class="sop-documents">
        <el-empty v-if="!documents.length" description="暂无受控文档" />
        <button
          v-for="doc in documents"
          :key="doc.id"
          type="button"
          class="sop-document"
          :class="{ active: selected?.id === doc.id }"
          @click="selectDocument(doc)"
        >
          <strong>{{ doc.name }}</strong
          ><span>{{ doc.code }}</span
          ><small>{{ itemName(doc.itemId) }} / {{ processName(doc.processId) }}</small>
        </button>
      </div>
      <div v-loading="loading" class="sop-versions">
        <template v-if="selected">
          <div class="sop-toolbar"
            ><h3>{{ selected.name }} · 版本</h3
            ><el-button v-hasPermi="['mes:sop:update']" type="primary" @click="editVersion()"
              >新建版本</el-button
            ></div
          >
          <el-table :data="versions" border>
            <el-table-column prop="versionNo" label="版本号" width="110" />
            <el-table-column prop="title" label="标题" min-width="170" />
            <el-table-column label="状态" width="110"
              ><template #default="{ row }"
                ><el-tag
                  :type="
                    row.status === 'PUBLISHED'
                      ? 'success'
                      : row.status === 'DISABLED'
                        ? 'danger'
                        : 'info'
                  "
                  >{{ statusLabel(row.status) }}</el-tag
                ></template
              ></el-table-column
            >
            <el-table-column label="步骤" width="70"
              ><template #default="{ row }">{{ row.steps.length }}</template></el-table-column
            >
            <el-table-column label="操作" min-width="350"
              ><template #default="{ row }">
                <el-button link type="primary" @click="preview = row">查看</el-button>
                <el-button
                  v-if="row.status === 'DRAFT'"
                  v-hasPermi="['mes:sop:update']"
                  link
                  type="primary"
                  @click="editVersion(row)"
                  >编辑</el-button
                >
                <el-button
                  v-hasPermi="['mes:sop:update']"
                  link
                  type="primary"
                  @click="editVersion(row, true)"
                  >复制为新版本</el-button
                >
                <el-button
                  v-if="row.status === 'DRAFT'"
                  v-hasPermi="['mes:sop:submit']"
                  link
                  type="warning"
                  @click="transition(row, 'submit')"
                  >提交</el-button
                >
                <template v-if="row.status === 'SUBMITTED'"
                  ><el-button
                    v-hasPermi="['mes:sop:approve']"
                    link
                    type="success"
                    @click="transition(row, 'approve')"
                    >审批通过</el-button
                  ><el-button
                    v-hasPermi="['mes:sop:approve']"
                    link
                    type="danger"
                    @click="transition(row, 'reject')"
                    >驳回</el-button
                  ></template
                >
                <el-button
                  v-if="row.status === 'APPROVED'"
                  v-hasPermi="['mes:sop:publish']"
                  link
                  type="success"
                  @click="transition(row, 'publish')"
                  >发布</el-button
                >
                <el-button
                  v-if="['PUBLISHED', 'SUPERSEDED'].includes(row.status)"
                  v-hasPermi="['mes:sop:disable']"
                  link
                  type="danger"
                  @click="transition(row, 'disable')"
                  >停用</el-button
                >
              </template></el-table-column
            >
          </el-table>
        </template>
        <el-empty v-else description="请选择左侧文档" />
      </div>
    </div>
  </ContentWrap>

  <el-dialog v-model="documentDialog" title="新建受控文档" width="600px">
    <el-form label-width="90px">
      <el-form-item label="文档编码" required
        ><el-input v-model="documentForm.code" maxlength="64"
      /></el-form-item>
      <el-form-item label="文档名称" required
        ><el-input v-model="documentForm.name" maxlength="200"
      /></el-form-item>
      <el-form-item label="产品" required
        ><el-select
          v-model="documentForm.itemId"
          filterable
          remote
          :remote-method="searchItems"
          placeholder="输入产品名称搜索"
          class="w-full"
          ><el-option
            v-for="item in items"
            :key="item.id"
            :value="item.id"
            :label="`${item.code} · ${item.name}`" /></el-select
      ></el-form-item>
      <el-form-item label="工序" required
        ><el-select v-model="documentForm.processId" filterable class="w-full"
          ><el-option
            v-for="process in processes"
            :key="process.id"
            :value="process.id"
            :label="`${process.code} · ${process.name}`" /></el-select
      ></el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="documentDialog = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="createDocument"
        >创建</el-button
      ></template
    >
  </el-dialog>

  <el-dialog
    v-model="versionDialog"
    :title="form.id ? '编辑草稿版本' : '新建草稿版本'"
    width="min(1100px, 95vw)"
    top="4vh"
  >
    <el-form label-width="90px">
      <div class="sop-toolbar"
        ><el-form-item label="版本号" required
          ><el-input v-model="form.versionNo" maxlength="64" /></el-form-item
        ><el-form-item label="标题" required class="flex-1"
          ><el-input v-model="form.title" maxlength="200" /></el-form-item
      ></div>
      <el-form-item label="变更说明"
        ><el-input v-model="form.changeNote" type="textarea"
      /></el-form-item>
      <el-form-item label="作业资料"
        ><UploadFile v-model="form.attachments" :limit="20"
      /></el-form-item>
      <el-form-item label="GLB模型"
        ><UploadFile v-model="form.modelUrl" :limit="1" :file-type="['glb']" :file-size="50"
      /></el-form-item>
    </el-form>
    <div class="sop-toolbar"
      ><h3>作业步骤（按当前顺序执行）</h3><el-button @click="addStep">添加步骤</el-button></div
    >
    <el-card
      v-for="(step, index) in form.steps"
      :key="step.id || index"
      class="sop-editor-step"
      shadow="never"
    >
      <template #header
        ><div class="sop-toolbar"
          ><strong>步骤 {{ index + 1 }}</strong
          ><el-button :disabled="index === 0" @click="moveStep(index, -1)">上移</el-button
          ><el-button :disabled="index === form.steps.length - 1" @click="moveStep(index, 1)"
            >下移</el-button
          ><el-button type="danger" plain @click="form.steps.splice(index, 1)">删除</el-button></div
        ></template
      >
      <el-form label-width="100px">
        <el-form-item label="标题" required
          ><el-input v-model="step.title" maxlength="200"
        /></el-form-item>
        <el-form-item label="操作说明" required
          ><el-input v-model="step.instruction" type="textarea" :rows="3"
        /></el-form-item>
        <el-form-item label="执行要求"
          ><el-checkbox v-model="step.required">必做</el-checkbox
          ><el-checkbox v-model="step.photoRequired">必须上传照片</el-checkbox
          ><span class="ml-16px mr-8px">最短查看秒数</span
          ><el-input-number v-model="step.minViewSeconds" :min="0" :max="86400"
        /></el-form-item>
        <el-form-item label="步骤资料"
          ><UploadFile v-model="step.attachments" :limit="10"
        /></el-form-item>
      </el-form>
      <el-table :data="step.parameters" border>
        <el-table-column label="参数标识" min-width="110"
          ><template #default="{ row }"
            ><el-input v-model="row.key" placeholder="如 torque" /></template
        ></el-table-column>
        <el-table-column label="参数名称" min-width="110"
          ><template #default="{ row }"><el-input v-model="row.label" /></template
        ></el-table-column>
        <el-table-column label="单位" width="90"
          ><template #default="{ row }"><el-input v-model="row.unit" /></template
        ></el-table-column>
        <el-table-column label="下限" width="150"
          ><template #default="{ row }"
            ><el-input-number
              v-model="row.min"
              controls-position="right"
              class="!w-full" /></template
        ></el-table-column>
        <el-table-column label="上限" width="150"
          ><template #default="{ row }"
            ><el-input-number
              v-model="row.max"
              controls-position="right"
              class="!w-full" /></template
        ></el-table-column>
        <el-table-column label="必填" width="60"
          ><template #default="{ row }"><el-checkbox v-model="row.required" /></template
        ></el-table-column>
        <el-table-column label="操作" width="70"
          ><template #default="{ $index }"
            ><el-button link type="danger" @click="step.parameters.splice($index, 1)"
              >删除</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <el-button
        class="mt-8px"
        @click="step.parameters.push({ key: '', label: '', required: true })"
        >添加数值参数</el-button
      >
    </el-card>
    <template #footer
      ><el-button @click="versionDialog = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveVersion"
        >保存草稿</el-button
      ></template
    >
  </el-dialog>

  <el-dialog
    :model-value="!!preview"
    title="版本详情（只读）"
    width="min(900px, 95vw)"
    @close="preview = undefined"
  >
    <template v-if="preview">
      <el-descriptions :column="2" border
        ><el-descriptions-item label="版本">{{ preview.versionNo }}</el-descriptions-item
        ><el-descriptions-item label="状态">{{ statusLabel(preview.status) }}</el-descriptions-item
        ><el-descriptions-item label="变更说明" :span="2">{{
          preview.changeNote
        }}</el-descriptions-item
        ><el-descriptions-item label="提交人">{{ preview.submittedBy }}</el-descriptions-item
        ><el-descriptions-item label="审批人">{{ preview.reviewedBy }}</el-descriptions-item
        ><el-descriptions-item label="审批意见">{{ preview.reviewComment }}</el-descriptions-item
        ><el-descriptions-item label="发布人">{{ preview.publishedBy }}</el-descriptions-item
        ><el-descriptions-item label="停用原因" :span="2">{{
          preview.disableReason
        }}</el-descriptions-item></el-descriptions
      >
      <div class="sop-toolbar mt-16px"
        ><el-link
          v-for="(url, index) in preview.attachments"
          :key="url"
          :href="url"
          target="_blank"
          rel="noopener noreferrer"
          type="primary"
          >资料 {{ index + 1 }}</el-link
        ><el-link
          v-if="preview.modelUrl"
          :href="preview.modelUrl"
          target="_blank"
          rel="noopener noreferrer"
          type="primary"
          >下载GLB模型</el-link
        ></div
      >
      <el-card
        v-for="(step, index) in preview.steps"
        :key="step.id"
        class="sop-editor-step"
        shadow="never"
        ><h4>{{ index + 1 }}. {{ step.title }} · {{ step.required ? '必做' : '选做' }}</h4
        ><p class="sop-instruction">{{ step.instruction }}</p
        ><p
          >最短查看 {{ step.minViewSeconds }} 秒 ·
          {{ step.photoRequired ? '照片必填' : '照片选填' }}</p
        ><p v-for="param in step.parameters" :key="param.key"
          >{{ param.label }} ({{ param.unit || '无单位' }})：{{ param.min ?? '不限' }} ～
          {{ param.max ?? '不限' }} · {{ param.required ? '必填' : '选填' }}</p
        ><el-link
          v-for="(url, fileIndex) in step.attachments"
          :key="url"
          :href="url"
          target="_blank"
          rel="noopener noreferrer"
          class="mr-12px"
          type="primary"
          >步骤资料 {{ fileIndex + 1 }}</el-link
        ></el-card
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { SopApi, type SopDocument, type SopVersion } from '@/api/mes/pro/sop'
import { MdItemApi } from '@/api/mes/md/item'
import { ProProcessApi } from '@/api/mes/pro/process'
import { UploadFile } from '@/components/UploadFile'
import { ElMessageBox } from 'element-plus'

defineOptions({ name: 'MesControlledSop' })
const message = useMessage()
const keyword = ref('')
const documents = ref<SopDocument[]>([])
const selected = ref<SopDocument>()
const versions = ref<SopVersion[]>([])
const loading = ref(false)
const saving = ref(false)
const items = ref<any[]>([])
const processes = ref<any[]>([])
const documentDialog = ref(false)
const versionDialog = ref(false)
const preview = ref<SopVersion>()
const documentForm = reactive({
  code: '',
  name: '',
  itemId: undefined as number | undefined,
  processId: undefined as number | undefined
})
const emptyVersion = (): SopVersion => ({
  documentId: selected.value?.id || 0,
  versionNo: '',
  title: selected.value?.name || '',
  changeNote: '',
  modelUrl: '',
  attachments: [],
  steps: []
})
const form = ref<SopVersion>(emptyVersion())
const statusLabel = (value?: string) =>
  ({
    DRAFT: '草稿',
    SUBMITTED: '待审批',
    APPROVED: '审批通过',
    REJECTED: '已驳回',
    PUBLISHED: '已发布',
    SUPERSEDED: '已替代',
    DISABLED: '已停用'
  })[value || ''] || value
const itemName = (id: number) => items.value.find((row) => row.id === id)?.name || `产品 #${id}`
const processName = (id: number) =>
  processes.value.find((row) => row.id === id)?.name || `工序 #${id}`
const searchItems = async (name?: string) => {
  const page = await MdItemApi.getItemPage({
    pageNo: 1,
    pageSize: 100,
    name,
    itemOrProduct: 'PRODUCT'
  })
  items.value = [...new Map([...items.value, ...page.list].map((item) => [item.id, item])).values()]
}
const loadDocuments = async () => {
  documents.value = await SopApi.documents(keyword.value)
  if (!selected.value && documents.value.length) await selectDocument(documents.value[0])
}
const selectDocument = async (doc: SopDocument) => {
  selected.value = doc
  loading.value = true
  try {
    versions.value = await SopApi.versions(doc.id)
  } finally {
    loading.value = false
  }
}
const createDocument = async () => {
  if (
    !documentForm.code.trim() ||
    !documentForm.name.trim() ||
    !documentForm.itemId ||
    !documentForm.processId
  ) {
    message.warning('请填写文档编码、名称、产品及工序')
    return
  }
  saving.value = true
  try {
    const id = await SopApi.createDocument({
      ...documentForm,
      itemId: documentForm.itemId,
      processId: documentForm.processId
    })
    documentDialog.value = false
    Object.assign(documentForm, { code: '', name: '', itemId: undefined, processId: undefined })
    await loadDocuments()
    const created = documents.value.find((doc) => doc.id === id)
    if (created) await selectDocument(created)
    message.success('受控文档已创建，请建立并发布版本后派工')
  } finally {
    saving.value = false
  }
}
const editVersion = (version?: SopVersion, clone = false) => {
  form.value = version ? JSON.parse(JSON.stringify(version)) : emptyVersion()
  if (clone) {
    form.value.id = undefined
    form.value.versionNo = ''
    form.value.status = 'DRAFT'
  }
  versionDialog.value = true
  if (!form.value.steps.length) addStep()
}
const addStep = () =>
  form.value.steps.push({
    title: '',
    instruction: '',
    required: true,
    minViewSeconds: 0,
    photoRequired: false,
    attachments: [],
    parameters: []
  })
const moveStep = (index: number, offset: number) => {
  const step = form.value.steps.splice(index, 1)[0]
  form.value.steps.splice(index + offset, 0, step)
}
const saveVersion = async () => {
  if (
    !form.value.versionNo.trim() ||
    !form.value.title.trim() ||
    !form.value.steps.length ||
    form.value.steps.some((step) => !step.title.trim() || !step.instruction.trim())
  ) {
    message.warning('请填写版本号、标题及每步的标题和操作说明')
    return
  }
  saving.value = true
  try {
    await SopApi.saveVersion(form.value)
    versionDialog.value = false
    await selectDocument(selected.value!)
    message.success('草稿已保存')
  } finally {
    saving.value = false
  }
}
const transition = async (version: SopVersion, action: string) => {
  let comment: string | undefined
  const labels = {
    submit: '提交审批',
    approve: '审批通过',
    reject: '驳回',
    publish: '发布',
    disable: '停用'
  }
  if (['reject', 'disable'].includes(action)) {
    const result = await ElMessageBox.prompt('请填写原因', labels[action], {
      inputValidator: (value) => !!value?.trim() || '原因必填'
    })
    comment = result.value
  } else {
    await ElMessageBox.confirm(`确认${labels[action]}版本 ${version.versionNo}？`, '版本操作', {
      type: 'warning'
    })
  }
  await SopApi.action(version.id!, action, comment)
  await selectDocument(selected.value!)
  message.success('操作成功')
}
onMounted(async () => {
  await Promise.all([
    searchItems(),
    ProProcessApi.getProcessSimpleList().then((rows) => {
      processes.value = rows
    })
  ])
  await loadDocuments()
})
</script>

<style scoped>
.sop-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.sop-toolbar h2,
.sop-toolbar h3,
.sop-toolbar p {
  margin: 0;
}

.sop-toolbar p {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
}

.sop-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 20px;
  margin-top: 20px;
}

.sop-documents {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sop-document {
  display: flex;
  padding: 16px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.sop-document.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary);
}

.sop-document small,
.sop-document span {
  color: var(--el-text-color-secondary);
}

.sop-versions {
  min-width: 0;
}

.sop-editor-step {
  margin-top: 16px;
}

.sop-instruction {
  line-height: 1.8;
  white-space: pre-wrap;
}

@media (width <= 900px) {
  .sop-layout {
    grid-template-columns: 1fr;
  }

  .sop-documents {
    flex-direction: row;
    overflow-x: auto;
  }

  .sop-document {
    min-width: 220px;
  }
}
</style>
