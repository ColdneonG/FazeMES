<template>
  <el-button v-if="canView" type="warning" plain @click="open">质量同期比较与预警</el-button>
  <Dialog v-model="visible" title="质量同期比较与预警" width="1150px">
    <el-alert title="按检测日期统计已完成的IQC/IPQC/OQC检验单；异常率为未通过单数÷已完成单数，避免混加不同物料单位。本期或对比期无单据时不推断为零异常率。" type="info" :closable="false" class="mb-12px" />
    <el-form inline @submit.prevent="load">
      <el-form-item label="本期"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" class="!w-250px" :clearable="false" /></el-form-item>
      <el-form-item label="对比"><el-select v-model="compareMode" class="!w-150px"><el-option label="去年同期" value="YEAR_AGO" /><el-option label="上一等长周期" value="PREVIOUS" /></el-select></el-form-item>
      <el-form-item label="异常率上限%"><el-input-number v-model="rateThreshold" :min="0" :max="100" :precision="2" class="!w-110px" /></el-form-item>
      <el-form-item label="上升上限(百分点)"><el-input-number v-model="increaseThreshold" :min="0" :max="100" :precision="2" class="!w-110px" /></el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
    </el-form>
    <p v-if="result">本期：{{ result.startDate }} 至 {{ result.endDate }}；对比期：{{ result.comparisonStartDate }} 至 {{ result.comparisonEndDate }}。超过所设上限时预警。</p>
    <el-table :data="result?.rows || []" v-loading="loading">
      <el-table-column prop="stage" label="阶段" width="80" />
      <el-table-column prop="currentTotal" label="本期单数" width="95" />
      <el-table-column prop="currentFailed" label="本期未通过" width="100" />
      <el-table-column label="本期异常率"><template #default="scope">{{ rate(scope.row.currentRate) }}</template></el-table-column>
      <el-table-column prop="comparisonTotal" label="对比单数" width="95" />
      <el-table-column label="对比异常率"><template #default="scope">{{ rate(scope.row.comparisonRate) }}</template></el-table-column>
      <el-table-column label="变化(百分点)"><template #default="scope">{{ scope.row.changePoints == null ? '—' : Number(scope.row.changePoints).toFixed(2) }}</template></el-table-column>
      <el-table-column label="预警" min-width="240"><template #default="scope"><el-tag :type="scope.row.alert ? 'danger' : 'info'">{{ scope.row.message }}</el-tag></template></el-table-column>
    </el-table>
  </Dialog>
</template>
<script setup lang="ts">
import dayjs from 'dayjs'
import request from '@/config/axios'
import { checkPermi } from '@/utils/permission'
const canView = computed(() => ['mes:qc-iqc:query', 'mes:qc-ipqc:query', 'mes:qc-oqc:query'].every((permission) => checkPermi([permission])))
const visible = ref(false)
const loading = ref(false)
const dates = ref<[string, string]>([dayjs().startOf('month').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
const compareMode = ref('YEAR_AGO')
const rateThreshold = ref(5)
const increaseThreshold = ref(2)
const result = ref<any>()
const rate = (value?: number) => value == null ? '—' : Number(value).toFixed(2) + '%'
const load = async () => {
  if (!dates.value?.[0] || !dates.value?.[1]) return
  loading.value = true
  result.value = undefined
  try {
    result.value = await request.get({ url: '/mes/qc/analysis/comparison', params: {
      startDate: dates.value[0], endDate: dates.value[1], compareMode: compareMode.value,
      rateThreshold: rateThreshold.value, increaseThreshold: increaseThreshold.value
    } })
  } finally { loading.value = false }
}
const open = () => { visible.value = true; load() }
</script>
