<script setup lang="ts">
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  ReportApi,
  type ReportBlockType,
  type ReportConfig,
  type ReportDataset,
  type ReportDefinition,
  type ReportPreview
} from '@/api/mes/report'
import { checkPermi } from '@/utils/permission'
import { formatDate } from '@/utils/formatTime'
import download from '@/utils/download'
import {
  BLOCK_TYPES,
  REPORT_TEMPLATES,
  blockLabel,
  cleanBindings,
  cloneReport,
  createBlock,
  createTemplate,
  newBlockId,
  normalizeCanvas,
  numericFields,
  pageSize,
  querySignature,
  visibleColumns
} from './designerModel'
import VisualReportCanvas from './components/VisualReportCanvas.vue'
import ReportDataPanel from './components/ReportDataPanel.vue'

defineOptions({ name: 'MesReportDesigner' })
const message = useMessage()
const canDesign = computed(() => checkPermi(['mes:report:design']))
const canExport = computed(() => checkPermi(['mes:report:export']))
const canInspectDraft = computed(() => canDesign.value || checkPermi(['mes:report:publish']))
const reports = ref<ReportDefinition[]>([])
const datasets = ref<ReportDataset[]>([])
const id = ref<number>()
const version = ref<number>()
const latestVersion = ref<number>()
const publishedVersion = ref<number>()
const versions = ref<{ version: number; createTime?: string; name?: string }[]>([])
const name = ref('新业务报表')
const config = ref<ReportConfig>(createTemplate('general', []).config)
const saved = ref('')
const signature = computed(() => JSON.stringify({ name: name.value, config: config.value }))
const dirty = computed(() => signature.value !== saved.value)
const mode = ref<'edit' | 'preview'>(canDesign.value ? 'edit' : 'preview')
const opening = ref(false)
const editable = computed(() => canDesign.value && mode.value === 'edit' && !opening.value)
const selectedId = ref('')
const selectedBlock = computed(() =>
  config.value.canvas?.blocks.find((block) => block.id === selectedId.value)
)
const propertyTab = ref('component')
const libraryTab = ref(canDesign.value ? 'components' : 'reports')
const search = ref('')
const category = ref('全部')
const filteredReports = computed(() =>
  reports.value.filter((report) => report.name.includes(search.value.trim()))
)
const filteredTemplates = computed(() =>
  REPORT_TEMPLATES.filter(
    (template) =>
      (category.value === '全部' || template.category === category.value) &&
      template.name.includes(search.value.trim())
  )
)
const datasetName = computed(
  () =>
    datasets.value.find((dataset) => dataset.key === config.value.dataset)?.name ||
    config.value.dataset
)
const dataDrawer = ref(false)
const historyDrawer = ref(false)
const paperPreview = ref(false)
const tableDrawer = ref(false)
const zoom = ref(0.7)
const showGrid = ref(true)
const result = ref<ReportPreview>()
const previewSignature = ref('')
const stale = computed(
  () => !!result.value && previewSignature.value !== querySignature(config.value)
)
const currentResult = computed(() => (stale.value ? undefined : result.value))
const page = ref(1)
const loading = ref(false)
const saving = ref(false)
const exporting = ref(false)
const printing = ref(false)
const error = ref('')
const history = ref<string[]>([])
const historyIndex = ref(-1)
let historyTimer: ReturnType<typeof setTimeout> | undefined
let historySuspended = false
let previewSequence = 0
let openSequence = 0
let active = true
let printStyle: HTMLStyleElement | undefined
const pageBounds = computed(() => pageSize(config.value.canvas!))
const numericColumns = computed(() => numericFields(config.value, datasets.value))
const bindingColumns = computed(() => visibleColumns(config.value))
function selectBlock(blockId: string) {
  selectedId.value = blockId
  propertyTab.value = 'component'
}

function recordHistory() {
  if (historyTimer) clearTimeout(historyTimer)
  historyTimer = undefined
  const value = signature.value
  if (history.value[historyIndex.value] === value) return
  history.value = [...history.value.slice(0, historyIndex.value + 1), value].slice(-60)
  historyIndex.value = history.value.length - 1
}
function resetHistory() {
  if (historyTimer) clearTimeout(historyTimer)
  historyTimer = undefined
  history.value = [signature.value]
  historyIndex.value = 0
}
watch(
  signature,
  () => {
    if (historySuspended) return
    if (historyTimer) clearTimeout(historyTimer)
    historyTimer = setTimeout(recordHistory, 300)
  },
  { flush: 'sync' }
)
function undo(direction: number) {
  recordHistory()
  const target = historyIndex.value + direction
  if (target < 0 || target >= history.value.length) return
  historySuspended = true
  const snapshot = JSON.parse(history.value[target])
  name.value = snapshot.name
  config.value = snapshot.config
  historyIndex.value = target
  historySuspended = false
  if (!selectedBlock.value) selectedId.value = ''
}
watch(
  () => config.value.columns,
  () => cleanBindings(config.value, datasets.value),
  { deep: true }
)
function clampBlocks() {
  const bounds = pageBounds.value
  config.value.canvas!.blocks.forEach((block) => {
    block.width = Math.max(40, Math.min(bounds.width, Math.round(Number(block.width) || 40)))
    block.height = Math.max(16, Math.min(bounds.height, Math.round(Number(block.height) || 16)))
    block.x = Math.max(0, Math.min(bounds.width - block.width, Math.round(Number(block.x) || 0)))
    block.y = Math.max(0, Math.min(bounds.height - block.height, Math.round(Number(block.y) || 0)))
  })
}
function normalizeColors() {
  const valid = (value: unknown) => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value)
  if (!valid(config.value.canvas!.background)) config.value.canvas!.background = '#ffffff'
  config.value.canvas!.blocks.forEach((block) => {
    if (!valid(block.style.color)) block.style.color = '#183247'
    if (!valid(block.style.background)) block.style.background = '#ffffff'
  })
}
function addBlock(type: ReportBlockType, x?: number, y?: number) {
  if (!editable.value) return
  if (config.value.canvas!.blocks.length >= 30) return message.warning('每页最多30个组件')
  const block = createBlock(type, config.value, datasets.value)
  block.x = x ?? 40
  block.y =
    y ??
    Math.min(140 + config.value.canvas!.blocks.length * 24, pageBounds.value.height - block.height)
  config.value.canvas!.blocks.push(block)
  clampBlocks()
  selectedId.value = block.id
  propertyTab.value = 'component'
}
function startPaletteDrag(event: DragEvent, type: ReportBlockType) {
  if (!editable.value) {
    event.preventDefault()
    return
  }
  event.dataTransfer?.setData('application/fazemes-report-component', type)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}
