<template>
  <ContentWrap>
    <div class="trace-heading">
      <div>
        <h2>单件 SN 用料追溯</h2>
        <p>登记单件身份，按工艺顺序绑定已完成报工，保留实际用料批次与过站履历。</p>
      </div>
      <el-button v-hasPermi="['mes:sn-trace:create']" type="primary" @click="openRegister">
        登记单件 SN
      </el-button>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="SN 履历与过站" name="sn">
        <el-form :inline="true" :model="query" @submit.prevent="search">
          <el-form-item label="生产工单">
            <ProWorkOrderSelect v-model="query.workOrderId" class="!w-240px" />
          </el-form-item>
          <el-form-item label="SN 编号">
            <el-input
              v-model="query.snCode"
              clearable
              placeholder="扫描或输入 SN"
              @keyup.enter="search"
            />
          </el-form-item>
          <el-form-item label="产品批次">
            <el-input v-model="query.batchCode" clearable @keyup.enter="search" />
          </el-form-item>
          <el-form-item
            ><el-button :loading="loading" @click="search">查询</el-button></el-form-item
          >
        </el-form>
        <el-table v-loading="loading" :data="rows" stripe>
          <el-table-column prop="snCode" label="SN 编号" min-width="190" />
          <el-table-column prop="workOrderId" label="工单 ID" width="100" />
          <el-table-column prop="productId" label="产品 ID" width="100" />
          <el-table-column label="产品批次" min-width="150">
            <template #default="{ row }">{{ row.productBatchCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="追溯状态" width="130">
            <template #default="{ row }">
              <el-tag :type="row.status === 'COMPLETED' ? 'success' : 'info'">
                {{ row.status === 'COMPLETED' ? '全工序已登记' : '待过站登记' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showDetail(row.id)">查看履历</el-button>
              <el-button
                v-if="row.status !== 'COMPLETED'"
                v-hasPermi="['mes:sn-trace:station']"
                link
                type="primary"
                @click="openStation(row)"
                >单件过站</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <Pagination
          v-model:page="query.pageNo"
          v-model:limit="query.pageSize"
          :total="total"
          @pagination="load"
        />
      </el-tab-pane>
      <el-tab-pane label="原料批次反查" name="batch">
        <el-form :inline="true" @submit.prevent="reverseLookup">
          <el-form-item label="原料批次号">
            <el-input
              v-model="reverseBatch"
              placeholder="输入实际耗用的原料批次号"
              clearable
              @keyup.enter="reverseLookup"
            />
          </el-form-item>
          <el-form-item
            ><el-button :loading="reverseLoading" @click="reverseLookup"
              >查询受影响 SN</el-button
            ></el-form-item
          >
        </el-form>
        <el-alert
          title="仅返回已分配该原料批次实际耗料的 SN；尚未登记的单件不计入此清单。"
          type="info"
          :closable="false"
          class="mb-16px"
        />
        <el-table v-loading="reverseLoading" :data="affected" stripe>
          <el-table-column prop="snCode" label="受影响 SN" min-width="190" />
          <el-table-column prop="workOrderId" label="工单 ID" width="120" />
          <el-table-column prop="productId" label="产品 ID" width="120" />
          <el-table-column label="产品批次" min-width="150">
            <template #default="{ row }">{{ row.productBatchCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120"
            ><template #default="{ row }">
              <el-button link type="primary" @click="showDetail(row.id)">查看履历</el-button>
            </template></el-table-column
          >
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </ContentWrap>

  <Dialog v-model="registerVisible" title="登记单件 SN" width="560px">
    <el-alert
      title="产品和生产批次取自工单。已有 SN 必须匹配该工单产品和批次，未归属工单的 SN 可在此认领。"
      type="info"
      :closable="false"
      class="mb-16px"
    />
    <el-form label-width="100px" @submit.prevent="register">
      <el-form-item label="生产工单" required>
        <ProWorkOrderSelect v-model="registration.workOrderId" class="!w-full" />
      </el-form-item>
      <el-form-item label="登记方式">
        <el-radio-group v-model="registration.mode">
          <el-radio value="code">扫描 / 输入编号</el-radio
          ><el-radio value="id">已有 SN ID</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="registration.mode === 'code'" label="SN 编号" required>
        <el-input
          v-model="registration.snCode"
          maxlength="128"
          placeholder="已有编号可直接认领；新编号将同时生成条码"
        />
      </el-form-item>
      <el-form-item v-else label="已有 SN ID" required>
        <el-input-number
          v-model="registration.snId"
          :min="1"
          :precision="0"
          controls-position="right"
        />
      </el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="registerVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="register"
        >登记并查看履历</el-button
      ></template
    >
  </Dialog>

  <Dialog v-model="stationVisible" :title="`单件过站 · ${selected?.snCode || ''}`" width="1000px">
    <el-alert
      title="选择该单件实际参与的已完成报工。每条耗料行须分配完整一件份额，可从多个实际批次拆分；提交后保存为不可编辑快照。"
      type="info"
      :closable="false"
      class="mb-16px"
    />
    <el-form label-width="100px">
      <el-form-item label="已完成报工" required>
        <el-select
          v-model="feedbackId"
          filterable
          placeholder="选择同工单已完成报工"
          class="!w-full"
          @change="loadSources"
        >
          <el-option
            v-for="f in feedbacks"
            :key="f.id"
            :value="f.id"
            :label="`${f.code} · 任务 ${f.taskId} · 工序 ${f.processId} · 产量 ${f.feedbackQuantity}`"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <p v-if="!feedbacks.length" class="hint"
      >此工单暂无已完成报工。完成报工审批及必要检验后再登记。</p
    >
    <template v-if="feedbackId">
      <div class="source-heading"
        ><strong>实际已耗用的工单 WIP 批次</strong>
        <el-button :disabled="sourcesLoading || !sources.length" @click="allocateOne"
          >按剩余批次分配一件</el-button
        >
      </div>
      <el-table v-loading="sourcesLoading" :data="sources" stripe>
        <el-table-column prop="consumeLineId" label="耗料行 ID" width="100" />
        <el-table-column prop="itemId" label="物料 ID" width="90" />
        <el-table-column label="实际批次" min-width="160"
          ><template #default="{ row }"
            >{{ row.batchCode || '未分批'
            }}<span v-if="row.batchId"> / {{ row.batchId }}</span></template
          ></el-table-column
        >
        <el-table-column prop="quantity" label="实际耗量" width="100" />
        <el-table-column prop="availableQuantity" label="可分配量" width="100" />
        <el-table-column prop="unitQuantity" label="该行一件用量" width="120" />
        <el-table-column label="分配给本 SN" width="180"
          ><template #default="{ row }">
            <MesQuantityInput v-model="row.inputQuantity" class="!w-160px" /> </template
        ></el-table-column>
      </el-table>
      <p v-if="!sourcesLoading && !sources.length" class="hint"
        >该工序没有实际物料消耗，提交将仅保存过站履历。</p
      >
      <p v-for="line in lineTotals" :key="line.id" class="hint" :class="{ invalid: !line.valid }">
        耗料行 {{ line.id }}：已分配 {{ line.quantity }} / 单件要求 {{ line.required }}
      </p>
    </template>
    <template #footer
      ><el-button @click="stationVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="
          !feedbackId || !sourceReady || sourcesLoading || lineTotals.some((l) => !l.valid)
        "
        @click="submitStation"
        >确认过站并保存用料快照</el-button
      ></template
    >
  </Dialog>

  <Dialog v-model="detailVisible" title="单件追溯履历" width="1000px">
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions :column="3" border class="mb-16px">
          <el-descriptions-item label="SN 编号">{{ detail.sn.snCode }}</el-descriptions-item>
          <el-descriptions-item label="工单 ID">{{ detail.sn.workOrderId }}</el-descriptions-item>
          <el-descriptions-item label="产品 ID">{{ detail.sn.productId }}</el-descriptions-item>
          <el-descriptions-item label="产品批次">{{
            detail.sn.productBatchCode || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{
            detail.sn.status === 'COMPLETED' ? '全工序已登记' : '待过站登记'
          }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-if="!detail.stations.length" description="已登记单件身份，尚未绑定过站报工" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="station in detail.stations"
            :key="station.id"
            :timestamp="formatDate(station.stationTime)"
            placement="top"
          >
            <el-card shadow="never">
              <strong
                >工序 {{ station.processId }} · 顺序 {{ station.processSort }} · 报工
                {{ station.feedbackCode }}</strong
              >
              <p class="hint"
                >任务 {{ station.taskId }} · 工作站 {{ station.workstationId || '—' }} · 登记人
                {{ station.operatorId }} · 报工人 {{ station.feedbackUserId || '—' }}</p
              >
              <el-table v-if="station.materials.length" :data="station.materials" size="small">
                <el-table-column prop="itemId" label="物料 ID" width="100" />
                <el-table-column prop="batchCode" label="实际原料批次" min-width="160" />
                <el-table-column prop="batchId" label="批次 ID" width="100" />
                <el-table-column prop="quantity" label="本件耗用量" width="110" />
                <el-table-column prop="consumeDetailId" label="耗料明细 ID" width="120" />
                <el-table-column prop="materialStockId" label="WIP 台账 ID" width="120" />
              </el-table>
              <p v-else class="hint">此工序无实际耗料</p>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </template>
    </div>
  </Dialog>
</template>

<script setup lang="ts">
import MesQuantityInput from '@/views/mes/components/MesQuantityInput.vue'
import { quantityUnits } from '@/utils/mesQuantity'
import { snAllocationTotals, distributeSnMaterials } from './snQuantities'
import { formatDate } from '@/utils/formatTime'
import {
  SnTraceApi,
  type SnTrace,
  type TraceDetail,
  type FeedbackOption,
  type ConsumeOption
} from '@/api/mes/pro/sntrace'
import ProWorkOrderSelect from '@/views/mes/pro/workorder/components/ProWorkOrderSelect.vue'

defineOptions({ name: 'MesProSnTrace' })
const route = useRoute()
const message = useMessage()
const tab = ref('sn')
const query = reactive({
  pageNo: 1,
  pageSize: 10,
  workOrderId: undefined as number | undefined,
  snCode: '',
  batchCode: ''
})
const rows = ref<SnTrace[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const registerVisible = ref(false)
const registration = reactive({
  workOrderId: undefined as number | undefined,
  mode: 'code',
  snCode: '',
  snId: undefined as number | undefined
})
const selected = ref<SnTrace>()
const stationVisible = ref(false)
const feedbacks = ref<FeedbackOption[]>([])
const feedbackId = ref<number>()
const sources = ref<(ConsumeOption & { inputQuantity: string })[]>([])
const sourcesLoading = ref(false)
const sourceReady = ref(false)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TraceDetail>()
const reverseBatch = ref('')
const affected = ref<SnTrace[]>([])
const reverseLoading = ref(false)
let retryKey = ''
let retryPayload = ''

const lineTotals = computed(() => snAllocationTotals(sources.value))

async function load() {
  loading.value = true
  try {
    const result = await SnTraceApi.page(query)
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}
function search() {
  query.pageNo = 1
  load()
}
function openRegister() {
  registration.workOrderId = query.workOrderId
  registration.snCode = query.snCode
  registration.snId = undefined
  registerVisible.value = true
}
async function register() {
  if (
    !registration.workOrderId ||
    (registration.mode === 'code' ? !registration.snCode.trim() : !registration.snId)
  ) {
    message.warning('请选择工单，并输入 SN 编号或已有 SN ID')
    return
  }
  saving.value = true
  try {
    const id = await SnTraceApi.register({
      workOrderId: registration.workOrderId,
      ...(registration.mode === 'code'
        ? { snCode: registration.snCode.trim() }
        : { snId: registration.snId })
    })
    registerVisible.value = false
    query.workOrderId = registration.workOrderId
    message.success('SN 已登记')
    await Promise.all([load(), showDetail(id)])
  } finally {
    saving.value = false
  }
}
async function showDetail(id: number) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = undefined
  try {
    detail.value = await SnTraceApi.get(id)
  } finally {
    detailLoading.value = false
  }
}
async function openStation(sn: SnTrace) {
  selected.value = sn
  sources.value = []
  feedbacks.value = []
  feedbackId.value = undefined
  retryKey = ''
  retryPayload = ''
  sourceReady.value = false
  stationVisible.value = true
  sourcesLoading.value = true
  try {
    feedbacks.value = await SnTraceApi.feedbackOptions(sn.workOrderId)
    const preferred = Number(route.query.feedbackId)
    if (feedbacks.value.some((f) => f.id === preferred)) {
      feedbackId.value = preferred
      await loadSources()
    }
  } finally {
    sourcesLoading.value = false
  }
}
async function loadSources() {
  sources.value = []
  sourceReady.value = false
  if (!feedbackId.value) return
  const requested = feedbackId.value
  sourcesLoading.value = true
  try {
    const result = await SnTraceApi.consumeOptions(requested)
    if (requested === feedbackId.value) {
      sources.value = result.map((row) => ({ ...row, inputQuantity: '0' }))
      sourceReady.value = true
    }
  } finally {
    if (requested === feedbackId.value) sourcesLoading.value = false
  }
}
function allocateOne() {
  const allocation = distributeSnMaterials(sources.value)
  sources.value.forEach((row, index) => {
    row.inputQuantity = allocation.quantities[index]
  })
  if (!allocation.complete)
    message.warning('部分耗料行剩余份额不足或单件用量无法以六位小数精确表达，请刷新后核对')
}

async function submitStation() {
  if (
    !selected.value ||
    !feedbackId.value ||
    !sourceReady.value ||
    lineTotals.value.some((line) => !line.valid)
  )
    return
  const payload = {
    traceId: selected.value.id,
    feedbackId: feedbackId.value,
    materials: sources.value
      .filter((row) => (quantityUnits(row.inputQuantity) ?? 0n) > 0n)
      .map((row) => ({ consumeDetailId: row.consumeDetailId, quantity: row.inputQuantity }))
  }
  const fingerprint = JSON.stringify(payload)
  if (retryPayload !== fingerprint) {
    retryKey =
      globalThis.crypto?.randomUUID?.() || `sn-${Date.now()}-${Math.random().toString(36).slice(2)}`
    retryPayload = fingerprint
  }
  saving.value = true
  try {
    await SnTraceApi.station({ ...payload, requestKey: retryKey })
    stationVisible.value = false
    message.success('过站和用料快照已保存')
    await Promise.all([load(), showDetail(selected.value.id)])
  } finally {
    saving.value = false
  }
}
async function reverseLookup() {
  if (!reverseBatch.value.trim()) {
    message.warning('请输入原料批次号')
    return
  }
  reverseLoading.value = true
  try {
    affected.value = await SnTraceApi.affected({ batchCode: reverseBatch.value.trim() })
  } finally {
    reverseLoading.value = false
  }
}
onMounted(() => {
  const orderId = Number(route.query.workOrderId)
  if (Number.isSafeInteger(orderId) && orderId > 0) query.workOrderId = orderId
  load()
})
</script>

<style scoped>
.trace-heading,
.source-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.trace-heading h2 {
  margin: 0 0 8px;
}

.trace-heading p,
.hint {
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.invalid {
  color: var(--el-color-danger);
}

@media (width <= 700px) {
  .trace-heading,
  .source-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
