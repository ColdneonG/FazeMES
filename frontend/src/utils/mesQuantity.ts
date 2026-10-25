const SCALE = 1_000_000n

/** Compare DECIMAL(20,6) text without floating point rounding. */
export const quantityUnits = (value: unknown): bigint | undefined => {
  if (typeof value !== 'number' && typeof value !== 'string') return undefined
  const text = String(value).trim()
  if (!/^(?:0|[1-9]\d{0,13})(?:\.\d{1,6})?$/.test(text)) return undefined
  const [whole, fraction = ''] = text.split('.')
  return BigInt(whole) * SCALE + BigInt(fraction.padEnd(6, '0'))
}

export const quantityText = (units: bigint): string => {
  const fraction = (units % SCALE).toString().padStart(6, '0').replace(/0+$/, '')
  return `${units / SCALE}${fraction ? `.${fraction}` : ''}`
}

/** Localized display preserves all meaningful digits rather than rounding through Number. */
export const formatQuantity = (value: unknown): string => {
  const units = quantityUnits(value)
  if (units === undefined) return value == null ? '—' : String(value)
  const [whole, fraction] = quantityText(units).split('.')
  return `${BigInt(whole).toLocaleString('zh-CN')}${fraction ? `.${fraction}` : ''}`
}

export const sumQuantities = (...values: unknown[]): string | undefined => {
  let total = 0n
  for (const value of values) {
    const units = quantityUnits(value)
    if (units === undefined) return undefined
    total += units
  }
  return quantityText(total)
}

/** Minimum of validated quantities; never coerce persisted DECIMAL values to Number. */
export const minQuantity = (...values: unknown[]): string | undefined => {
  const units = values.map(quantityUnits)
  if (!units.length || units.some((value) => value === undefined)) return undefined
  return quantityText((units as bigint[]).reduce((a, b) => (a < b ? a : b)))
}

export const quantityRule = (positive = false, maximum?: unknown, optional = false) => ({
  validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
    if (optional && (value == null || value === '')) {
      callback()
      return
    }
    const units = quantityUnits(value)
    const max = quantityUnits(typeof maximum === 'function' ? maximum() : maximum)
    if (units === undefined || (positive && units <= 0n)) {
      callback(new Error(`数量须${positive ? '大于零' : '非负'}，最多 14 位整数和 6 位小数`))
    } else if (max !== undefined && units > max) {
      callback(new Error(`数量不能超过可用数量 ${quantityText(max)}`))
    } else callback()
  },
  trigger: 'blur'
})
