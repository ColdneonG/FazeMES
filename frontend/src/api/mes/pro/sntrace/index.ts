import request from '@/config/axios'

export interface SnTrace {
  id: number
  snId: number
  snCode: string
  workOrderId: number
  productId: number
  productBatchCode: string | null
  status: 'REGISTERED' | 'COMPLETED'
  createTime: string
}
export interface SnMaterial {
  id: number
  consumeDetailId: number
  consumeLineId: number
  materialStockId: number
  itemId: number
  batchId?: number
  batchCode?: string
  quantity: number | string
}
export interface SnStation {
  id: number
  feedbackId: number
  feedbackCode: string
  taskId: number
  routeId: number
  processId: number
  processSort: number
  workstationId?: number
  operatorId: number
  feedbackUserId?: number
  stationTime: string
  feedbackTime?: string
  materials: SnMaterial[]
}
export interface TraceDetail {
  sn: SnTrace
  stations: SnStation[]
}
export interface FeedbackOption {
  id: number
  code: string
  taskId: number
  processId: number
  feedbackQuantity: number | string
}
export interface ConsumeOption {
  consumeDetailId: number
  consumeLineId: number
  itemId: number
  batchId?: number
  batchCode?: string
  materialStockId: number
  quantity: number | string
  allocatedQuantity: number | string
  availableQuantity: number | string
  unitQuantity: number | string
}
export interface StationRequest {
  requestKey: string
  traceId: number
  feedbackId: number
  materials: { consumeDetailId: number; quantity: number | string }[]
}
const base = '/mes/pro/sn-trace'
export const SnTraceApi = {
  page: (params: {
    pageNo: number
    pageSize: number
    workOrderId?: number
    snCode?: string
    batchCode?: string
  }) => request.get<{ list: SnTrace[]; total: number }>({ url: `${base}/page`, params }),
  get: (id: number) => request.get<TraceDetail>({ url: `${base}/get`, params: { id } }),
  register: (data: { workOrderId: number; snId?: number; snCode?: string }) =>
    request.post<number>({ url: `${base}/register`, data }),
  station: (data: StationRequest) => request.post<number>({ url: `${base}/station`, data }),
  feedbackOptions: (workOrderId: number) =>
    request.get<FeedbackOption[]>({ url: `${base}/feedback-options`, params: { workOrderId } }),
  consumeOptions: (feedbackId: number) =>
    request.get<ConsumeOption[]>({ url: `${base}/consume-options`, params: { feedbackId } }),
  affected: (params: { batchId?: number; batchCode?: string }) =>
    request.get<SnTrace[]>({ url: `${base}/affected`, params })
}
