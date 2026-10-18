<template>
  <el-button type="warning" plain @click="open">库存阈值预警</el-button>
  <Dialog v-model="visible" title="库存阈值预警" width="1000px">
    <el-alert title="按已启用物料的实物仓库账面库存汇总（含冻结库存，不含线边库）；上限为0时不启用上限预警。" type="info" :closable="false" class="mb-12px" />
    <el-form inline @submit.prevent="load">
      <el-form-item label="物料"><el-input v-model="keyword" clearable placeholder="编码或名称" @keyup.enter="load" /></el-form-item>
      <el-form-item label="预警"><el-select v-model="level" clearable class="!w-150px"><el-option label="库存不足" value="LOW" /><el-option label="超出上限" value="HIGH" /></el-select></el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
    </el-form>
    <el-table :data="rows" v-loading="loading" max-height="500">
      <el-table-column prop="code" label="物料编码" />
      <el-table-column prop="name" label="物料名称" />
      <el-table-column prop="stockQuantity" label="当前库存" />
      <el-table-column prop="minStock" label="下限" />
      <el-table-column prop="maxStock" label="上限（0不启用）" />
      <el-table-column label="预警"><template #default="scope"><el-tag :type="scope.row.level === 'LOW' ? 'danger' : 'warning'">{{ scope.row.level === 'LOW' ? '库存不足' : '超出上限' }}</el-tag></template></el-table-column>
      <el-table-column prop="difference" label="与阈值差量" />
    </el-table>
  </Dialog>
</template>
<script setup lang="ts">
import request from '@/config/axios'
const visible = ref(false)
const loading = ref(false)
const keyword = ref('')
const level = ref('')
const rows = ref<any[]>([])
const load = async () => {
  loading.value = true
  rows.value = []
  try { rows.value = await request.get({ url: '/mes/md/item/stock-warning', params: { keyword: keyword.value, level: level.value } }) }
  finally { loading.value = false }
}
const open = () => { visible.value = true; load() }
</script>
