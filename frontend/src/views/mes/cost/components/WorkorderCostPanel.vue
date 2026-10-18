<template>
  <el-form inline @submit.prevent>
    <el-form-item label="生产工单"
      ><ProWorkOrderSelect v-model="selectedId" class="!w-320px" @change="load"
    /></el-form-item>
    <el-form-item
      ><el-button type="primary" :loading="loading" @click="load">查询核算</el-button></el-form-item
    >
  </el-form>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mb-16px" />
  <el-empty
    v-if="!cost && !loading"
    description="选择生产工单查看计划快照、实际耗料成本与费用记录"
  />
  <div v-loading="loading">
    <template v-if="cost">
      <div class="cost-toolbar">
        <h2
          >工单成本
          <el-tag :type="cost.status === 'SETTLED' ? 'success' : 'warning'">{{
            statusText
          }}</el-tag></h2
        >
        <div>
          <el-button
            v-if="!cost.planSnapshot"
            v-hasPermi="['mes:cost:update']"
            :loading="acting"
            @click="capturePlan"
            >冻结计划快照</el-button
          >
          <el-button
            v-if="cost.status !== 'SETTLED'"
            v-hasPermi="['mes:cost:update']"
            @click="openEffort"
            >记录实际工时与费用</el-button
          >
          <el-button
            v-if="cost.status !== 'SETTLED'"
            v-hasPermi="['mes:cost:settle']"
            type="primary"
            :loading="acting"
            @click="settle"
            >结算并锁定</el-button
          >
          <el-button @click="exportCostSnapshot(`工单${cost.workorderId}-成本`, cost)"
            >导出成本快照</el-button
          >
        </div>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="mb-16px"
        title="计划按工单确认时的 BOM、价格和费用快照核算。实际材料按实际消耗与来源库存成本核算；缺失任何依据时不生成完整成本。结算后锁定费用和结果。"
      />
      <el-alert
        v-if="cost.planSnapshot?.status === 'INCOMPLETE'"
        type="warning"
        :closable="false"
        show-icon
        class="mb-16px"
        title="这张工单的历史计划快照存在缺价或缺失定额。计划成本与差异保留待核定；实际成本按其自身完整证据独立核算，不回填或覆盖原计划。"
      />
      <el-alert
        v-for="issue in cost.issues"
        :key="issue"
        :title="issue"
        type="warning"
        :closable="false"
        show-icon
        class="mb-8px"
      />
      <el-descriptions :column="3" border class="my-16px">
        <el-descriptions-item label="计划数量">{{
          costNumber(cost.planQuantity)
        }}</el-descriptions-item>
        <el-descriptions-item label="合格产出数量">{{
          costNumber(cost.producedQuantity)
        }}</el-descriptions-item>
        <el-descriptions-item label="标准单位成本">{{
          money(cost.planSnapshot?.totalCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="计划材料金额">{{
          money(cost.planMaterialCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="计划人工金额">{{
          money(cost.planLaborCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="计划制造费用">{{
          money(cost.planOverheadCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="计划总成本">{{
          money(cost.planTotalCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际总成本">{{
          money(cost.actualTotalCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际单位成本">{{
          money(cost.actualUnitCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际材料金额">{{
          money(cost.actualMaterialCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际人工金额">{{
          money(cost.actualLaborCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际制造费用">{{
          money(cost.actualOverheadCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="总额差异（实－计）">{{
          money(cost.totalVariance)
        }}</el-descriptions-item>
        <el-descriptions-item label="单位差异（实－标）">{{
          money(cost.unitVariance)
        }}</el-descriptions-item>
        <el-descriptions-item label="币种 / 税口径">人民币 / 不含税</el-descriptions-item>
      </el-descriptions>
      <h3>实际工时和制造费用记录</h3>
      <el-table :data="cost.efforts" border empty-text="尚无费用记录；没有记录不代表费用为零">
        <el-table-column prop="id" label="记录号" width="90" />
        <el-table-column prop="processId" label="工序 ID" width="100" />
        <el-table-column label="工时（小时）" min-width="120"
          ><template #default="{ row }">{{ costNumber(row.hours) }}</template></el-table-column
        >
        <el-table-column label="人工费率 / 小时" min-width="140"
          ><template #default="{ row }">{{ money(row.hourlyRate) }}</template></el-table-column
        >
        <el-table-column label="制造费用金额" min-width="130"
          ><template #default="{ row }">{{ money(row.overheadAmount) }}</template></el-table-column
        >
        <el-table-column prop="remark" label="依据 / 说明" min-width="200" show-overflow-tooltip />
        <el-table-column
          prop="createTime"
          label="记录时间"
          min-width="180"
          :formatter="dateFormatter"
        />
        <el-table-column label="状态" width="100"
          ><template #default="{ row }"
            ><el-tag :type="row.voided ? 'info' : 'success'">{{
              row.voided ? '已撤销' : '有效'
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="voidReason" label="撤销原因" min-width="180" show-overflow-tooltip />
        <el-table-column
          prop="voidTime"
          label="撤销时间"
          min-width="180"
          :formatter="dateFormatter"
        />
        <el-table-column
          v-if="cost.status !== 'SETTLED' && checkPermi(['mes:cost:update'])"
          label="操作"
          width="90"
          fixed="right"
          ><template #default="{ row }"
            ><el-button
              v-if="!row.voided"
              link
              type="danger"
              :disabled="acting"
              @click="voidEffort(row)"
              >撤销</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <el-collapse class="mt-16px">
        <el-collapse-item title="查看冻结的计划核算依据" name="snapshot">
          <el-empty
            v-if="!cost.planSnapshot"
            description="未建立计划快照。已经发生生产或库存业务的历史工单不能事后伪造计划。"
          />
          <template v-else>
            <p>{{ cost.planSnapshot.itemName }} · {{ cost.planSnapshot.itemCode }}</p>
            <el-table :data="cost.planSnapshot.leaves" border size="small">
              <el-table-column prop="itemCode" label="采购物料编码" min-width="150" />
              <el-table-column prop="itemName" label="采购物料" min-width="160" />
              <el-table-column label="单位产品展开需求" min-width="150"
                ><template #default="{ row }">{{
                  costNumber(row.quantity)
                }}</template></el-table-column
              >
              <el-table-column label="冻结参考采购价" min-width="140"
                ><template #default="{ row }">{{
                  money(row.purchasePrice)
                }}</template></el-table-column
              >
              <el-table-column label="单位产品材料金额" min-width="150"
                ><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column
              >
            </el-table>
          </template>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
  <Dialog v-model="effortVisible" title="记录实际工时与制造费用" width="650px">
    <p>费用归属工单 ID：{{ effortWorkorderId }}</p>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="仅录入已实际发生的工时和费用。误录可在结算前填写原因撤销，原记录保留审计；请核对工序与依据，避免重复录入。"
      class="mb-16px"
    />
    <el-form ref="effortRef" :model="effort" :rules="effortRules" label-width="150px">
      <el-form-item label="工序" prop="processId"
        ><ProProcessSelect v-model="effort.processId" class="!w-full"
      /></el-form-item>
      <el-form-item label="实际工时（小时）" prop="hours"
        ><el-input v-model="effort.hours" inputmode="decimal" placeholder="最多 4 位小数"
      /></el-form-item>
      <el-form-item label="人工费率 / 小时" prop="hourlyRate"
        ><el-input v-model="effort.hourlyRate" inputmode="decimal" placeholder="最多 6 位小数"
      /></el-form-item>
      <el-form-item label="本次制造费用" prop="overheadAmount"
        ><el-input v-model="effort.overheadAmount" inputmode="decimal" placeholder="最多 6 位小数"
      /></el-form-item>
      <el-form-item label="核算依据" prop="remark"
        ><el-input
          v-model="effort.remark"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="effortSaving" @click="effortVisible = false">取消</el-button>
      <el-button type="primary" :loading="effortSaving" @click="saveEffort">保存实际记录</el-button>
    </template>
  </Dialog>
</template>
<script setup lang="ts">
import { CostApi, type WorkorderCost, type CostEffort } from '@/api/mes/cost'
import { ElMessageBox } from 'element-plus'
import { checkPermi } from '@/utils/permission'
import { dateFormatter } from '@/utils/formatTime'
import { generateUUID } from '@/utils'
import ProWorkOrderSelect from '@/views/mes/pro/workorder/components/ProWorkOrderSelect.vue'
import ProProcessSelect from '@/views/mes/pro/process/components/ProProcessSelect.vue'
import { costNumber, money, exportCostSnapshot, decimalRule } from '../format'

const props = defineProps<{ workorderId?: number }>()
const message = useMessage()
const selectedId = ref<number>()
const cost = ref<WorkorderCost>()
const loading = ref(false)
const acting = ref(false)
const error = ref('')
const effortVisible = ref(false)
const effortSaving = ref(false)
const effortRef = ref()
let effortWorkorderId: number | undefined
let effortRequestId: string | undefined
let effortAttempted = false
const effort = reactive<{
  processId?: number
  hours?: string
  hourlyRate?: string
  overheadAmount?: string
  remark: string
}>({ remark: '' })
const required = (message: string) => [{ required: true, message, trigger: 'change' }]
const effortRules = {
  processId: required('请选择实际执行的工序'),
  hours: [decimalRule(8, 4, true)],
  hourlyRate: [decimalRule(12, 6, true)],
  overheadAmount: [decimalRule(12, 6, true)],
  remark: [{ required: true, whitespace: true, message: '请填写工时和费用依据', trigger: 'blur' }]
}
const statusText = computed(
  () =>
    ({ INCOMPLETE: '依据未完整', PROVISIONAL: '暂估 · 未结算', SETTLED: '已结算并锁定' })[
      cost.value?.status ?? 'INCOMPLETE'
    ]
)
let loadVersion = 0
const load = async () => {
  const version = ++loadVersion
  const id = selectedId.value
  cost.value = undefined
  error.value = ''
  loading.value = false
  if (!id) return
  loading.value = true
  try {
    const result = await CostApi.getWorkorder(id)
    if (version === loadVersion) cost.value = result
  } catch {
    if (version === loadVersion) error.value = '工单成本加载失败，请重试。'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
const capturePlan = async () => {
  if (!cost.value || acting.value) return
  const id = cost.value.workorderId
  try {
    await message.confirm(
      '将当前有效 BOM、价格和费用冻结为该工单的计划依据。仅尚未发生生产与库存业务的工单允许补建，是否继续？'
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await CostApi.capturePlan(id)
    message.success('计划快照已冻结')
    await load()
  } catch {
    // The interceptor displays the server-side rejection.
  } finally {
    acting.value = false
  }
}
const settle = async () => {
  if (!cost.value || acting.value) return
  const id = cost.value.workorderId
  try {
    await message.confirm(
      '结算后将锁定该工单的实际成本和费用记录。系统会再次检查工单完成状态与成本完整性，是否结算？'
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await CostApi.settleWorkorder(id)
    message.success('工单已结算')
    await load()
  } catch {
    // No local status change is made when the server refuses settlement.
  } finally {
    acting.value = false
  }
}
const voidEffort = async (row: CostEffort) => {
  if (!row.id || row.voided || acting.value) return
  const id = row.id
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '撤销后该记录不再计入实际成本，原工时和撤销依据继续保留。请填写原因。',
      '撤销费用记录',
      {
        confirmButtonText: '确认撤销',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputValidator: (value) =>
          value && value.trim() && value.trim().length <= 500 ? true : '请填写 1～500 字的撤销原因'
      }
    )
    reason = result.value.trim()
  } catch {
    return
  }
  acting.value = true
  try {
    await CostApi.voidEffort({ id, reason })
    message.success('费用已撤销，原记录与原因已保留')
    await load()
  } catch {
    // Keep the row intact unless the server confirms the reversal.
  } finally {
    acting.value = false
  }
}
const openEffort = () => {
  if (effortAttempted && effortRequestId) {
    effortVisible.value = true
    return
  }
  effortWorkorderId = cost.value?.workorderId
  if (!effortWorkorderId) return
  effortRequestId = generateUUID()
  Object.assign(effort, {
    processId: undefined,
    hours: undefined,
    hourlyRate: undefined,
    overheadAmount: undefined,
    remark: ''
  })
  effortVisible.value = true
  nextTick(() => effortRef.value?.clearValidate())
}
const saveEffort = async () => {
  if (!effortWorkorderId || effortSaving.value) return
  const valid = await effortRef.value.validate().catch(() => false)
  if (!valid) return
  effortAttempted = true
  effortSaving.value = true
  try {
    await CostApi.addEffort({
      requestId: effortRequestId,
      workorderId: effortWorkorderId,
      processId: effort.processId,
      hours: effort.hours!,
      hourlyRate: effort.hourlyRate!,
      overheadAmount: effort.overheadAmount!,
      remark: effort.remark.trim()
    })
    message.success('实际工时与费用已记录')
    effortVisible.value = false
    effortRequestId = undefined
    effortAttempted = false
    await load()
  } catch {
    // Reopening this form reuses the same request ID after an uncertain response.
  } finally {
    effortSaving.value = false
  }
}
watch(
  () => props.workorderId,
  (id) => {
    selectedId.value = id
    load()
  },
  { immediate: true }
)
</script>
