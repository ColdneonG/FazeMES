import request from '@/config/axios'

// Decimal values are calculated by the server; the browser only formats them.
export type CostDecimal = number | string | null | undefined
export interface CostProfile {
  id?: number
  itemId: number
  version?: number
  purchasePrice?: CostDecimal
  salePrice?: CostDecimal
  laborCost?: CostDecimal
  overheadCost?: CostDecimal
  remark?: string
  createTime?: number | string
}
export interface CostLeaf {
  itemId: number
  itemCode: string
  itemName: string
  unitMeasureName?: string
  quantity: CostDecimal
  purchasePrice: CostDecimal
  amount: CostDecimal
}
export interface ProductCost {
  itemId: number
  itemCode: string
  itemName: string
  currency: 'CNY'
  taxBasis: 'EXCLUSIVE'
  quantity?: CostDecimal
  unitMeasureName?: string
  status: 'COMPLETE' | 'INCOMPLETE'
  purchasePrice: CostDecimal
  salePrice: CostDecimal
  laborCost: CostDecimal
  overheadCost: CostDecimal
  materialCost: CostDecimal
  totalCost: CostDecimal
  leaves: CostLeaf[]
  children: ProductCost[]
  issues: string[]
}
export interface CostEffort {
  id?: number
  requestId?: string
  workorderId: number
  processId?: number
  hours: number | string
  hourlyRate: number | string
  overheadAmount: number | string
  remark?: string
  createTime?: number | string
  voided?: boolean
  voidReason?: string
  voidTime?: number | string
  voidBy?: string
}
export interface WorkorderCost {
  workorderId: number
  status: 'INCOMPLETE' | 'PROVISIONAL' | 'SETTLED'
  planSnapshot: ProductCost | null
  planQuantity: CostDecimal
  planMaterialCost?: CostDecimal
  planLaborCost?: CostDecimal
  planOverheadCost?: CostDecimal
  planTotalCost?: CostDecimal
  totalVariance?: CostDecimal
  unitVariance?: CostDecimal
  actualMaterialCost: CostDecimal
  actualLaborCost: CostDecimal
  actualOverheadCost: CostDecimal
  actualTotalCost: CostDecimal
  producedQuantity: CostDecimal
  actualUnitCost: CostDecimal
  issues: string[]
  efforts: CostEffort[]
  lossQuantity?: CostDecimal
  lossAllocationMethod?: string
  lossAllocationReason?: string
  lossAllocatedBy?: string | number
  lossAllocatedAt?: string | number
}
export interface LinePrice {
  lineId: number
  unitPrice: CostDecimal
  remark?: string
  quantity?: CostDecimal
  amount?: CostDecimal
  locked?: boolean
}
export interface SalesCostLine {
  lineId: number
  itemId: number
  itemCode: string
  itemName: string
  quantity: CostDecimal
  unitPrice: CostDecimal
  salesAmount: CostDecimal
  costAmount: CostDecimal
  grossProfit: CostDecimal
  grossMargin: CostDecimal
  status: string
  issues: string[]
}
export interface SalesCost {
  salesId: number
  status: string
  quantity: CostDecimal
  salesAmount: CostDecimal
  costAmount: CostDecimal
  grossProfit: CostDecimal
  grossMargin: CostDecimal
  lines: SalesCostLine[]
  issues: string[]
}
export interface ReturnCostLine {
  lineId: number
  itemId: number
  itemCode: string
  itemName: string
  quantity: CostDecimal
  postedQuantity: CostDecimal
  originTransactionId: number | null
  originSalesId: number | null
  originSalesCode: string | null
  originSalesLineId: number | null
  materialAmount: CostDecimal
  laborAmount: CostDecimal
  overheadAmount: CostDecimal
  costAmount: CostDecimal
  status: string
  issues: string[]
}
export interface ReturnCostOriginal {
  originTransactionId: number
  salesId: number
  salesCode: string
  salesLineId: number
  itemId: number
  itemCode: string
  itemName: string
  outboundQuantity: CostDecimal
  returnedQuantity: CostDecimal
  remainingQuantity: CostDecimal
  originalCostAmount: CostDecimal
  returnedCostAmount: CostDecimal
  remainingCostAmount: CostDecimal
  status: string
  issues: string[]
}
export interface ReturnSalesCost {
  returnId: number
  returnCode: string
  status: string
  posted: boolean
  materialAmount: CostDecimal
  laborAmount: CostDecimal
  overheadAmount: CostDecimal
  costAmount: CostDecimal
  lines: ReturnCostLine[]
  originals: ReturnCostOriginal[]
  issues: string[]
}

export interface ChildSettlement {
  id: number
  code: string
  settled: boolean
}

export const CostApi = {
  getChildSettlements: (workorderId: number): Promise<ChildSettlement[]> =>
    request.get({ url: '/mes/cost/workorder/child-settlements', params: { workorderId } }),
  getReturnSales: (id: number): Promise<ReturnSalesCost> =>
    request.get({ url: '/mes/cost/return-sales', params: { id } }),
  getProduct: (itemId: number): Promise<ProductCost> =>
    request.get({ url: '/mes/cost/product', params: { itemId } }),
  getProfile: (itemId: number): Promise<CostProfile | null> =>
    request.get({ url: '/mes/cost/profile', params: { itemId } }),
  getProfileHistory: (itemId: number): Promise<CostProfile[]> =>
    request.get({ url: '/mes/cost/profile-history', params: { itemId } }),
  saveProfile: (data: CostProfile) => request.put({ url: '/mes/cost/profile', data }),
  getWorkorder: (workorderId: number): Promise<WorkorderCost> =>
    request.get({ url: '/mes/cost/workorder', params: { workorderId } }),
  capturePlan: (workorderId: number) =>
    request.post({ url: '/mes/cost/workorder/snapshot', params: { workorderId } }),
  addEffort: (data: CostEffort) => request.post({ url: '/mes/cost/workorder/effort', data }),
  voidEffort: (data: { id: number; reason: string }) =>
    request.post({ url: '/mes/cost/workorder/effort/void', data }),
  settleWorkorder: (workorderId: number) =>
    request.post({ url: '/mes/cost/workorder/settle', params: { workorderId } }),
  allocateWorkorderLoss: (data: { workorderId: number; reason: string }) =>
    request.post({ url: '/mes/cost/workorder/loss-allocation', data }),
  getReceiptLinePrice: (lineId: number): Promise<LinePrice | null> =>
    request.get({ url: '/mes/cost/receipt-line-price', params: { lineId } }),
  saveReceiptLinePrice: (data: LinePrice) =>
    request.put({ url: '/mes/cost/receipt-line-price', data }),
  getSales: (salesId: number): Promise<SalesCost> =>
    request.get({ url: '/mes/cost/sales', params: { salesId } }),
  saveSalesLinePrice: (data: LinePrice) => request.put({ url: '/mes/cost/sales-line-price', data })
}
