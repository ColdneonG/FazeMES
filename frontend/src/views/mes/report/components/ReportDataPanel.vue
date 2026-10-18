<script setup lang="ts">
import { computed } from 'vue'
import draggable from 'vuedraggable'
import type { ReportConfig, ReportDataset } from '@/api/mes/report'
const config = defineModel<ReportConfig>({ required: true })
const props = defineProps<{ datasets: ReportDataset[] }>()
const fields = computed(
  () => props.datasets.find((dataset) => dataset.key === config.value.dataset)?.fields || []
)
const title = (key: string) => fields.value.find((field) => field.key === key)?.title || key
const type = (key: string) => fields.value.find((field) => field.key === key)?.type
const add = (field: ReportDataset['fields'][number]) =>
  config.value.columns.push({ field: field.key, title: field.title, visible: true, width: 160 })
const resetDataset = () => {
  config.value.columns = fields.value
    .slice(0, 6)
    .map((field) => ({ field: field.key, title: field.title, visible: true, width: 160 }))
  config.value.filters = []
  config.value.groupBy = []
  config.value.sortField = undefined
  config.value.canvas?.blocks.forEach((block) => {
    block.binding.dimension = undefined
    block.binding.metrics = []
  })
}
const operators = [
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'ne' },
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'ge' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'le' },
  { label: '包含（文本）', value: 'contains' }
]
</script>

<template>
  <div class="data-panel">
    <el-alert
      title="数据来自业务白名单。筛选以 AND 连接；使用聚合时，其他列均需分组。画布组件共用此查询。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-form label-position="top" class="dataset-form"
      ><el-form-item label="业务数据集"
        ><el-select v-model="config.dataset" @change="resetDataset"
          ><el-option
            v-for="dataset in datasets"
            :key="dataset.key"
            :label="dataset.name"
            :value="dataset.key" /></el-select></el-form-item
    ></el-form>
    <h3
      >字段与列顺序 <small>{{ config.columns.length }} / 30</small></h3
    >
    <div class="field-buttons"
      ><el-button
        v-for="field in fields"
        :key="field.key"
        size="small"
        :disabled="config.columns.some((column) => column.field === field.key)"
        @click="add(field)"
        >＋ {{ field.title }}</el-button
      ></div
    >
    <div class="columns-scroll">
      <div class="column-head"
        ><span>字段（拖动排序）</span><span>显示标题</span><span>列宽</span><span>聚合</span
        ><span>显示</span><span></span
      ></div>
      <draggable v-model="config.columns" item-key="field" handle=".column-grip" :animation="150"
        ><template #item="{ element, index }"
          ><div class="column-row"
            ><span class="column-grip">⠿ {{ title(element.field) }}</span
            ><el-input v-model="element.title" maxlength="100" size="small" /><el-input-number
              v-model="element.width"
              :min="60"
              :max="600"
              :controls="false"
              size="small"
            /><el-select v-model="element.aggregate" clearable placeholder="明细" size="small"
              ><el-option
                v-for="aggregate in type(element.field) === 'n'
                  ? ['SUM', 'AVG', 'MIN', 'MAX', 'COUNT']
                  : ['COUNT']"
                :key="aggregate"
                :value="aggregate"
                :label="aggregate" /></el-select
            ><el-switch v-model="element.visible" size="small" /><el-button
              link
              type="danger"
              @click="config.columns.splice(index, 1)"
              >移除</el-button
            ></div
          ></template
        ></draggable
      >
    </div>
    <el-form label-position="top" class="query-settings">
      <el-form-item label="分组维度（最多5项）"
        ><el-select v-model="config.groupBy" multiple clearable :multiple-limit="5"
          ><el-option
            v-for="field in fields"
            :key="field.key"
            :label="field.title"
            :value="field.key" /></el-select
      ></el-form-item>
      <el-form-item label="排序字段"
        ><el-select v-model="config.sortField" clearable
          ><el-option
            v-for="column in config.columns.filter((item) => item.visible !== false)"
            :key="column.field"
            :label="column.title"
            :value="column.field" /></el-select
      ></el-form-item>
      <el-form-item label="排序方向"
        ><el-select v-model="config.sortDirection"
          ><el-option label="升序" value="ASC" /><el-option label="降序" value="DESC" /></el-select
      ></el-form-item>
    </el-form>
    <h3
      >筛选条件
      <el-button
        link
        type="primary"
        :disabled="config.filters.length >= 20 || !fields.length"
        @click="config.filters.push({ field: fields[0].key, operator: 'eq', value: '' })"
        >＋ 添加</el-button
      ></h3
    >
    <div v-for="(filter, index) in config.filters" :key="index" class="filter-row"
      ><el-select v-model="filter.field"
        ><el-option
          v-for="field in fields"
          :key="field.key"
          :value="field.key"
          :label="field.title" /></el-select
      ><el-select v-model="filter.operator"
        ><el-option
          v-for="operator in operators"
          :key="operator.value"
          :value="operator.value"
          :label="operator.label" /></el-select
      ><el-input
        v-model="filter.value"
        :placeholder="type(filter.field) === 'd' ? '2026-10-18 00:00:00' : '筛选值'"
        maxlength="500"
      /><el-button link type="danger" @click="config.filters.splice(index, 1)">删除</el-button></div
    >
    <p class="data-note"
      >预览每页100行；聚合由服务端在分页前执行。Excel导出完整查询结果，最多5000行，超出需收窄筛选。列顺序与画布中表格一致。</p
    >
  </div>
</template>

<style scoped>
.data-panel {
  color: var(--el-text-color-primary);
}

.dataset-form {
  margin-top: 20px;
}

h3 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 22px 0 12px;
  font-size: 14px;
}

h3 small {
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.field-buttons {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.field-buttons .el-button {
  margin: 0;
}

.columns-scroll {
  overflow-x: auto;
}

.column-head,
.column-row {
  display: grid;
  min-width: 650px;
  padding: 9px 0;
  grid-template-columns: minmax(110px, 1fr) minmax(130px, 1.1fr) 80px 100px 40px 40px;
  gap: 10px;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-light);
}

.column-head {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.column-grip {
  font-size: 12px;
  cursor: grab;
}

.column-row .el-input-number {
  width: 80px;
}

.query-settings {
  display: grid;
  margin-top: 20px;
  grid-template-columns: 2fr 1fr 100px;
  gap: 12px;
}

.filter-row {
  display: grid;
  margin: 10px 0;
  grid-template-columns: 150px 140px minmax(150px, 1fr) 40px;
  gap: 10px;
}

.data-note {
  margin-top: 22px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}
</style>
