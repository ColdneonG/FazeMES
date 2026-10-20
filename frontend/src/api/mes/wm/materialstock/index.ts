import request from '@/config/axios'

// MES 库存台账 VO
export interface WmMaterialStockVO {
  id: number
  workOrderId?: number
  workOrderCode?: string
  itemTypeId: number
  itemId: number
  itemCode: string
  itemName: string
  specification: string
  unitMeasureName: string
  batchId: number
  batchCode: string
  warehouseId: number
  warehouseCode: string
  warehouseName: string
  locationId: number
  locationName: string
  areaId: number
  areaName: string
  vendorId: number
  vendorName: string
  quantity: number
  receiptTime: string
  frozen: boolean
  qualityHold?: boolean
  qualityHoldReason?: string
  createTime: string
}

// 历史质量核查证据：只读记录，不代表已经完成实物核对或质量处置。
export interface WmStockQualityHoldVO {
  id: number
  materialStockId?: number | null
  sourceTransactionId: number
  sourceBizType?: number | null
  sourceBizId?: number | null
  sourceBizLineId?: number | null
  arrivalTransactionId: number
  /** 服务端返回精确十进制文本；number 仅兼容旧版响应，展示时不作数值转换。 */
  observedQuantity?: string | number | null
  reason: string
  status: string
  createTime: string
}

// MES 库存台账 API
export const WmMaterialStockApi = {
  getQualityHoldPage: async (params: {
    pageNo: number
    pageSize: number
    materialStockId?: number
  }): Promise<{ list: WmStockQualityHoldVO[]; total: number }> => {
    return await request.get({ url: '/mes/wm/material-stock/quality-hold-page', params })
  },

  // 查询库存台账分页
  getMaterialStockPage: async (params: any) => {
    return await request.get({ url: '/mes/wm/material-stock/page', params })
  },

  // 查询库存记录详情
  getMaterialStock: async (id: number) => {
    return await request.get({ url: '/mes/wm/material-stock/get?id=' + id })
  },

  // 更新库存冻结状态
  updateMaterialStockFrozen: async (data: { id: number; frozen: boolean }) => {
    return await request.put({ url: '/mes/wm/material-stock/update-frozen', data })
  },

  // 导出库存台账 Excel
  exportMaterialStock: async (params: any) => {
    return await request.download({ url: '/mes/wm/material-stock/export-excel', params })
  }
}
