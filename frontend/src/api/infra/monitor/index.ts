import request from '@/config/axios'
export interface ServerSnapshot {
  sampleTime: number
  startedTime: number
  uptimeMillis: number
  javaVersion: string
  jvmName: string
  osName: string
  osArch: string
  processors: number
  systemCpuLoad?: number
  processCpuLoad?: number
  heapUsed: number
  heapCommitted: number
  heapMax: number
  nonHeapUsed: number
  physicalMemoryTotal?: number
  physicalMemoryFree?: number
  liveThreads: number
  peakThreads: number
  loadedClasses: number
  gcCount: number
  gcMillis: number
  disks: { root: string; total: number; usable: number }[]
}
export interface SqlSummary {
  id: number
  digest: string
  operation: string
  executeCount: number
  errorCount: number
  totalMillis: number
  maxMillis: number
  fetchedRows: number
  updatedRows: number
}
export interface DatabasePool {
  id: number
  databaseType: string
  enabled: boolean
  closed: boolean
  sqlStatisticsEnabled: boolean
  active: number
  idle: number
  maxActive: number
  minIdle: number
  peakActive: number
  waitingThreads: number
  waitCount: number
  borrowed: number
  returned: number
  created: number
  destroyed: number
  errors: number
  sqlExecuteCount: number
  sqlErrorCount: number
  sql: SqlSummary[]
}
export const MonitorApi = {
  server: (): Promise<ServerSnapshot> => request.get({ url: '/infra/monitor/server' }),
  database: (): Promise<{ sampleTime: number; pools: DatabasePool[] }> =>
    request.get({ url: '/infra/monitor/database' })
}
