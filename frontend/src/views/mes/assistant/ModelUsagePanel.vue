<template>
  <div v-if="canConfig" v-loading="loading" class="model-usage-panel">
    <div class="usage-toolbar"
      ><el-button type="primary" @click="policyDialog?.open()">设置预算与价格</el-button
      ><el-button :disabled="loading" @click="refresh">刷新用量</el-button></div
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert
      v-if="summary && (!summary.policyConfigured || !summary.policyEnabled)"
      title="预算尚未配置或未启用，外部模型调用暂停；文档检索与规则查询仍可使用。"
      type="warning"
      :closable="false"
      show-icon
    />
    <p
      >统计时区：Asia/Shanghai，Token配额仅覆盖聊天模型。问答、Jev与连接测试共享次数及频率限制；未知用量保留预留额度。修改模型或预算不会重置用量。</p
    >
    <p v-if="summary"
      >本次统计：{{ summary.generatedAt }}；计量起点：{{
        summary.coverageStart || '尚未启用计量'
      }}。启用前历史用量未知，金额为配置价格估算。</p
    >
    <el-tabs v-if="summary" v-model="subjectTab">
      <el-tab-pane
        v-for="subject in [summary.tenant, summary.user]"
        :key="subject.subjectType"
        :name="subject.subjectType"
        :label="subject.subjectType === 'TENANT' ? '当前租户' : `当前用户（${subject.subjectId}）`"
      >
        <p
          >当前分钟：{{ subject.minute.admitted }} / {{ subject.minute.limit }} 次，剩余
          {{ subject.minute.remaining }}；当前并发：{{ subject.live.active }} /
          {{ subject.live.limit }}，其中过期待核定 {{ subject.live.staleReserved }}。</p
        >
        <UsagePeriodSummary title="本日" :period="subject.day" />
        <UsagePeriodSummary title="本月" :period="subject.month" />
      </el-tab-pane>
    </el-tabs>
    <h3>调用审计</h3>
    <el-form :inline="true" @submit.prevent="query">
      <el-form-item label="开始日"
        ><el-date-picker v-model="filters.from" value-format="YYYY-MM-DD" type="date" clearable
      /></el-form-item>
      <el-form-item label="结束日（不含）"
        ><el-date-picker v-model="filters.to" value-format="YYYY-MM-DD" type="date" clearable
      /></el-form-item>
      <el-form-item label="用途"
        ><el-select v-model="filters.purpose" clearable placeholder="全部用途"
          ><el-option
            v-for="(name, key) in purposeNames"
            :key="key"
            :label="name"
            :value="key" /></el-select
      ></el-form-item>
      <el-form-item label="状态"
        ><el-select v-model="filters.status" clearable placeholder="全部状态"
          ><el-option
            v-for="(name, key) in statusNames"
            :key="key"
            :label="name"
            :value="key" /></el-select
      ></el-form-item>
      <el-form-item label="用户ID"
        ><el-input v-model="filters.userId" inputmode="numeric" maxlength="19" clearable
      /></el-form-item>
      <el-form-item
        ><el-button type="primary" :disabled="loading" @click="query">查询</el-button
        ><el-button @click="resetFilters">重置</el-button></el-form-item
      >
    </el-form>
    <el-table :data="rows" border row-key="id">
      <el-table-column prop="createdAt" label="创建时间" min-width="195" />
      <el-table-column prop="userId" label="用户" width="80" />
      <el-table-column label="用途" min-width="130"
        ><template #default="{ row }">{{
          purposeNames[row.purpose] || row.purpose
        }}</template></el-table-column
      >
      <el-table-column label="服务 / 模型" min-width="180"
        ><template #default="{ row }"
          >{{ row.provider }} / {{ row.model }}</template
        ></el-table-column
      >
      <el-table-column label="状态" min-width="130"
        ><template #default="{ row }"
          >{{ statusNames[row.status] || row.status }}<br /><small>{{
            row.safeErrorKind
          }}</small></template
        ></el-table-column
      >
      <el-table-column label="服务原报Token" min-width="125"
        ><template #default="{ row }">{{
          measured(row.actualTotalTokens)
        }}</template></el-table-column
      >
      <el-table-column label="聊天额度计入Token" min-width="135"
        ><template #default="{ row }">{{
          row.channel === 'CHAT' ? measured(row.accountedTokens) : '不适用'
        }}</template></el-table-column
      >
      <el-table-column label="计入金额（¥）" min-width="125"
        ><template #default="{ row }">{{
          measured(row.accountedAmount)
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="125" fixed="right"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="detail = row">详情</el-button
          ><el-button
            v-if="row.canReconcile"
            link
            type="warning"
            @click="reconcileDialog?.open(row)"
            >核定</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-pagination
      v-model:current-page="pageNo"
      v-model:page-size="pageSize"
      :page-sizes="[20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @current-change="changePage"
      @size-change="changeSize"
    />
    <UsagePolicyDialog ref="policyDialog" @saved="afterChange" />
    <UsageReconcileDialog ref="reconcileDialog" @saved="afterChange" />
    <el-dialog
      :model-value="!!detail"
      title="调用用量明细"
      width="760px"
      @update:model-value="detail = undefined"
    >
      <template v-if="detail"
        ><el-descriptions :column="1" border>
          <el-descriptions-item label="调用ID / 版本"
            >{{ detail.id }} / {{ detail.version }}</el-descriptions-item
          >
          <el-descriptions-item label="请求 / 实际模型"
            >{{ detail.model }} / {{ detail.servedModel || '未知' }}</el-descriptions-item
          >
          <el-descriptions-item label="原报输入 / 输出 / 合计Token"
            >{{ measured(detail.actualInputTokens) }} / {{ measured(detail.actualOutputTokens) }} /
            {{ measured(detail.actualTotalTokens) }}</el-descriptions-item
          >
          <el-descriptions-item label="Token预留 / 未知占用"
            >{{ measured(detail.reservedTokens) }} /
            {{ measured(detail.unknownHeldTokens) }}</el-descriptions-item
          >
          <el-descriptions-item label="金额预留 / 未知占用"
            >{{ measured(detail.reservedAmount) }} /
            {{ measured(detail.unknownHeldAmount) }}</el-descriptions-item
          >
          <el-descriptions-item label="配置价格估算金额">{{
            measured(detail.calculatedAmount)
          }}</el-descriptions-item>
          <el-descriptions-item label="已核定输入 / 输出Token"
            >{{ measured(detail.confirmedInputTokens) }} /
            {{ measured(detail.confirmedOutputTokens) }}</el-descriptions-item
          >
          <el-descriptions-item label="已核定金额">{{
            measured(detail.confirmedAmount)
          }}</el-descriptions-item>
          <el-descriptions-item label="用量依据 / 结果"
            >{{ detail.usageBasis }} / {{ detail.outcome || '尚未完成' }}</el-descriptions-item
          >
          <el-descriptions-item label="发送 / 结算时间"
            >{{ detail.dispatchedAt || '未知' }} /
            {{ detail.settledAt || '尚未结算' }}</el-descriptions-item
          >
          <el-descriptions-item label="安全错误类型 / HTTP状态"
            >{{ detail.safeErrorKind || '无' }} /
            {{ detail.httpStatus ?? '未知' }}</el-descriptions-item
          >
          <el-descriptions-item label="时长（毫秒）">{{
            measured(detail.durationMs)
          }}</el-descriptions-item>
          <el-descriptions-item label="最近核定依据">{{
            detail.lastReconciliationReason || '无'
          }}</el-descriptions-item>
          <el-descriptions-item label="核定人 / 时间"
            >{{ detail.lastReconciledBy ?? '无' }} /
            {{ detail.lastReconciledAt || '无' }}</el-descriptions-item
          >
          <el-descriptions-item label="计量策略版本">{{
            detail.policyVersion ?? '未配置'
          }}</el-descriptions-item>
          <el-descriptions-item label="价格快照"
            ><template v-if="detail.priceSnapshot"
              >{{ detail.priceSnapshot.channel }} / {{ detail.priceSnapshot.provider }} /
              {{ detail.priceSnapshot.model }}<br />输入／百万：{{
                measured(detail.priceSnapshot.inputPerMillion)
              }}，输出／百万：{{ measured(detail.priceSnapshot.outputPerMillion) }}，单次预留：{{
                measured(detail.priceSnapshot.perCallReservationAmount)
              }}（CNY）</template
            ><span v-else>未定价</span></el-descriptions-item
          >
        </el-descriptions></template
      >
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { AssistantApi } from '@/api/mes/assistant'
import type { UsageRow, UsageSummary } from '@/api/mes/assistant/usage'
import { checkPermi } from '@/utils/permission'
import UsagePolicyDialog from './UsagePolicyDialog.vue'
import UsageReconcileDialog from './UsageReconcileDialog.vue'
import UsagePeriodSummary from './UsagePeriodSummary.vue'
import { measured, pageRequest, purposeNames, statusNames } from './usagePolicy'
const emit = defineEmits<{ changed: [] }>()
const canConfig = computed(() => checkPermi(['mes:assistant:config']))
const loading = ref(false),
  error = ref(''),
  summary = ref<UsageSummary>(),
  rows = ref<UsageRow[]>([]),
  total = ref(0)
