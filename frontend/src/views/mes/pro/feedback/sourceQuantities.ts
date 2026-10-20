import type { FeedbackSource, FeedbackSourceOption } from '@/api/mes/pro/feedback'

import { quantityUnits } from '@/utils/mesQuantity'
export { quantityUnits, quantityText, sumQuantities, quantityRule } from '@/utils/mesQuantity'

export const sourceValidationError = (
  sources: FeedbackSource[],
  options: FeedbackSourceOption[],
  quantity: unknown,
  requireTotal: boolean
): string => {
  let total = 0n
  const seen = new Set<number>()
  for (const source of sources) {
    if (seen.has(source.sourceFeedbackId)) return '同一前序报工只能分配一次'
    seen.add(source.sourceFeedbackId)
    const option = options.find((item) => item.id === source.sourceFeedbackId)
    if (!option) return '前序来源已变化，请刷新来源并重新选择'
    const units = quantityUnits(source.quantity)
    if (units === undefined || units <= 0n)
      return '来源数量必须大于零，最多支持 14 位整数和 6 位小数'
    const available = quantityUnits(option.available)
    if (available === undefined || units > available)
      return `来源 ${option.code} 的数量超过当前可用量`
    total += units
  }
  if (requireTotal) {
    const expected = quantityUnits(quantity)
    if (expected === undefined || expected <= 0n) return '请先填写有效的本次报工数量'
    if (total !== expected) return '前序来源分配合计必须等于本次报工数量'
  }
  return ''
}
