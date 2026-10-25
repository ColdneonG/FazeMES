import { quantityText, quantityUnits } from '@/utils/mesQuantity'

interface AllocationRow {
  consumeLineId: number
  inputQuantity: string | number
  unitQuantity: string | number
  availableQuantity: string | number
}

/** All comparisons use the same six-decimal units as the server's allocation check. */
export const snAllocationTotals = (rows: AllocationRow[]) => {
  const totals = new Map<
    number,
    { id: number; units: bigint; required: string; requiredUnits?: bigint; valid: boolean }
  >()
  for (const row of rows) {
    const required = quantityUnits(row.unitQuantity)
    const line = totals.get(row.consumeLineId) || {
      id: row.consumeLineId,
      units: 0n,
      required: String(row.unitQuantity),
      requiredUnits: required,
      valid: required !== undefined
    }
    const input = quantityUnits(row.inputQuantity)
    const available = quantityUnits(row.availableQuantity)
    line.valid &&= input !== undefined && available !== undefined && input <= available
    line.valid &&= required === line.requiredUnits
    if (input !== undefined) line.units += input
    totals.set(row.consumeLineId, line)
  }
  return [...totals.values()].map((line) => ({
    id: line.id,
    quantity: quantityText(line.units),
    required: line.required,
    valid: line.valid && line.units === line.requiredUnits
  }))
}

/** Greedily propose one unit without Number conversion, rounding, or exceeding a source. */
export const distributeSnMaterials = (rows: AllocationRow[]) => {
  const remaining = new Map<number, bigint>()
  let valid = true
  const quantities = rows.map((row) => {
    const unit = quantityUnits(row.unitQuantity)
    const available = quantityUnits(row.availableQuantity)
    if (unit === undefined || available === undefined) {
      valid = false
      return '0'
    }
    const need = remaining.get(row.consumeLineId) ?? unit
    const allocated = need < available ? need : available
    remaining.set(row.consumeLineId, need - allocated)
    return quantityText(allocated)
  })
  return { quantities, complete: valid && [...remaining.values()].every((value) => value === 0n) }
}
