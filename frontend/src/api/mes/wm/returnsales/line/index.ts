import request from '@/config/axios'

export interface ReturnSourceBalance {
  outboundQuantity?: string | null
  returnedQuantity?: string | null
  reservedQuantity?: string | null
  availableQuantity?: string | null
  editableAvailableQuantity?: string | null
  sourceReliable?: boolean
  sourceWarning?: string | null
}
export interface ReturnSalesSource extends ReturnSourceBalance {
  id: number
  originTransactionId: number
  salesId: number
  salesCode: string
  salesLineId: number
  itemId: number
  itemCode: string
  itemName: string
  itemSpecification: string | null
  unitMeasureId: number | null
  unitMeasureName: string | null
  batchId: number | null
  batchCode: string | null
  materialStockId: number
  warehouseId: number
  warehouseName: string | null
  locationId: number
  locationName: string | null
  areaId: number
  areaName: string | null
  outboundAt: string | null
}
export interface ReturnSourceQuery {
  returnId: number
  lineId?: number
  originTransactionId?: number
  salesCode?: string
  itemCode?: string
  batchCode?: string
  pageNo: number
  pageSize: number
}
export interface ReturnSalesLineSave {
  id?: number
  returnId: number
  originTransactionId: number
  quantity: string
  rqcCheckFlag: boolean
  remark?: string
}

// MES 销售退货单行 VO
export interface WmReturnSalesLineVO extends ReturnSourceBalance {
  id?: number
  returnId: number
  itemId: number
  itemCode?: string
  itemName?: string
  specification?: string
  unitMeasureName?: string
  quantity: number | string
  batchId?: number | null
  batchCode?: string | null
  rqcCheckFlag: boolean
  rqcId?: number
  originTransactionId?: number | null
  originSalesId?: number | null
  originSalesCode?: string | null
  originSalesLineId?: number | null
  qualityStatus?: number
  remark?: string
}

// MES 销售退货单行 API
export const WmReturnSalesLineApi = {
  getSourceOptions: (
    params: ReturnSourceQuery
  ): Promise<{ list: ReturnSalesSource[]; total: number }> =>
    request.get({ url: '/mes/wm/return-sales-line/source-options', params }),
  // 查询销售退货单行分页
  getReturnSalesLinePage: async (params: any) => {
    return await request.get({ url: '/mes/wm/return-sales-line/page', params })
  },

  // 查询销售退货单行详情
  getReturnSalesLine: async (id: number) => {
    return await request.get({ url: '/mes/wm/return-sales-line/get?id=' + id })
  },

  // 新增销售退货单行
  createReturnSalesLine: async (data: ReturnSalesLineSave) => {
    return await request.post({ url: '/mes/wm/return-sales-line/create', data })
  },

  // 修改销售退货单行
  updateReturnSalesLine: async (data: ReturnSalesLineSave) => {
    return await request.put({ url: '/mes/wm/return-sales-line/update', data })
  },

  // 删除销售退货单行
  deleteReturnSalesLine: async (id: number) => {
    return await request.delete({ url: '/mes/wm/return-sales-line/delete?id=' + id })
  }
}
