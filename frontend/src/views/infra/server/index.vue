<template>
  <ContentWrap v-loading="loading">
    <div class="flex items-center gap-12px mb-16px flex-wrap">
      <el-button type="primary" @click="load">刷新指标</el-button>
      <span v-if="snapshot">采样：{{ formatDate(snapshot.sampleTime) }}</span>
    </div>
    <template v-if="snapshot">
      <div class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-12px mb-16px">
        <el-card
          ><div>系统 CPU</div><strong>{{ percent(snapshot.systemCpuLoad) }}</strong
          ><div
            >JVM CPU {{ percent(snapshot.processCpuLoad) }} · {{ snapshot.processors }} 核</div
          ></el-card
        >
        <el-card
          ><div>JVM 堆内存</div
          ><strong>{{ bytes(snapshot.heapUsed) }} / {{ bytes(snapshot.heapMax) }}</strong
          ><div>已提交 {{ bytes(snapshot.heapCommitted) }}</div></el-card
        >
        <el-card
          ><div>物理内存</div
          ><strong>{{
            bytes((snapshot.physicalMemoryTotal || 0) - (snapshot.physicalMemoryFree || 0))
          }}</strong
          ><div>总量 {{ bytes(snapshot.physicalMemoryTotal) }}</div></el-card
        >
        <el-card
          ><div>运行时长</div><strong>{{ duration(snapshot.uptimeMillis) }}</strong
          ><div>启动 {{ formatDate(snapshot.startedTime) }}</div></el-card
        >
      </div>
      <el-descriptions title="运行环境" border :column="2">
        <el-descriptions-item label="JVM">{{ snapshot.jvmName }}</el-descriptions-item>
        <el-descriptions-item label="Java">{{ snapshot.javaVersion }}</el-descriptions-item>
        <el-descriptions-item label="系统"
          >{{ snapshot.osName }} / {{ snapshot.osArch }}</el-descriptions-item
        >
        <el-descriptions-item label="线程"
          >当前 {{ snapshot.liveThreads }} / 峰值 {{ snapshot.peakThreads }}</el-descriptions-item
        >
        <el-descriptions-item label="非堆内存">{{
          bytes(snapshot.nonHeapUsed)
        }}</el-descriptions-item>
        <el-descriptions-item label="GC"
          >{{ snapshot.gcCount }} 次 / {{ snapshot.gcMillis }} ms</el-descriptions-item
        >
        <el-descriptions-item label="已加载类">{{ snapshot.loadedClasses }}</el-descriptions-item>
      </el-descriptions>
      <h3 class="mt-20px">磁盘容量</h3>
      <el-table :data="snapshot.disks">
        <el-table-column label="磁盘" prop="root" />
        <el-table-column label="总容量"
          ><template #default="{ row }">{{ bytes(row.total) }}</template></el-table-column
        >
        <el-table-column label="可用容量"
          ><template #default="{ row }">{{ bytes(row.usable) }}</template></el-table-column
        >
        <el-table-column label="使用率"
          ><template #default="{ row }">{{
            percent(1 - row.usable / row.total)
          }}</template></el-table-column
        >
      </el-table>
    </template>
  </ContentWrap>
</template>
<script setup lang="ts">
import { MonitorApi, type ServerSnapshot } from '@/api/infra/monitor'
import { formatDate } from '@/utils/formatTime'
defineOptions({ name: 'InfraAdminServer' })
const snapshot = ref<ServerSnapshot>(),
  loading = ref(false)
const bytes = (n?: number) =>
  n == null || n < 0 ? '暂不可用' : `${(n / 1024 / 1024 / 1024).toFixed(2)} GiB`
const percent = (n?: number) => (n == null || n < 0 ? '采样中' : `${(n * 100).toFixed(1)}%`)
const duration = (ms: number) =>
  `${Math.floor(ms / 86400000)}天 ${Math.floor(ms / 3600000) % 24}小时 ${Math.floor(ms / 60000) % 60}分`
const load = async () => {
  loading.value = true
  try {
    snapshot.value = await MonitorApi.server()
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
