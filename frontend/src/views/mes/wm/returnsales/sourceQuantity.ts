import type { ReturnSourceBalance } from '@/api/mes/wm/returnsales/line'
import { quantityUnits } from '@/utils/mesQuantity'

export function returnQuantityError(
  value: unknown,
  source: ReturnSourceBalance | undefined
): string {
  const quantity = quantityUnits(value)
  if (quantity === undefined || quantity <= 0n) return '退货数量须大于零，最多14位整数和6位小数'
  if (!source || source.sourceReliable !== true)
    return source?.sourceWarning || '请先选择已核对的原销售出库流水'
  const available = quantityUnits(source.editableAvailableQuantity)
  if (available === undefined) return '原出库可退额度待核对，请刷新来源'
  if (quantity > available) return '退货数量超过本行可用的原出库额度'
  return ''
}
export function canSelectReturnSource(source: ReturnSourceBalance | undefined): boolean {
  return (
    source?.sourceReliable === true && (quantityUnits(source.editableAvailableQuantity) ?? 0n) > 0n
  )
}
export const returnBalanceText = (value: string | null | undefined) =>
  value == null ? '待核对' : value
