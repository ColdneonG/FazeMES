<template>
  <main class="quality-workbench">
    <header>
      <div><h2>现场质检</h2><p>从待检任务建单，按模板填写检测值、登记缺陷并完成检验。</p></div>
      <el-button size="large" @click="router.push('/mes/workbench')">返回工位</el-button>
    </header>
    <ContentWrap>
      <el-radio-group v-model="stage" size="large" @change="reload">
        <el-radio-button v-for="option in stages" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
      </el-radio-group>
      <div class="actions">
        <el-input v-model="search" placeholder="检验单或来源单编号" clearable @keyup.enter="reload" />
        <el-button size="large" type="primary" @click="reload">查询 / 刷新</el-button>
        <el-button v-if="canCreate" size="large" @click="createInspection()">新建{{ stageLabel }}</el-button>
      </div>
      <p v-if="stage === 'IPQC' && context.workstationId">当前工位 #{{ context.workstationId }}，工单 #{{ context.workOrderId || '—' }}，任务 #{{ context.taskId || '—' }}</p>
      <el-tabs v-model="tab">
        <el-tab-pane label="待检任务" name="pending">
          <div class="cards" v-loading="pendingLoading">
            <article v-for="row in pending" :key="`${row.sourceDocType}-${row.sourceLineId}`">
              <h3>{{ row.itemName || row.itemCode }}</h3>
              <p>{{ row.sourceDocCode }} · {{ row.workstationName || row.vendorName || row.clientName || stageLabel }}</p>
              <strong>待检数量 {{ row.quantity }} {{ row.unitName }}</strong>
              <el-button v-if="canCreate" type="primary" size="large" @click="createInspection(row)">建立检验单</el-button>
            </article>
          </div>
          <el-empty v-if="!pendingLoading && !pending.length" :description="canPending ? '当前条件下没有待检任务' : '当前账号可处理已有检验单，待检任务需另行授权'" />
          <Pagination :total="pendingTotal" v-model:page="pendingPage" v-model:limit="pageSize" @pagination="loadPending" />
        </el-tab-pane>
        <el-tab-pane label="检验单 / 结果登记" name="documents">
          <div class="cards" v-loading="documentLoading">
            <article v-for="row in documents" :key="row.id">
              <div class="document-title"><h3>{{ row.code }}</h3><el-tag :type="row.status === MesQcStatusEnum.DRAFT ? 'warning' : 'success'">{{ row.status === MesQcStatusEnum.DRAFT ? '待完成' : '已完成' }}</el-tag></div>
              <p>{{ row.name }}</p><p>{{ row.itemName || row.itemCode }} · 检测数量 {{ row.checkQuantity }}</p>
              <el-button size="large" type="primary" @click="editInspection(row)">{{ row.status === MesQcStatusEnum.DRAFT && canUpdate ? '填报检测值 / 缺陷 / 完成' : '查看结果' }}</el-button>
            </article>
          </div>
          <el-empty v-if="!documentLoading && !documents.length" description="当前条件下没有检验单" />
          <Pagination :total="documentTotal" v-model:page="documentPage" v-model:limit="pageSize" @pagination="loadDocuments" />
        </el-tab-pane>
      </el-tabs>
    </ContentWrap>
    <IqcForm ref="iqcForm" terminal @success="inspectionSaved" />
    <IpqcForm ref="ipqcForm" terminal @success="inspectionSaved" />
    <OqcForm ref="oqcForm" terminal @success="inspectionSaved" />
  </main>
</template>
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { checkPermi } from '@/utils/permission'
import { QcPendingInspectApi, type QcPendingInspectVO } from '@/api/mes/qc/pendinginspect'
import { QcIqcApi } from '@/api/mes/qc/iqc'
import { QcIpqcApi } from '@/api/mes/qc/ipqc'
import { QcOqcApi } from '@/api/mes/qc/oqc'
import { MesQcTypeEnum, MesQcStatusEnum } from '@/views/mes/utils/constants'
import IqcForm from '../iqc/IqcForm.vue'
import IpqcForm from '../ipqc/IpqcForm.vue'
import OqcForm from '../oqc/OqcForm.vue'

