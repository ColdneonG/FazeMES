import type { Draft } from './index'

export interface BusinessEntity {
  id: number | null
  code: string | null
  name: string | null
}
export interface BusinessWarning {
  code: string
  message: string
}
export interface SourceReference {
  bizType: number
  bizId: number | null
  bizLineId: number | null
  canOpen: boolean
}
export interface QuantitySummary {
  openingQuantity: string | null
  inboundQuantity: string | null
  outboundQuantity: string | null
  closingQuantity: string | null
  balanced: boolean | null
}
export interface InventoryUnitGroup extends QuantitySummary {
  unitKey: string
  unitMeasureId: number | null
  unitCode: string | null
  unitName: string | null
  complete: boolean
  rowCount: number
}
export interface InventoryRow extends QuantitySummary {
  rowKey: string
  item: BusinessEntity
  category: BusinessEntity | null
  batch: { id: number | null; code: string | null } | null
  warehouse: BusinessEntity | null
  unitKey: string
  unitMeasureId: number | null
  unitName: string | null
  complete: boolean
  warnings: BusinessWarning[]
}
export interface EvidencePage {
  asOf: string
  basis: string
  complete: boolean
  warnings: BusinessWarning[]
  total: number
  pageNo: number
  pageSize: number
  hasMore: boolean
}
export interface InventoryEvidence extends EvidencePage {
  kind: 'INVENTORY_LEDGER'
  period: {
    year: number
    month: number
    start: string
    endExclusive: string
    effectiveEndExclusive: string
    partialMonth: boolean
    timezone: 'Asia/Shanghai'
  }
  filters: {
    item: BusinessEntity | null
    category: BusinessEntity | null
    includeCategoryChildren: boolean
    warehouse: BusinessEntity | null
    batchCode: string | null
    groupBy: 'ITEM' | 'BATCH' | 'WAREHOUSE'
    stockScope: 'ALL_PHYSICAL'
    classificationBasis: 'CURRENT_ITEM_CATEGORY'
  }
  unitGroups: InventoryUnitGroup[]
  list: InventoryRow[]
}
export interface DocumentMovement {
  id: number
  transactionAt: string | null
  bizLineId: number | null
  item: BusinessEntity
  batch: { id: number | null; code: string | null } | null
  warehouse: BusinessEntity | null
  location: BusinessEntity | null
  area: BusinessEntity | null
  unitKey: string
  unitName: string | null
  quantity: string
  direction: 'IN' | 'OUT'
  relatedTransactionId: number | null
  source: SourceReference
}
export interface DocumentEvidence extends EvidencePage {
  kind: 'STOCK_DOCUMENT'
  document: {
    bizType: number
    bizId: number | null
    bizCode: string
    bizTypeName: string
    postingStatus: 'POSTED' | 'NO_POSTED_MOVEMENTS'
    headerKnown: boolean
    businessStatus: number | string | null
    source: SourceReference
  }
  unitGroups: {
    unitKey: string
    unitMeasureId: number | null
    unitName: string | null
    inboundQuantity: string
    outboundQuantity: string
    netQuantity: string
    complete: boolean
  }[]
  list: DocumentMovement[]
}
export type StockEvidence = InventoryEvidence | DocumentEvidence
export interface BusinessClarification {
  token: string
  reason:
    | 'YEAR_REQUIRED'
    | 'PERIOD_REQUIRED'
    | 'ENTITY_AMBIGUOUS'
    | 'DOCUMENT_AMBIGUOUS'
    | 'SCOPE_TOO_LARGE'
    | 'NEED_FILTER'
    | 'INVALID_PERIOD'
  prompt: string
  expiresAt: string
  choices: { id: string; label: string }[]
  acceptsFreeText: boolean
}
export interface BusinessAskInput {
  question: string
  useModel: boolean
  conversationId?: number
  version?: number
  clarification?: { token: string; choiceId: string }
}
export interface BusinessAnswer {
  answer: string
  mode: 'RULES' | 'MODEL'
  topic: string
  evidence: StockEvidence | Record<string, unknown>
  suggestions: Draft[]
  generatedAt: string
  conversationId: number
  version: number
  turnId: number
  status: 'ANSWERED' | 'CLARIFY' | 'OUT_OF_SCOPE'
  clarification: BusinessClarification | null
  modelWarning?: string
}
export interface BusinessConversation {
  id: number
  title: string
  version: number
  turnCount: number
  createdAt: string
  updatedAt: string
}
export interface BusinessTurn {
  id: number
  question: string
  createdAt: string
  version: number
  answer: BusinessAnswer
}
export interface BusinessHistory {
  conversation: BusinessConversation
  turns: BusinessTurn[]
  hasMore: boolean
  nextBeforeTurnId: number | null
}
export function isStockEvidence(value: BusinessAnswer['evidence']): value is StockEvidence {
  return value?.kind === 'INVENTORY_LEDGER' || value?.kind === 'STOCK_DOCUMENT'
}
