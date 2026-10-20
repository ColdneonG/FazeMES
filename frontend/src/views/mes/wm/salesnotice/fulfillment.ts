import type { SalesFulfillment } from '@/api/mes/wm/salesnotice/line'
import { quantityUnits } from '@/utils/mesQuantity'

export const canSelectNoticeLine = (line: SalesFulfillment): boolean => {
  const available = quantityUnits(line.editableAvailableQuantity)
  return line.fulfillmentReliable === true && available !== undefined && available > 0n
}

export const salesQuantityError = (quantity: unknown, quota?: SalesFulfillment): string => {
  const units = quantityUnits(quantity)
  if (units === undefined || units <= 0n) return '出库数量必须大于零，最多支持 14 位整数和 6 位小数'
  if (!quota) return ''
  if (!quota.fulfillmentReliable)
    return quota.fulfillmentWarning || '历史履约数据待核对，暂不能发货'
  const available = quantityUnits(quota.editableAvailableQuantity)
  if (available === undefined) return '请刷新通知单行的可用额度'
  return units > available ? '出库数量超过当前可发货额度，请调整数量或刷新通知单行' : ''
}