defineOptions({ name: 'MesQualityWorkbench' })
type Stage = 'IQC' | 'IPQC' | 'OQC'
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const stages = [{ value: 'IQC', label: '来料 IQC' }, { value: 'IPQC', label: '过程 IPQC' }, { value: 'OQC', label: '出货 OQC' }]
const stage = ref<Stage>(['IQC', 'IPQC', 'OQC'].includes(String(route.query.stage)) ? String(route.query.stage) as Stage : 'IPQC')
const stageLabel = computed(() => stages.find((option) => option.value === stage.value)?.label)
const canCreate = computed(() => checkPermi([`mes:qc-${stage.value.toLowerCase()}:create`]))
const canUpdate = computed(() => checkPermi([`mes:qc-${stage.value.toLowerCase()}:update`]))
const canPending = computed(() => checkPermi(['mes:qc-pending-inspect:query']))
const canQuery = computed(() => checkPermi([`mes:qc-${stage.value.toLowerCase()}:query`]))
const numberQuery = (key: string) => { const value = Number(route.query[key]); return Number.isSafeInteger(value) && value > 0 ? value : undefined }
const context = computed(() => ({ workstationId: numberQuery('workstationId'), workOrderId: numberQuery('workOrderId'), taskId: numberQuery('taskId'), itemId: numberQuery('itemId'), processId: numberQuery('processId') }))
const tab = ref('pending')
const search = ref('')
const pageSize = ref(12)
const pendingPage = ref(1)
const documentPage = ref(1)
const pending = ref<QcPendingInspectVO[]>([])
const documents = ref<any[]>([])
const pendingTotal = ref(0)
const documentTotal = ref(0)
const pendingLoading = ref(false)
const documentLoading = ref(false)
const iqcForm = ref<InstanceType<typeof IqcForm>>()
const ipqcForm = ref<InstanceType<typeof IpqcForm>>()
const oqcForm = ref<InstanceType<typeof OqcForm>>()
const loadPending = async () => {
  pending.value = []; pendingTotal.value = 0
  if (!canPending.value) return
  pendingLoading.value = true
  try {
    const result = await QcPendingInspectApi.getPendingInspectPage({ pageNo: pendingPage.value, pageSize: pageSize.value, qcType: MesQcTypeEnum[stage.value], sourceDocCode: search.value || undefined, ...(stage.value === 'IPQC' ? context.value : {}) })
    pending.value = result.list; pendingTotal.value = result.total
  } finally { pendingLoading.value = false }
}
const loadDocuments = async () => {
  documents.value = []; documentTotal.value = 0
  if (!canQuery.value) return
  documentLoading.value = true
  try {
    const params = { pageNo: documentPage.value, pageSize: pageSize.value, code: search.value || undefined, ...(stage.value === 'IPQC' ? context.value : {}) }
    const result = stage.value === 'IQC' ? await QcIqcApi.getIqcPage(params) : stage.value === 'IPQC' ? await QcIpqcApi.getIpqcPage(params) : await QcOqcApi.getOqcPage(params)
    documents.value = result.list; documentTotal.value = result.total
  } finally { documentLoading.value = false }
}
const reload = async () => { pendingPage.value = 1; documentPage.value = 1; await Promise.all([loadPending(), loadDocuments()]) }
const createInspection = (row?: QcPendingInspectVO) => {
  const common: any = { inspectorUserId: user.getUser.id, inspectDate: Date.now(), name: (row?.sourceDocCode || context.value.workOrderId || '') + ' ' + stageLabel.value }
  if (row) Object.assign(common, { sourceDocType: row.sourceDocType, sourceDocId: row.sourceDocId, sourceLineId: row.sourceLineId, sourceDocCode: row.sourceDocCode, itemId: row.itemId, vendorId: row.vendorId, clientId: row.clientId })
  if (stage.value === 'IQC') iqcForm.value?.open('create', undefined, { ...common, receivedQuantity: row?.quantity, receiveDate: row?.recordTime } as any)
  else if (stage.value === 'IPQC') ipqcForm.value?.open('create', undefined, { ...context.value, ...common, workOrderId: row?.workOrderId || context.value.workOrderId, workstationId: row?.workstationId || context.value.workstationId, taskId: row?.taskId || context.value.taskId, checkQuantity: row?.quantity } as any)
  else oqcForm.value?.open('create', undefined, { ...common, outQuantity: row?.quantity, outDate: row?.recordTime } as any)
}
const editInspection = (row: any) => {
  const mode = row.status === MesQcStatusEnum.DRAFT && canUpdate.value ? 'update' : 'detail'
  if (stage.value === 'IQC') iqcForm.value?.open(mode, row.id)
  else if (stage.value === 'IPQC') ipqcForm.value?.open(mode, row.id)
  else oqcForm.value?.open(mode, row.id)
}
const inspectionSaved = () => { tab.value = 'documents'; reload() }
watch(() => route.fullPath, () => { if (['IQC', 'IPQC', 'OQC'].includes(String(route.query.stage))) stage.value = String(route.query.stage) as Stage; reload() })
onMounted(reload)
</script>
<style scoped>
.quality-workbench { max-width: 1400px; margin: 0 auto; padding: 20px; }
header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h2 { margin: 0; } header p { color: var(--el-text-color-secondary); }
.actions { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
.actions .el-input { max-width: 300px; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; }
article { border: 1px solid var(--el-border-color); padding: 18px; border-radius: 12px; }
article h3 { margin: 0 0 10px; overflow-wrap: anywhere; } article p { color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
article strong { display: block; margin: 12px 0; } article .el-button { width: 100%; min-height: 46px; margin-top: 12px; }
.document-title { display: flex; gap: 8px; justify-content: space-between; }
@media (max-width: 600px) { .quality-workbench { padding: 10px; } header { align-items: flex-start; } .cards { grid-template-columns: 1fr; } .actions .el-input { max-width: none; } }
</style>
<style>
@media (max-width: 760px) {
  .mes-qc-responsive .el-col { flex: 0 0 100%; max-width: 100%; }
  .mes-qc-responsive .el-form-item__label { width: 104px !important; }
  .mes-qc-responsive .el-dialog__body { padding: 12px; }
  .mes-qc-responsive .el-input, .mes-qc-responsive .el-select, .mes-qc-responsive .el-date-editor { min-height: 40px; }
}
</style>
