import request from '@/config/axios'

export interface KittingMaterial {
  itemId: number
  itemCode: string
  itemName: string
  unitName: string
  requiredQuantity: number
  issuedQuantity: number
  outstandingQuantity: number
  physicalQuantity: number
  reservedQuantity: number
  usableReservedQuantity: number
  otherReservedQuantity: number
  freeQuantity: number
  availableQuantity: number
  shortageQuantity: number
  trackingStatus: string
  expectedDate?: string
  remark?: string
}
export interface KittingAnalysis {
  workOrderId: number
  workOrderCode: string
  workOrderName: string
  workOrderStatus: number
  status: string
  ready: boolean
  shortageItemCount: number
  analyzedAt: string
  materials: KittingMaterial[]
}
export interface KittingReservation {
  id: number
  materialStockId: number
  itemId: number
  quantity: number
  allocatedQuantity: number
  consumedQuantity: number
  releasedQuantity: number
  batchCode?: string
  warehouseName?: string
  locationName?: string
  areaName?: string
  eligible: boolean
}
export interface KittingEvent {
  id: number
  itemId: number
  materialStockId: number
  reservationId: number
  action: string
  quantity: number
  referenceId?: number
  operatorId: number
  createTime: string
}
export interface KittingFollowup {
  workOrderId: number
  itemId: number
  trackingStatus: string
  expectedDate?: string | null
  remark?: string
}
export const KittingApi = {
  board: (params: { keyword?: string; onlyShortage: boolean }) =>
    request.get<KittingAnalysis[]>({ url: '/mes/pro/kitting/board', params }),
  analyze: (workOrderId: number) =>
    request.get<KittingAnalysis>({ url: '/mes/pro/kitting/analyze', params: { workOrderId } }),
  reserve: (workOrderId: number) =>
    request.post<KittingAnalysis>({ url: '/mes/pro/kitting/reserve', params: { workOrderId } }),
  release: (workOrderId: number) =>
    request.post({ url: '/mes/pro/kitting/release', params: { workOrderId } }),
  followup: (data: KittingFollowup) => request.put({ url: '/mes/pro/kitting/followup', data }),
  reservations: (workOrderId: number) =>
    request.get<KittingReservation[]>({
      url: '/mes/pro/kitting/reservations',
      params: { workOrderId }
    }),
  history: (workOrderId: number) =>
    request.get<KittingEvent[]>({ url: '/mes/pro/kitting/history', params: { workOrderId } })
}