function removeBlock(blockId: string) {
  if (!editable.value) return
  config.value.canvas!.blocks = config.value.canvas!.blocks.filter((block) => block.id !== blockId)
  if (selectedId.value === blockId) selectedId.value = ''
}
function duplicateBlock() {
  if (!selectedBlock.value || config.value.canvas!.blocks.length >= 30) return
  const block = cloneReport(selectedBlock.value)
  block.id = newBlockId()
  block.x += 16
  block.y += 16
  config.value.canvas!.blocks.push(block)
  clampBlocks()
  selectedId.value = block.id
}
function layerBlock(direction: number) {
  const blocks = config.value.canvas!.blocks
  const index = blocks.findIndex((block) => block.id === selectedId.value)
  const next = index + direction
  if (index < 0 || next < 0 || next >= blocks.length) return
  ;[blocks[index], blocks[next]] = [blocks[next], blocks[index]]
}
async function confirmDiscard() {
  if (!dirty.value) return true
  try {
    await message.confirm('当前报表有未保存修改，继续将放弃这些修改。', '未保存的报表')
    return true
  } catch {
    return false
  }
}
async function newReport(templateKey = 'general') {
  if (saving.value) return message.info('正在保存版本，请稍候。')
  if (!(await confirmDiscard())) return
  previewSequence++
  loading.value = false
  const template = createTemplate(templateKey, datasets.value)
  id.value = undefined
  version.value = undefined
  latestVersion.value = undefined
  publishedVersion.value = undefined
  versions.value = []
  name.value = template.name
  config.value = template.config
  saved.value = ''
  mode.value = 'edit'
  result.value = undefined
  selectedId.value = config.value.canvas!.blocks[0]?.id || ''
  resetHistory()
}
async function openReport(reportId: number, selectedVersion?: number, skipConfirm = false) {
  if (saving.value) return message.info('正在保存版本，请稍候。')
  if (!skipConfirm && !(await confirmDiscard())) return
  const sequence = ++openSequence
  opening.value = true
  error.value = ''
  try {
    const data = await ReportApi.get(reportId, selectedVersion)
    if (sequence !== openSequence) return
    id.value = reportId
    name.value = data.definition.name
    version.value = data.version
    latestVersion.value = data.definition.latestVersion
    publishedVersion.value = data.definition.publishedVersion
    versions.value = data.versions
    config.value = normalizeCanvas(data.config, datasets.value)
    saved.value = signature.value
    selectedId.value = ''
    result.value = undefined
    resetHistory()
    await preview(1)
  } catch {
    error.value = '报表加载失败，请稍后重试。'
  } finally {
    if (sequence === openSequence) opening.value = false
  }
}
function previewRequest(p = 1) {
  return canDesign.value
    ? { config: cloneReport(config.value), pageNo: p, pageSize: 100 }
    : { id: id.value, version: version.value, pageNo: p, pageSize: 100 }
}
async function preview(p = 1) {
  const sequence = ++previewSequence
  const query = querySignature(config.value)
  loading.value = true
  error.value = ''
  try {
    const response = await ReportApi.preview(previewRequest(p))
    if (sequence !== previewSequence) return
    result.value = response
    previewSignature.value = query
    page.value = p
  } catch {
    if (sequence === previewSequence)
      error.value = '取数失败，请检查字段、筛选值类型、聚合分组和组件绑定配置。'
  } finally {
    if (sequence === previewSequence) loading.value = false
  }
}
async function save() {
  if (!canDesign.value || saving.value) return
  saving.value = true
  error.value = ''
  const snapshot = signature.value
  try {
    const data = await ReportApi.save({
      id: id.value,
      name: name.value,
      config: cloneReport(config.value),
      expectedVersion: latestVersion.value
    })
    id.value = data.id
    version.value = data.version
    latestVersion.value = data.version
    saved.value = snapshot
    message.success(`已保存草稿 v${data.version}`)
    try {
      const [list, detail] = await Promise.all([
        ReportApi.list(),
        ReportApi.get(data.id, data.version)
      ])
      reports.value = list
      versions.value = detail.versions
    } catch {
      message.warning('版本已保存，列表刷新失败。可稍后重新打开报表查看。')
    }
  } catch {
    error.value = '保存失败。请检查配置是否完整；若存在并发修改，请重新打开最新版本后保存。'
  } finally {
    saving.value = false
  }
}
async function publish() {
  if (!id.value || !version.value || dirty.value) return
  try {
    await ReportApi.publish(id.value, version.value)
    publishedVersion.value = version.value
    reports.value = await ReportApi.list()
    message.success(`已发布 v${version.value}`)
  } catch {
    error.value = '发布失败，请确认此版本仍是最新保存版本。'
  }
}
async function duplicateReport() {
  if (!canDesign.value || saving.value) return
  name.value = `${name.value.slice(0, 90)} · 副本`
  id.value = undefined
  version.value = undefined
  latestVersion.value = undefined
  publishedVersion.value = undefined
  versions.value = []
  saved.value = ''
  mode.value = 'edit'
  resetHistory()
  message.info('副本已在画布打开，保存后将创建独立报表。')
}
async function renameReport() {
  try {
    const response = await ElMessageBox.prompt('名称随下一次保存形成独立版本。', '重命名报表', {
      inputValue: name.value,
      inputValidator: (value) =>
        (!!value?.trim() && value.trim().length <= 100) || '请输入1至100字的名称'
    })
    name.value = response.value.trim()
  } catch {
    /* Dialog cancelled. */
  }
}
async function exportExcel() {
  exporting.value = true
  try {
    const data = await ReportApi.export(previewRequest())
    download.excel(data, `${name.value || '业务报表'}.xlsx`)
  } finally {
    exporting.value = false
  }
}
function exportDesign() {
  download.json(
    new Blob(
      [JSON.stringify({ schemaVersion: 1, name: name.value, config: config.value }, null, 2)],
      { type: 'application/json' }
    ),
    `${name.value || '业务报表'}-设计.json`
  )
}
function finishPrint() {
  document.body.classList.remove('report-studio-print')
  printing.value = false
  printStyle?.remove()
  printStyle = undefined
  window.removeEventListener('afterprint', finishPrint)
}
async function printPaper() {
  if (!currentResult.value) return message.warning('请先刷新真实数据，再打印当前页。')
  printing.value = true
  await nextTick()
  await new Promise<void>((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  )
  printStyle = document.createElement('style')
  printStyle.textContent = `@media print { @page { size: A4 ${config.value.canvas!.orientation}; margin: 0; } }`
  document.head.appendChild(printStyle)
  document.body.classList.add('report-studio-print')
  window.addEventListener('afterprint', finishPrint, { once: true })
  window.print()
}
function keyShortcut(event: KeyboardEvent) {
  if (!active || dataDrawer.value || historyDrawer.value || paperPreview.value) return
  const editingText = (event.target as HTMLElement)?.closest(
    'input, textarea, [contenteditable="true"]'
  )
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && canDesign.value) {
    event.preventDefault()
    void save()
  }
  if (!editable.value || editingText) return
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    undo(event.shiftKey ? 1 : -1)
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'y') {
    event.preventDefault()
    undo(1)
  }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value && canDesign.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onBeforeRouteLeave(async () => !canDesign.value || (await confirmDiscard()))
