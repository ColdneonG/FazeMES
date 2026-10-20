import type { BusinessEntity, SourceReference } from '@/api/mes/insight/business'

export const sourcePermissions: Readonly<Record<number, string>> = Object.freeze({
  110: 'mes:wm-item-receipt:query',
  111: 'mes:wm-transfer:query',
  112: 'mes:wm-transfer:query',
  113: 'mes:wm-misc-issue:query',
  114: 'mes:wm:misc-receipt:query',
  115: 'mes:wm-product-issue:query',
  116: 'mes:wm-return-issue:query',
  117: 'mes:wm-product-receipt:query',
  118: 'mes:wm-product-sales:query',
  119: 'mes:wm-return-sales:query',
  120: 'mes:wm-outsource-issue:query',
  121: 'mes:wm-outsource-receipt:query',
  124: 'mes:wm-return-vendor:query'
})
export function canOpenSource(
  source: SourceReference | null | undefined,
  hasPermission: (permissions: string[]) => boolean
) {
  return (
    !!source &&
    source.canOpen === true &&
    Number.isSafeInteger(source.bizId) &&
    (source.bizId || 0) > 0 &&
    Object.hasOwn(sourcePermissions, source.bizType) &&
    hasPermission([sourcePermissions[source.bizType]])
  )
}
export const exactQuantity = (value: string | null | undefined) =>
  value == null ? '待核对' : value
export const entityLabel = (entity: BusinessEntity | null | undefined) =>
  entity
    ? [entity.code, entity.name].filter(Boolean).join(' · ') || `记录 #${entity.id ?? '未知'}`
    : '—'
