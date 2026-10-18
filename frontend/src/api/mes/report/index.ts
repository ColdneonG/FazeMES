import request from '@/config/axios'

export interface ReportColumn {
  field: string
  title: string
  width: number
  visible: boolean
  aggregate?: string
}
export interface ReportConfig {
  dataset: string
  chart: string
  columns: ReportColumn[]
  filters: { field: string; operator: string; value: string }[]
  groupBy: string[]
  sortField?: string
  sortDirection: string
  canvas?: ReportCanvas
}
export type ReportBlockType = 'text' | 'table' | 'bar' | 'line' | 'pie' | 'metric' | 'divider'
export interface ReportBlock {
  id: string
  type: ReportBlockType
  x: number
  y: number
  width: number
  height: number
  title: string
  text: string
  style: {
    fontSize: number
    color: string
    background: string
    align: 'left' | 'center' | 'right'
    bold: boolean
    border: boolean
  }
  binding: {
    dimension?: string
    metrics: string[]
    summary: 'SUM' | 'AVG' | 'MIN' | 'MAX' | 'COUNT'
    rowLimit: number
  }
}
export interface ReportCanvas {
  schemaVersion: 1
  paper: 'A4'
  orientation: 'portrait' | 'landscape'
  background: string
  blocks: ReportBlock[]
}
export interface ReportDefinition {
  id: number
  name: string
  latestVersion: number
  publishedVersion?: number
}
export interface ReportDataset {
  key: string
  name: string
  fields: { key: string; title: string; type: string }[]
}
export interface ReportPreview {
  columns: { key: string; field: string; title: string; width: number; type: string }[]
  rows: Record<string, string | number | null>[]
  hasMore: boolean
  generatedAt: string
}
export const ReportApi = {
  datasets: () => request.get<ReportDataset[]>({ url: '/mes/report/datasets' }),
  list: () => request.get<ReportDefinition[]>({ url: '/mes/report/list' }),
  get: (id: number, version?: number) =>
    request.get<{
      definition: ReportDefinition
      version: number
      config: ReportConfig
      versions: { version: number; createTime?: string; name?: string }[]
    }>({ url: '/mes/report/get', params: { id, version } }),
  save: (data: { id?: number; name: string; expectedVersion?: number; config: ReportConfig }) =>
    request.post<{ id: number; version: number }>({ url: '/mes/report/save', data }),
  publish: (id: number, version: number) =>
    request.put({ url: '/mes/report/publish', params: { id, version } }),
  preview: (data: object) => request.post<ReportPreview>({ url: '/mes/report/preview', data }),
  export: (data: object) =>
    request.download({ url: '/mes/report/export-excel', method: 'POST', data })
}