onActivated(() => {
  active = true
})
onDeactivated(() => {
  active = false
})
onMounted(async () => {
  window.addEventListener('keydown', keyShortcut)
  window.addEventListener('beforeunload', beforeUnload)
  try {
    const [list, definitions] = await Promise.all([ReportApi.datasets(), ReportApi.list()])
    datasets.value = list
    reports.value = definitions
    if (reports.value.length) await openReport(reports.value[0].id, undefined, true)
    else if (canDesign.value) {
      const template = createTemplate('general', datasets.value)
      name.value = template.name
      config.value = template.config
      saved.value = signature.value
      resetHistory()
    } else {
      saved.value = signature.value
      mode.value = 'preview'
    }
  } catch {
    error.value = '报表工作室初始化失败，请刷新页面重试。'
  }
})
onBeforeUnmount(() => {
  if (historyTimer) clearTimeout(historyTimer)
  previewSequence++
  openSequence++
  window.removeEventListener('keydown', keyShortcut)
  window.removeEventListener('beforeunload', beforeUnload)
  finishPrint()
})
</script>

<template>
  <div v-loading="opening" class="visual-report-studio">
    <header class="studio-command">
      <div class="command-identity"
        ><img class="command-mark" src="/fazemes-logo.svg" alt="FazeMES 标识" /><div
          ><small>FAZEMES / REPORT STUDIO</small><h1>可视化报表设计器</h1></div
        ></div
      >
      <div class="save-state" :class="{ 'state-dirty': dirty }"
        ><i></i
        >{{
          saving ? '保存中…' : dirty ? '有未保存修改' : id ? `已保存 v${version}` : '待创建报表'
        }}</div
      >
      <div class="command-actions">
        <el-button v-if="canDesign" @click="newReport()">＋ 新建</el-button
        ><el-button v-if="canDesign" :loading="saving" type="primary" @click="save"
          >保存版本</el-button
        ><el-button v-if="canDesign" @click="mode = mode === 'edit' ? 'preview' : 'edit'">{{
          mode === 'edit' ? '预览画布' : '返回设计'
        }}</el-button
        ><el-button
          v-hasPermi="['mes:report:publish']"
          :disabled="!id || dirty || version !== latestVersion"
          @click="publish"
          >发布</el-button
        >
        <el-dropdown trigger="click"
          ><el-button>导出 / 更多 ▾</el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item v-if="canExport" :disabled="exporting" @click="exportExcel"
                >导出 Excel（查询数据）</el-dropdown-item
              ><el-dropdown-item
                v-if="canExport"
                :disabled="!currentResult || printing"
                @click="printPaper"
                >打印 / 另存 PDF（当前页画布）</el-dropdown-item
              ><el-dropdown-item v-if="canInspectDraft" @click="exportDesign"
                >下载设计 JSON</el-dropdown-item
              ><el-dropdown-item v-if="canDesign" divided @click="duplicateReport"
                >复制为新报表</el-dropdown-item
              ><el-dropdown-item v-if="canDesign" @click="renameReport">重命名</el-dropdown-item
              ><el-dropdown-item :disabled="!id" @click="historyDrawer = true"
                >版本档案</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </header>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="studio-alert"
    />
    <div class="studio-body">
      <aside class="studio-dock">
        <div class="dock-tabs"
          ><button
            v-if="canDesign"
            :class="{ active: libraryTab === 'components' }"
            @click="libraryTab = 'components'"
            >组件</button
          ><button
            v-if="canDesign"
            :class="{ active: libraryTab === 'templates' }"
            @click="libraryTab = 'templates'"
            >模板</button
          ><button :class="{ active: libraryTab === 'reports' }" @click="libraryTab = 'reports'"
            >报表库</button
          ></div
        >
        <template v-if="libraryTab === 'components' && canDesign">
          <div class="dock-section-title">组件库 <span>拖入画布</span></div
          ><div class="component-palette"
            ><button
              v-for="item in BLOCK_TYPES"
              :key="item.type"
              type="button"
              :draggable="editable"
              :disabled="!editable"
              @dragstart="startPaletteDrag($event, item.type)"
              @click="addBlock(item.type)"
              ><b>{{ item.icon }}</b
              ><span
                >{{ item.label }}<small>{{ item.hint }}</small></span
              ></button
            ></div
          >
          <div class="dock-section-title"
            >图层 <span>{{ config.canvas?.blocks.length }} / 30</span></div
          ><div class="layer-list"
            ><button
              v-for="(block, index) in config.canvas?.blocks"
              :key="block.id"
              :class="{ active: selectedId === block.id }"
              @click="selectBlock(block.id)"
              ><span>{{ String(index + 1).padStart(2, '0') }}</span
              ><strong>{{ block.title || block.text || blockLabel(block.type) }}</strong
              ><small>{{ blockLabel(block.type) }}</small></button
            ></div
          ><p class="dock-help"
            >拖动选中组件的顶部手柄移动，右下角调整大小。方向键移动8px，Shift＋方向键移动1px。</p
          >
        </template>
        <template v-else
          ><div class="dock-search"
            ><el-input v-model="search" placeholder="搜索名称" clearable
          /></div>
          <template v-if="libraryTab === 'templates'"
            ><div class="template-categories"
              ><button
                v-for="item in ['全部', '通用', '生产', '质量', '打印']"
                :key="item"
                :class="{ active: category === item }"
                @click="category = item"
                >{{ item }}</button
              ></div
            ><button
              v-for="template in filteredTemplates"
              :key="template.key"
              class="template-card"
              @click="newReport(template.key)"
              ><span class="template-thumbnail">{{ template.icon }}</span
              ><strong>{{ template.name }}</strong
              ><small>{{ template.description }}</small
              ><span class="template-badge">{{ template.category }}模板 · 点击创建</span></button
            ></template
          >
          <template v-else
            ><p class="library-count">最近200份 · {{ filteredReports.length }}份</p
            ><button
              v-for="report in filteredReports"
              :key="report.id"
              class="saved-report"
              :class="{ active: id === report.id }"
              @click="openReport(report.id)"
              ><b>▤</b
              ><div
                ><strong>{{ report.name }}</strong
                ><small
                  >{{ canInspectDraft ? `草稿 v${report.latestVersion} · ` : ''
                  }}{{
                    report.publishedVersion ? `已发布 v${report.publishedVersion}` : '未发布'
                  }}</small
                ></div
              ></button
            ><el-empty v-if="!filteredReports.length" description="暂无报表" :image-size="60"
          /></template>
        </template>
      </aside>
      <main class="designer-workspace">
        <div class="workspace-strip"
          ><div class="workspace-file"
            ><span :class="['file-led', { draft: dirty }]"></span
            ><el-input
              v-model="name"
              class="report-name-input"
              :disabled="!editable"
              maxlength="100"
            /><el-tag size="small" :type="mode === 'edit' ? 'warning' : 'success'">{{
              mode === 'edit' ? '设计' : '只读预览'
            }}</el-tag></div
          ><div class="workspace-tools"
            ><el-button v-if="editable" link :disabled="historyIndex < 1" @click="undo(-1)"
              >↶ 撤销</el-button
            ><el-button
              v-if="editable"
              link
              :disabled="historyIndex >= history.length - 1"
              @click="undo(1)"
              >↷ 重做</el-button
            ><el-button link @click="paperPreview = true">适纸预览</el-button></div
          ></div
        >
        <div class="canvas-toolbar"
          ><div
            ><el-button v-if="editable" size="small" @click="dataDrawer = true"
              >▦ 数据配置</el-button
            ><span class="dataset-label"
              >{{ datasetName }} · {{ bindingColumns.length }}列</span
            ></div
          ><div class="zoom-controls"
            ><el-checkbox v-if="editable" v-model="showGrid" size="small">网格</el-checkbox
            ><button
              type="button"
              :disabled="zoom <= 0.4"
              @click="zoom = Math.max(0.4, +(zoom - 0.1).toFixed(1))"
              >−</button
            ><span>{{ Math.round(zoom * 100) }}%</span
            ><button
              type="button"
              :disabled="zoom >= 1.3"
              @click="zoom = Math.min(1.3, +(zoom + 0.1).toFixed(1))"
              >＋</button
            ></div
          ></div
        >
        <div v-loading="loading" class="canvas-scroll"
          ><VisualReportCanvas
            v-if="config.canvas"
            v-model="config.canvas"
            :selected-id="selectedId"
            :result="currentResult"
            :editable="editable"
            :zoom="zoom"
            :show-grid="showGrid"
            @select="selectedId = $event"
            @add="addBlock"
            @remove="removeBlock"
        /></div>
        <footer class="workspace-status"
          ><div
            ><span>{{
              stale
                ? '查询配置已变更，请刷新数据'
                : result
                  ? `真实数据 · 第${page}页 · ${result.rows.length}行`
                  : '尚未取数，画布不显示虚构数据'
            }}</span
            ><small v-if="result && !stale">{{ formatDate(result.generatedAt) }}</small></div
          ><div
            ><el-button
              size="small"
              :disabled="!result || page === 1 || loading"
              @click="preview(page - 1)"
              >上一页</el-button
            ><el-button
              size="small"
              :disabled="!result?.hasMore || loading"
              @click="preview(page + 1)"
              >下一页</el-button
            ><el-button size="small" :loading="loading" type="primary" @click="preview(1)"
              >刷新真实数据</el-button
            ></div
          ></footer
        >
      </main>
      <aside class="property-panel">
        <div class="property-tabs"
          ><button
            :class="{ active: propertyTab === 'component' }"
            @click="propertyTab = 'component'"
            >组件属性</button
          ><button :class="{ active: propertyTab === 'page' }" @click="propertyTab = 'page'"
            >页面设置</button
          ></div
        >
        <div class="property-content">
          <el-form v-if="propertyTab === 'page'" label-position="top" :disabled="!editable"
            ><h3>纸张与背景</h3
            ><el-form-item label="纸张"><el-input model-value="A4" disabled /></el-form-item
            ><el-form-item label="方向"
              ><el-radio-group v-model="config.canvas!.orientation" @change="clampBlocks"
                ><el-radio-button value="portrait">纵向</el-radio-button
                ><el-radio-button value="landscape">横向</el-radio-button></el-radio-group
              ></el-form-item
            ><el-form-item label="画布背景"
              ><el-color-picker
                v-model="config.canvas!.background"
                @change="normalizeColors" /></el-form-item
            ><p class="property-note"
              >{{ pageBounds.width }} ×
              {{ pageBounds.height }} px。布局保存到当前报表版本；更改方向会自动约束组件边界。</p
            ><h3>输出说明</h3
            ><p class="property-note"
              >Excel导出服务端完整查询结果；打印 / PDF
              输出当前页A4画布。图表、指标和表格均基于当前预览页。</p
            ></el-form
          >
          <template v-else-if="selectedBlock">
            <div class="property-title"
              ><h3>{{ blockLabel(selectedBlock.type) }}</h3
              ><span>{{ selectedBlock.id.slice(0, 10) }}</span></div
            >
            <el-form label-position="top" :disabled="!editable" size="small">
              <el-form-item
                v-if="selectedBlock.type !== 'text' && selectedBlock.type !== 'divider'"
                label="组件标题"
                ><el-input v-model="selectedBlock.title" maxlength="100" /></el-form-item
              ><el-form-item v-if="selectedBlock.type === 'text'" label="文本内容"
                ><el-input
                  v-model="selectedBlock.text"
                  type="textarea"
                  :rows="4"
                  maxlength="4000"
                  show-word-limit
              /></el-form-item>
              <div class="property-grid"
                ><el-form-item label="X / px"
                  ><el-input-number
                    v-model="selectedBlock.x"
                    :min="0"
                    :max="pageBounds.width - selectedBlock.width"
                    :controls="false"
                    @change="clampBlocks" /></el-form-item
                ><el-form-item label="Y / px"
                  ><el-input-number
                    v-model="selectedBlock.y"
                    :min="0"
                    :max="pageBounds.height - selectedBlock.height"
                    :controls="false"
                    @change="clampBlocks" /></el-form-item
                ><el-form-item label="宽度 / px"
                  ><el-input-number
                    v-model="selectedBlock.width"
                    :min="40"
                    :max="pageBounds.width - selectedBlock.x"
                    :controls="false"
                    @change="clampBlocks" /></el-form-item
                ><el-form-item label="高度 / px"
                  ><el-input-number
                    v-model="selectedBlock.height"
                    :min="16"
                    :max="pageBounds.height - selectedBlock.y"
                    :controls="false"
                    @change="clampBlocks" /></el-form-item
              ></div>
              <h3>外观</h3
              ><div class="property-grid"
                ><el-form-item label="字号"
                  ><el-input-number
                    v-model="selectedBlock.style.fontSize"
                    :min="8"
                    :max="72"
                    :controls="false" /></el-form-item
                ><el-form-item label="对齐"
                  ><el-select v-model="selectedBlock.style.align"
                    ><el-option label="左对齐" value="left" /><el-option
                      label="居中"
                      value="center" /><el-option
                      label="右对齐"
                      value="right" /></el-select></el-form-item
                ><el-form-item label="文字颜色"
                  ><el-color-picker
                    v-model="selectedBlock.style.color"
                    @change="normalizeColors" /></el-form-item
                ><el-form-item label="背景颜色"
                  ><el-color-picker
                    v-model="selectedBlock.style.background"
                    @change="normalizeColors" /></el-form-item></div
              ><div class="style-switches"
                ><el-checkbox v-model="selectedBlock.style.bold">加粗</el-checkbox
                ><el-checkbox v-model="selectedBlock.style.border">边框</el-checkbox></div
              >
              <template v-if="!['text', 'divider'].includes(selectedBlock.type)"
                ><h3>数据绑定</h3><div class="binding-source">{{ datasetName }} · 当前查询</div
                ><el-form-item
                  v-if="['bar', 'line', 'pie'].includes(selectedBlock.type)"
                  label="维度列"
                  ><el-select
                    v-model="selectedBlock.binding.dimension"
                    clearable
                    placeholder="默认使用行序号"
                    ><el-option
                      v-for="column in bindingColumns"
                      :key="column.field"
                      :label="column.title"
                      :value="column.field" /></el-select></el-form-item
                ><el-form-item v-if="selectedBlock.type !== 'table'" label="数值列"
                  ><el-select
                    v-model="selectedBlock.binding.metrics"
                    multiple
                    :multiple-limit="['pie', 'metric'].includes(selectedBlock.type) ? 1 : 5"
                    placeholder="选择已显示的数值列"
                    ><el-option
                      v-for="column in numericColumns"
                      :key="column.field"
                      :label="column.title"
                      :value="column.field" /></el-select></el-form-item
                ><el-form-item v-if="selectedBlock.type === 'metric'" label="当前页汇总"
                  ><el-select v-model="selectedBlock.binding.summary"
                    ><el-option
                      v-for="summary in ['SUM', 'AVG', 'MIN', 'MAX', 'COUNT']"
                      :key="summary"
                      :value="summary"
                      :label="summary" /></el-select></el-form-item
                ><el-form-item v-if="selectedBlock.type !== 'metric'" label="显示当前页前 N 行"
                  ><el-input-number
                    v-model="selectedBlock.binding.rowLimit"
                    :min="1"
                    :max="100" /></el-form-item
                ><p class="property-note">{{
                  selectedBlock.type === 'table'
                    ? '表格使用查询中全部显示列；在数据配置中调整列顺序。超出组件高度的行将被裁切。'
                    : '汇总与图表只使用当前页；如需全数据汇总，请在数据配置中设置分组与聚合后刷新。'
                }}</p
                ><el-button class="full-width" @click="dataDrawer = true"
                  >打开字段与筛选配置</el-button
                ></template
              >
            </el-form>
            <div v-if="editable" class="component-actions"
              ><el-button
                size="small"
                :disabled="config.canvas!.blocks.length >= 30"
                @click="duplicateBlock"
                >复制</el-button
              ><el-button size="small" @click="layerBlock(1)">上移一层</el-button
              ><el-button size="small" @click="layerBlock(-1)">下移一层</el-button
              ><el-button size="small" type="danger" plain @click="removeBlock(selectedId)"
                >删除</el-button
              ></div
            >
          </template>
          <div v-else class="no-selection"
            ><span>⌖</span><strong>选择画布中的组件</strong
            ><p>编辑位置、样式和数据绑定。也可拖动组件库中的内容到画布。</p
            ><el-button v-if="editable" @click="dataDrawer = true">配置业务数据</el-button></div
          >
        </div>
        <div class="property-footer"
          ><button @click="tableDrawer = true">▦ 查看当前页数据</button
          ><button :disabled="!id" @click="historyDrawer = true">◷ 版本档案</button></div
        >
      </aside>
    </div>
    <el-drawer
      v-model="dataDrawer"
      title="业务数据与字段绑定"
      size="min(860px, 95vw)"
      append-to-body
      ><ReportDataPanel
        v-if="dataDrawer && canDesign"
        v-model="config"
        :datasets="datasets"
      /><template #footer
        ><el-button @click="dataDrawer = false">返回画布</el-button
        ><el-button type="primary" :loading="loading" @click="preview(1)"
          >应用配置并刷新数据</el-button
        ></template
      ></el-drawer
    >
    <el-drawer v-model="historyDrawer" title="报表版本档案" size="420px" append-to-body
      ><p class="version-intro">每次保存均创建不可变版本。打开历史版本后，可另存为新的最新版本。</p
      ><button
        v-for="item in versions"
        :key="item.version"
        class="version-entry"
        :class="{ active: version === item.version }"
        @click="openReport(id!, item.version)"
        ><div
          ><strong>版本 {{ item.version }}</strong
          ><el-tag v-if="publishedVersion === item.version" type="success" size="small"
            >当前发布</el-tag
          ><el-tag v-if="latestVersion === item.version" size="small">最新</el-tag></div
        ><span>{{ item.name || name }}</span
        ><small>{{ item.createTime ? formatDate(item.createTime) : '' }}</small></button
      ></el-drawer
    >
    <el-drawer v-model="tableDrawer" title="当前页查询数据" size="min(1100px, 95vw)" append-to-body
      ><el-alert
        :title="
          stale
            ? '查询配置已变更，下方是上次取数结果。请刷新真实数据。'
            : '以下为当前预览页；Excel导出会重新查询全部结果，最多5000行。'
        "
        :type="stale ? 'warning' : 'info'"
        :closable="false" /><el-table
        v-if="result"
        :data="result.rows"
        border
        stripe
        class="raw-data-table"
        ><el-table-column
          v-for="column in result.columns"
          :key="column.key"
          :prop="column.key"
          :label="column.title"
          :min-width="column.width"
          show-overflow-tooltip /></el-table
      ><el-empty v-else description="尚未取数"
    /></el-drawer>
    <el-dialog
      v-model="paperPreview"
      title="A4纸张预览 · 当前预览页"
      width="min(1200px, 96vw)"
      append-to-body
      destroy-on-close
      ><div class="paper-preview-scroll"
        ><VisualReportCanvas
          v-if="config.canvas"
          :model-value="config.canvas"
          :result="currentResult"
          :zoom="config.canvas.orientation === 'landscape' ? 0.85 : 0.8" /></div
      ><template #footer
        ><span class="preview-page-note">{{
          currentResult
            ? `第${page}页 · ${currentResult.rows.length}行，组件按各自行数上限展示`
            : '请刷新真实数据后查看业务内容'
        }}</span
        ><el-button @click="paperPreview = false">关闭</el-button
        ><el-button
          v-hasPermi="['mes:report:export']"
          type="primary"
          :disabled="!currentResult || printing"
          @click="printPaper"
          >打印 / 另存 PDF</el-button
        ></template
      ></el-dialog
    >
    <Teleport to="body"
      ><div v-if="printing" class="report-print-host" :class="config.canvas!.orientation"
        ><VisualReportCanvas :model-value="config.canvas!" :result="currentResult" :zoom="1" /></div
    ></Teleport>
  </div>
