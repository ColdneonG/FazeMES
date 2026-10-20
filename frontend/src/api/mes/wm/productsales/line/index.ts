import request from '@/config/axios'
import type { SalesFulfillment } from '@/api/mes/wm/salesnotice/line'

// MES 销售出库单行 VO
export interface WmProductSalesLineVO extends SalesFulfillment {
  id: number
  salesId: number
  noticeLineId: number
  itemId: number
  itemCode: string
  itemName: string
  quantity: number | string
  pickedQuantity: number | string
  batchId: number
  batchCode: string
  oqcCheckFlag: boolean
  remark: string
  createTime: string
}

// MES 销售出库单行 API
export const WmProductSalesLineApi = {
  // 查询销售出库单行分页
  getProductSalesLinePage: async (params: any) => {
    return await request.get({ url: '/mes/wm/product-sales-line/page', params })
  },

  // 查询销售出库单行详情
  getProductSalesLine: async (id: number) => {
    return await request.get({ url: '/mes/wm/product-sales-line/get?id=' + id })
  },

  // 新增销售出库单行
  createProductSalesLine: async (data: WmProductSalesLineVO) => {
    return await request.post({ url: '/mes/wm/product-sales-line/create', data })
  },

  // 修改销售出库单行
  updateProductSalesLine: async (data: WmProductSalesLineVO) => {
    return await request.put({ url: '/mes/wm/product-sales-line/update', data })
  },

  // 删除销售出库单行
  deleteProductSalesLine: async (id: number) => {
    return await request.delete({ url: '/mes/wm/product-sales-line/delete?id=' + id })
  }
}
