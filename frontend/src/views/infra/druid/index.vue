<template>
  <ContentWrap v-loading="loading">
    <div class="flex items-center gap-12px mb-16px"
      ><el-button type="primary" @click="load">刷新指标</el-button
      ><span v-if="sampleTime">采样：{{ formatDate(sampleTime) }}</span></div
    >
    <el-empty v-if="!loading && !pools.length" description="尚无已初始化的 Druid 连接池" />
    <el-card v-for="pool in pools" :key="pool.id" class="mb-16px">
      <template #header
        ><div class="flex gap-12px items-center"
          >连接池 {{ pool.id }} · {{ pool.databaseType }}
          <el-tag :type="pool.enabled && !pool.closed ? 'success' : 'danger'">{{
            pool.closed ? '已关闭' : pool.enabled ? '运行中' : '已停用'
          }}</el-tag></div
        ></template
      >
      <el-descriptions border :column="3">
        <el-descriptions-item label="活跃 / 空闲"
          >{{ pool.active }} / {{ pool.idle }}</el-descriptions-item
        >
        <el-descriptions-item label="最大活跃 / 最小空闲"
          >{{ pool.maxActive }} / {{ pool.minIdle }}</el-descriptions-item
        >
        <el-descriptions-item label="活跃峰值">{{ pool.peakActive }}</el-descriptions-item>
        <el-descriptions-item label="等待线程 / 累计等待"
          >{{ pool.waitingThreads }} / {{ pool.waitCount }}</el-descriptions-item
        >
        <el-descriptions-item label="借用 / 归还"
          >{{ pool.borrowed }} / {{ pool.returned }}</el-descriptions-item
        >
        <el-descriptions-item label="创建 / 销毁"
          >{{ pool.created }} / {{ pool.destroyed }}</el-descriptions-item
        >
        <el-descriptions-item label="连接错误">{{ pool.errors }}</el-descriptions-item>
        <el-descriptions-item label="SQL 执行 / 错误"
          >{{ pool.sqlExecuteCount }} / {{ pool.sqlErrorCount }}</el-descriptions-item
        >
      </el-descriptions>
      <el-alert
        v-if="!pool.sqlStatisticsEnabled"
        title="当前连接池未启用 SQL 统计，连接状态仍为实时数据。"
        type="warning"
        :closable="false"
        class="mt-12px"
      />
      <p class="text-13px text-gray-500"
        >SQL 按累计耗时列出前 50 项，以指纹汇总，不展示 SQL 原文与参数。</p
      >
      <el-table :data="pool.sql">
        <el-table-column label="SQL 指纹" prop="digest" min-width="160" />
        <el-table-column label="操作" prop="operation" width="90" />
        <el-table-column label="次数" prop="executeCount" />
        <el-table-column label="错误" prop="errorCount" />
        <el-table-column label="累计 ms" prop="totalMillis" />
        <el-table-column label="最大 ms" prop="maxMillis" />
        <el-table-column label="读取行数" prop="fetchedRows" />
        <el-table-column label="更新行数" prop="updatedRows" />
      </el-table>
    </el-card>
  </ContentWrap>
</template>
<script setup lang="ts">
import { MonitorApi, type DatabasePool } from '@/api/infra/monitor'
import { formatDate } from '@/utils/formatTime'
defineOptions({ name: 'InfraDruid' })
const loading = ref(false),
  sampleTime = ref(0),
  pools = ref<DatabasePool[]>([])
const load = async () => {
  loading.value = true
  try {
    const data = await MonitorApi.database()
    pools.value = data.pools
    sampleTime.value = data.sampleTime
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