const pageNo = ref(1),
  pageSize = ref(20),
  subjectTab = ref('TENANT'),
  detail = ref<UsageRow>()
const filters = reactive({ from: '', to: '', purpose: '', status: '', userId: '' })
const policyDialog = ref<InstanceType<typeof UsagePolicyDialog>>(),
  reconcileDialog = ref<InstanceType<typeof UsageReconcileDialog>>()
let epoch = 0
function clear() {
  epoch++
  loading.value = false
  rows.value = []
  total.value = 0
  summary.value = undefined
  detail.value = undefined
  policyDialog.value?.close()
  reconcileDialog.value?.close()
}
async function refresh() {
  if (!canConfig.value) {
    clear()
    return
  }
  const token = ++epoch
  rows.value = []
  total.value = 0
  detail.value = undefined
  error.value = ''
  try {
    const params = pageRequest({ ...filters }, pageNo.value, pageSize.value)
    loading.value = true
    const [current, page] = await Promise.all([
      AssistantApi.usageSummary(),
      AssistantApi.usagePage(params)
    ])
    if (token !== epoch || !canConfig.value) return
    summary.value = current
    rows.value = page.list
    total.value = page.total
  } catch (e) {
    if (token === epoch && canConfig.value) {
      summary.value = undefined
      error.value = e instanceof Error ? e.message : '用量加载失败'
    }
  } finally {
    if (token === epoch) loading.value = false
  }
}
async function query() {
  pageNo.value = 1
  await refresh()
}
function resetFilters() {
  Object.assign(filters, { from: '', to: '', purpose: '', status: '', userId: '' })
  pageNo.value = 1
  void refresh()
}
function changePage(value: number) {
  pageNo.value = value
  void refresh()
}
function changeSize(value: number) {
  pageSize.value = value
  pageNo.value = 1
  void refresh()
}
function afterChange() {
  emit('changed')
  void refresh()
}
watch(
  filters,
  () => {
    epoch++
    rows.value = []
    total.value = 0
    detail.value = undefined
    loading.value = false
    pageNo.value = 1
  },
  { flush: 'sync' }
)
watch(canConfig, (allowed) => {
  if (!allowed) {
    clear()
    error.value = ''
  }
})
onMounted(refresh)
onBeforeUnmount(clear)
</script>

<style scoped>
.usage-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.el-pagination {
  margin-top: 16px;
}

.el-select {
  min-width: 160px;
}

p {
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
</style>
