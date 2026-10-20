import request from '@/config/axios'

export interface SalesFulfillment {
  noticeQuantity: string | null
  shippedQuantity: string | null
  reservedQuantity: string | null
  availableQuantity: string | null
  editableAvailableQuantity: string | null
  fulfillmentReliable: boolean
  fulfillmentWarning?: string
}

// MES 发货通知单行 VO
export interface WmSalesNoticeLineVO extends SalesFulfillment {
  id: number
  noticeId: number
  itemId: number
  itemCode: string
  itemName: string
  specification: string
  unitMeasureName: string
  batchId: number
  batchCode: string
  quantity: number | string
  oqcCheckFlag: boolean
  remark: string
  createTime: string
}

// MES 发货通知单行 API
export const WmSalesNoticeLineApi = {
  // 查询发货通知单行分页
  getSalesNoticeLinePage: async (params: any) => {
    return await request.get({ url: '/mes/wm/sales-notice-line/page', params })
  },

  // 查询发货通知单行详情
  getSalesNoticeLine: async (id: number, editingSalesLineId?: number) => {
    return await request.get({
      url: '/mes/wm/sales-notice-line/get',
      params: { id, editingSalesLineId }
    })
  },

  // 新增发货通知单行
  createSalesNoticeLine: async (data: WmSalesNoticeLineVO) => {
    return await request.post({ url: '/mes/wm/sales-notice-line/create', data })
  },

  // 修改发货通知单行
  updateSalesNoticeLine: async (data: WmSalesNoticeLineVO) => {
    return await request.put({ url: '/mes/wm/sales-notice-line/update', data })
  },

  // 删除发货通知单行
  deleteSalesNoticeLine: async (id: number) => {
    return await request.delete({ url: '/mes/wm/sales-notice-line/delete?id=' + id })
  }
}
