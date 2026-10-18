import type { CostDecimal } from '@/api/mes/cost'
import download from '@/utils/download'

const plainDecimal = (value: CostDecimal): string | undefined => {
  if (value == null || value === '') return undefined
  const match = String(value).match(/^(-?)(\d+)(?:\.(\d+))?(?:[Ee]([+-]?\d+))?$/)
  if (!match) return undefined
  const exponent = Number(match[4] ?? 0)
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 100) return undefined
  const digits = match[2] + (match[3] ?? '')
  const position = match[2].length + exponent
  const plain =
    position <= 0
      ? `0.${'0'.repeat(-position)}${digits}`
      : position >= digits.length
        ? digits + '0'.repeat(position - digits.length)
        : `${digits.slice(0, position)}.${digits.slice(position)}`
  return match[1] + plain
}

/** Keep missing prices distinct from a deliberately entered zero. */
export const costNumber = (value: CostDecimal): string => {
  if (value === null || value === undefined || value === '') return '待核定'
  // Cost APIs serialize BigDecimal as strings. Never coerce monetary amounts to
  // a JS Number: a 12-digit price with 6 fractional digits exceeds its precision.
  const raw = plainDecimal(value)
  if (raw === undefined) return '待核定'
  const match = raw.match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) return '待核定'
  const integer = match[2].replace(/^0+(?=\d)/, '')
  const fraction = (match[3] ?? '').replace(/0+$/, '')
  const negative = match[1] && (integer !== '0' || fraction) ? '-' : ''
  return `${negative}${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fraction ? `.${fraction}` : ''}`
}
export const money = (value: CostDecimal): string => {
  const formatted = costNumber(value)
  return formatted === '待核定' ? formatted : `¥${formatted}`
}
export const margin = (value: CostDecimal): string => {
  if (value === null || value === undefined || value === '') return '待核定'
  const parsed = Number(value)
  return Number.isFinite(parsed)
    ? parsed.toLocaleString('zh-CN', { style: 'percent', maximumFractionDigits: 2 })
    : '待核定'
}
export const exportCostSnapshot = (name: string, data: unknown) => {
  const blob = new Blob([
    JSON.stringify(
      { exportedAt: new Date().toISOString(), currency: 'CNY', taxBasis: 'EXCLUSIVE', data },
      null,
      2
    )
  ])
  download.json(blob, `${name.replace(/[\\/:*?"<>|]/g, '_')}.json`)
}
export const optionalAmount = (value: CostDecimal): string | undefined => plainDecimal(value)

/** Validate decimal text before sending the same exact value to BigDecimal. */
export const decimalRule = (integerDigits = 12, scale = 6, required = false) => ({
  validator: (_rule: unknown, value: unknown, done: (error?: Error) => void) => {
    if (value === undefined || value === null || value === '') {
      done(required ? new Error('请填写数值；零值请明确填 0') : undefined)
      return
    }
    const expression = new RegExp(`^\\d{1,${integerDigits}}(?:\\.\\d{1,${scale}})?$`)
    done(
      expression.test(String(value))
        ? undefined
        : new Error(`请输入非负数，最多 ${integerDigits} 位整数、${scale} 位小数`)
    )
  },
  trigger: ['blur', 'change']
})