</template>

<style scoped>
.visual-report-studio {
  display: flex;
  height: calc(100vh - 145px);
  min-height: 760px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  flex-direction: column;
}

.studio-command {
  display: flex;
  min-height: 77px;
  padding: 12px 20px;
  gap: 18px;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-light);
}

.command-identity {
  display: flex;
  min-width: 205px;
  gap: 11px;
  align-items: center;
}

.command-mark {
  display: block;
  width: 54px;
  height: 36px;
  object-fit: contain;
  flex-shrink: 0;
}

.command-identity small {
  display: block;
  font-size: 9px;
  letter-spacing: 1.7px;
  color: #778e9c;
}

.command-identity h1 {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 600;
}

.save-state {
  display: flex;
  min-width: 100px;
  font-size: 11px;
  color: #7b8e9a;
  gap: 6px;
  align-items: center;
}

.save-state i,
.file-led {
  width: 6px;
  height: 6px;
  background: #35b696;
  border-radius: 50%;
}

.state-dirty i,
.file-led.draft {
  background: #e8ab4e;
}

.command-actions {
  display: flex;
  margin-left: auto;
  gap: 7px;
  align-items: center;
  flex-wrap: wrap;
}

.command-actions .el-button {
  margin: 0;
}

.studio-alert {
  flex-shrink: 0;
  border-radius: 0;
}

