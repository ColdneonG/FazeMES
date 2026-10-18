<script setup lang="ts">
import { computed } from 'vue'
import type { ReportBlock, ReportPreview } from '@/api/mes/report'
import { formatReportValue, metricValue, resultColumn } from '../designerModel'
import ReportChart from './ReportChart.vue'
const props = defineProps<{ block: ReportBlock; result?: ReportPreview }>()
const rows = computed(() => props.result?.rows.slice(0, props.block.binding.rowLimit) || [])
const totalColumnWidth = computed(
  () => props.result?.columns.reduce((total, column) => total + column.width, 0) || 1
)
const metricLabel = computed(
  () => resultColumn(props.result, props.block.binding.metrics[0])?.title || '未绑定数值列'
)
</script>

<template>
  <div
    class="block-content"
    :class="[`content-${block.type}`, { 'content-bordered': block.style.border }]"
    :style="{
      color: block.style.color,
      backgroundColor: block.style.background,
      textAlign: block.style.align,
      fontSize: `${block.style.fontSize}px`,
      fontWeight: block.style.bold ? '700' : '400'
    }"
  >
    <template v-if="block.type === 'text'"
      ><div class="static-text">{{ block.text }}</div></template
    >
    <template v-else-if="block.type === 'divider'"
      ><div class="content-divider" :style="{ borderColor: block.style.color }"></div
    ></template>
    <template v-else>
      <div v-if="block.title" class="content-heading">{{ block.title }}</div>
      <template v-if="block.type === 'table'">
        <div class="table-viewport">
          <table v-if="result?.columns.length" class="data-table">
            <colgroup
              ><col
                v-for="column in result.columns"
                :key="column.key"
                :style="{ width: `${(column.width / totalColumnWidth) * 100}%` }"
            /></colgroup>
            <thead
              ><tr
                ><th v-for="column in result.columns" :key="column.key">{{ column.title }}</th></tr
              ></thead
            >
            <tbody
              ><tr v-for="(row, index) in rows" :key="index"
                ><td v-for="column in result.columns" :key="column.key">{{
                  formatReportValue(row[column.key])
                }}</td></tr
              ></tbody
            >
          </table>
          <div v-if="!rows.length" class="content-empty">{{
            result ? '当前条件下无业务数据' : '刷新真实数据后显示明细'
          }}</div>
        </div>
        <small v-if="rows.length" class="data-footnote"
          >当前页前 {{ rows.length }} 行 / 当前页 {{ result?.rows.length }} 行</small
        >
      </template>
      <template v-else-if="block.type === 'metric'">
        <strong class="metric-value">{{ metricValue(block, result) }}</strong>
        <small class="data-footnote">{{ metricLabel }} · 当前页 {{ block.binding.summary }}</small>
      </template>
      <template v-else
        ><div class="chart-body"><ReportChart :block="block" :result="result" /></div
        ><small class="data-footnote"
          >当前页前 {{ Math.min(result?.rows.length || 0, block.binding.rowLimit) }} 行</small
        ></template
      >
    </template>
  </div>
</template>

<style scoped>
.block-content {
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;
  box-sizing: border-box;
  flex-direction: column;
}

.content-bordered {
  border: 1px solid #dce5eb;
  border-radius: 3px;
}

.static-text {
  padding: 6px 8px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.content-heading {
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
}

.table-viewport {
  min-height: 0;
  overflow: hidden;
  flex: 1;
}

.data-table {
  width: 100%;
  font-size: inherit;
  border-collapse: collapse;
  table-layout: fixed;
}

.data-table th,
.data-table td {
  padding: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid #e2eaf0;
}

.data-table th {
  font-weight: 600;
  color: #355268;
  background: #edf4f7;
}

.data-table tbody tr:nth-child(even) {
  background: #f8fafc;
}

.content-empty {
  display: grid;
  min-height: 65px;
  font-size: 12px;
  color: #81929f;
  place-items: center;
}

.data-footnote {
  display: block;
  padding: 5px 12px;
  font-size: 9px;
  font-weight: 400;
  line-height: 1.4;
  color: #81929f;
}

.metric-value {
  padding: 2px 12px 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.1;
  color: #147d91;
}

.chart-body {
  min-height: 0;
  flex: 1;
}

.content-divider {
  margin: auto 0;
  border-top: 1px solid;
}
</style>
