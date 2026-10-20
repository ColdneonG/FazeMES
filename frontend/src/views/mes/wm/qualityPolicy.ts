/** System quality quarantine is separate from the normal, manually reversible freeze. */
export const QUALITY_QUARANTINE_WAREHOUSE = 'QUALITY_QUARANTINE_WAREHOUSE'

export type QuarantineFilter = 'all' | 'exclude' | 'only'

export const isQualityQuarantineWarehouse = (code?: string) =>
  code?.toUpperCase() === QUALITY_QUARANTINE_WAREHOUSE

export const matchesQuarantineFilter = (code: string | undefined, filter: QuarantineFilter) =>
  filter === 'all' || isQualityQuarantineWarehouse(code) === (filter === 'only')

export const returnQualityGuidance = (qualityStatus?: number) => {
  if (qualityStatus === 2) {
    return '检验不合格：必须上架至质量隔离仓；隔离库存不能销售、领料或调拨出库。'
  }
  if (qualityStatus === 0) return '当前物料待检验，完成检验后才能保存上架明细和执行入库。'
  if (qualityStatus !== 1) return '尚未取得质量状态，请重新打开上架明细后再操作。'
  return '合格物料可正常上架；放入质量隔离仓后不能销售、领料或调拨出库。'
}

export const stockSelectionFilters = (outboundOnly: boolean) => ({
  frozen: outboundOnly ? false : undefined,
  qualityHold: outboundOnly ? false : undefined,
  quarantineFilter: outboundOnly ? 'exclude' : 'all'
})

export interface StockQualityState {
  warehouseCode?: string
  qualityHold?: boolean
  qualityHoldReason?: string
  frozen?: boolean
}

export const stockQualityBlockReason = (stock: StockQualityState) => {
  if (stock.qualityHold) {
    return stock.qualityHoldReason || '历史不良混入，待管理员核对；普通解冻不能解除质量管控。'
  }
  if (isQualityQuarantineWarehouse(stock.warehouseCode)) {
    return '质量隔离库存，禁止销售、领料及调拨出库。'
  }
  if (stock.frozen) return '该库存已冻结。'
  return ''
}