.studio-body {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: 218px minmax(0, 1fr) 268px;
}

.studio-dock,
.property-panel {
  min-height: 0;
  overflow-y: auto;
  background: var(--el-bg-color);
}

.studio-dock {
  border-right: 1px solid var(--el-border-color-light);
}

.dock-tabs,
.property-tabs {
  display: flex;
  height: 46px;
  padding: 0 12px;
  gap: 16px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.dock-tabs button,
.property-tabs button {
  padding: 0 1px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
}

.dock-tabs button.active,
.property-tabs button.active {
  font-weight: 600;
  color: #137e93;
  border-bottom-color: #137e93;
}

.dock-section-title {
  display: flex;
  padding: 18px 14px 12px;
  font-size: 12px;
  font-weight: 600;
  justify-content: space-between;
}

.dock-section-title span {
  font-size: 10px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.component-palette {
  display: flex;
  padding: 0 12px;
  gap: 7px;
  flex-direction: column;
}

.component-palette button {
  display: flex;
  padding: 10px;
  text-align: left;
  cursor: grab;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  gap: 10px;
  align-items: center;
}

.component-palette button:hover {
  background: var(--el-color-primary-light-9);
  border-color: #91bec8;
}

.component-palette button:disabled {
  cursor: default;
  opacity: 0.6;
}

.component-palette b {
  display: grid;
  width: 29px;
  height: 29px;
  font-size: 23px;
  font-weight: 400;
  color: #137e93;
  background: #eef7f8;
  border-radius: 5px;
  place-items: center;
}

.component-palette span {
  font-size: 12px;
  color: var(--el-text-color-primary);
}

.component-palette small {
  display: block;
  margin-top: 4px;
  font-size: 9px;
  color: var(--el-text-color-secondary);
}

.layer-list {
  padding: 0 8px;
}

.layer-list button {
  display: flex;
  width: 100%;
  padding: 10px 7px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 5px;
  gap: 8px;
  align-items: center;
}

.layer-list strong {
  overflow: hidden;
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.layer-list small {
  font-size: 9px;
}

.layer-list button.active {
  background: #eaf5f7;
}

.dock-help {
  padding: 5px 14px;
  font-size: 10px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.dock-search {
  padding: 14px 12px;
}

.template-categories {
  display: flex;
  padding: 0 12px 8px;
  gap: 4px;
  flex-wrap: wrap;
}

.template-categories button {
  padding: 4px 7px;
  font-size: 10px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 4px;
}

.template-categories button.active {
  color: #137e93;
  background: #eaf5f7;
}

.template-card {
  display: flex;
  width: calc(100% - 24px);
  padding: 12px;
  margin: 0 12px 10px;
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-light);
  border-radius: 7px;
  flex-direction: column;
}

.template-thumbnail {
  display: grid;
  width: 100%;
  height: 62px;
  margin-bottom: 10px;
  font-size: 38px;
  color: #92b6c0;
  background: linear-gradient(135deg, #edf5f7, #f7fafb);
  border: 1px solid #e6eef2;
  border-radius: 4px;
  place-items: center;
}

.template-card strong {
  font-size: 12px;
  color: var(--el-text-color-primary);
}

.template-card small {
  margin-top: 6px;
  font-size: 10px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.template-badge {
  margin-top: 9px;
  font-size: 9px;
  color: #137e93;
}

.library-count {
  margin: 0 14px 12px;
  font-size: 10px;
  color: var(--el-text-color-secondary);
}

.saved-report {
  display: flex;
  width: calc(100% - 20px);
  padding: 13px 10px;
  margin: 0 10px 7px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  gap: 9px;
}

.saved-report.active {
  background: #edf7f8;
  border-color: #83b4bf;
}

.saved-report b {
  font-size: 23px;
  font-weight: 400;
  color: #6594a3;
}

.saved-report strong {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-primary);
}

.saved-report small {
  display: block;
  margin-top: 7px;
  font-size: 9px;
  color: var(--el-text-color-secondary);
}

.designer-workspace {
  display: flex;
  min-width: 0;
  min-height: 0;
  background: #eaf0f4;
  flex-direction: column;
}

.workspace-strip {
  display: flex;
  min-height: 46px;
  padding: 0 14px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.workspace-file,
.workspace-tools {
  display: flex;
  min-width: 0;
  gap: 8px;
  align-items: center;
}

.workspace-file {
  flex: 1;
}

.file-led {
  flex-shrink: 0;
}

.report-name-input {
  max-width: 245px;
}

.report-name-input :deep(.el-input__wrapper) {
  padding: 1px 5px;
  background: transparent;
  box-shadow: none;
}

.report-name-input :deep(.el-input__inner) {
  font-size: 12px;
}

.workspace-tools .el-button {
  margin: 0;
  font-size: 11px;
}

.canvas-toolbar {
  display: flex;
  min-height: 43px;
  padding: 0 14px;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.canvas-toolbar > div,
.zoom-controls {
  display: flex;
  gap: 9px;
  align-items: center;
}

.dataset-label,
.zoom-controls {
  font-size: 10px;
  color: #718696;
}

.zoom-controls button {
  width: 22px;
  height: 22px;
  padding: 0;
  color: #526b7c;
  cursor: pointer;
  background: #fff;
  border: 1px solid #d6e0e6;
  border-radius: 4px;
}

.canvas-scroll {
  min-height: 300px;
  padding: 34px 28px;
  overflow: auto;
  flex: 1;
  background-image: radial-gradient(#cddae2 0.8px, transparent 0.8px);
  background-size: 12px 12px;
}

.workspace-status {
  display: flex;
  padding: 10px 12px;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-light);
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.workspace-status span {
  font-size: 10px;
  color: var(--el-text-color-secondary);
}

.workspace-status small {
  display: block;
  margin-top: 4px;
  font-size: 9px;
  color: #8a9ca8;
}

.workspace-status .el-button {
  margin: 0 0 0 5px;
}

.property-panel {
  display: flex;
  border-left: 1px solid var(--el-border-color-light);
  flex-direction: column;
}

.property-tabs {
  padding-left: 18px;
  gap: 25px;
  flex-shrink: 0;
}

.property-content {
  padding: 16px;
  overflow-y: auto;
  flex: 1;
}

.property-content h3 {
  margin: 6px 0 16px;
  font-size: 12px;
  font-weight: 600;
}

.property-content .el-form-item {
  margin-bottom: 15px;
}

.property-content :deep(.el-form-item__label) {
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.property-title {
  display: flex;
  gap: 5px;
  justify-content: space-between;
}

.property-title span {
  font-size: 9px;
  color: #9aaab5;
}

.property-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 10px;
}

.property-grid .el-input-number,
.full-width {
  width: 100%;
}

.style-switches {
  display: flex;
  margin-bottom: 20px;
  gap: 15px;
}

.style-switches .el-checkbox {
  margin: 0;
}

.binding-source {
  padding: 8px;
  margin-bottom: 14px;
  font-size: 10px;
  color: #3d8496;
  background: #eef6f8;
  border-radius: 4px;
}

.property-note {
  margin: 6px 0 17px;
  font-size: 10px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.component-actions {
  display: flex;
  padding-top: 18px;
  margin-top: 18px;
  border-top: 1px solid var(--el-border-color-light);
  gap: 6px;
  flex-wrap: wrap;
}

.component-actions .el-button {
  margin: 0;
}

.no-selection {
  padding: 40px 4px;
  text-align: center;
}

.no-selection > span {
  display: block;
  margin-bottom: 15px;
  font-size: 35px;
  color: #9ab7c4;
}

.no-selection strong {
  font-size: 12px;
  font-weight: 500;
}

.no-selection p {
  margin: 10px 0 24px;
  font-size: 11px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.property-footer {
  display: flex;
  padding: 13px 10px;
  border-top: 1px solid var(--el-border-color-light);
  gap: 10px;
  flex-shrink: 0;
  justify-content: space-around;
}

.property-footer button {
  padding: 0;
  font-size: 10px;
  color: #6d8797;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.version-intro {
  margin-top: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.version-entry {
  display: block;
  width: 100%;
  padding: 15px;
  margin: 12px 0;
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-light);
  border-radius: 7px;
}

.version-entry.active {
  background: #f0f8fa;
  border-color: #91bfcb;
}

.version-entry > div {
  display: flex;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-primary);
  gap: 8px;
  align-items: center;
}

.version-entry > span {
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.version-entry > small {
  display: block;
  margin-top: 7px;
  font-size: 10px;
  color: var(--el-text-color-secondary);
}

.raw-data-table {
  margin-top: 18px;
}

.paper-preview-scroll {
  max-height: 72vh;
  padding: 25px;
  overflow: auto;
  background: #eaf0f4;
}

.preview-page-note {
  margin-right: 14px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

@media (width <= 1300px) {
  .studio-body {
    grid-template-columns: 180px minmax(0, 1fr) 240px;
  }

  .save-state {
    display: none;
  }

  .command-identity {
    min-width: 180px;
  }

  .studio-command {
    padding: 12px;
  }

  .workspace-tools .el-button {
    font-size: 10px;
  }
}

@media (width <= 1050px) {
  .visual-report-studio {
    height: auto;
    overflow: visible;
  }

  .studio-body {
    grid-template-columns: 180px minmax(0, 1fr);
  }

  .designer-workspace {
    height: 850px;
  }

  .property-panel {
    max-height: 500px;
    border-top: 1px solid var(--el-border-color-light);
    grid-column: 1 / -1;
  }

  .studio-command {
    flex-wrap: wrap;
  }

  .command-actions {
    margin-left: 0;
  }

  .workspace-status {
    flex-wrap: wrap;
  }
}

@media (width <= 700px) {
  .studio-body {
    display: flex;
    flex-direction: column;
  }

  .studio-dock {
    max-height: 300px;
  }

  .component-palette {
    flex-flow: row wrap;
  }

  .component-palette button {
    width: calc(50% - 4px);
  }

  .layer-list,
  .dock-help {
    display: none;
  }

  .canvas-toolbar {
    flex-wrap: wrap;
  }

  .dataset-label,
  .workspace-tools {
    display: none;
  }
}
</style>

<style>
.report-print-host {
  position: fixed;
  top: 0;
  left: -100000px;
  z-index: -1;
  width: 794px;
  height: 1123px;
  overflow: hidden;
  color-scheme: light;
}

.report-print-host.landscape {
  width: 1123px;
  height: 794px;
}

@media print {
  body.report-studio-print {
    padding: 0 !important;
    margin: 0 !important;
    background: #fff !important;
  }

  body.report-studio-print > :not(.report-print-host) {
    display: none !important;
  }

  body.report-studio-print > .report-print-host {
    position: static;
    display: block;
    width: 210mm;
    height: 297mm;
    overflow: hidden;
    print-color-adjust: exact;
  }

  body.report-studio-print > .report-print-host.landscape {
    width: 297mm;
    height: 210mm;
  }

  .report-print-host .report-paper {
    transform: scale(0.999) !important;
    box-shadow: none !important;
  }
}
</style>
