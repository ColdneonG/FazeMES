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

export const sumQuantities = (...values: unknown[]): string | undefined => {
  let total = 0n
  for (const value of values) {
    const units = quantityUnits(value)
    if (units === undefined) return undefined
    total += units
  }
  return quantityText(total)
}

export const quantityRule = (positive = false) => ({
  validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
    const units = quantityUnits(value)
    callback(
      units === undefined || (positive && units <= 0n)
        ? new Error(`数量须${positive ? '大于零' : '非负'}，最多 14 位整数和 6 位小数`)
        : undefined
    )
  },
  trigger: 'blur'
})
