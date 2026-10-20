export type ModelPurpose =
  | 'KNOWLEDGE_CHAT'
  | 'BUSINESS_INSIGHT'
  | 'MODEL_CONNECTION_TEST'
  | 'JEV_TRIAGE'
  | 'JEV_CONNECTION_TEST'
export type UsageStatus =
  | 'NEW'
  | 'RESERVED'
  | 'DISPATCHED'
  | 'SETTLED'
  | 'DENIED'
  | 'CANCELLED'
  | 'RECONCILED'
export interface UsageLimits {
  dayCalls: string
  monthCalls: string
  dayTokens: string
  monthTokens: string
  dayAmount: string | null
  monthAmount: string | null
  maxConcurrency: number
  callsPerMinute: number
}
export interface UsagePriceCard {
  channel: 'CHAT' | 'JEV'
  provider: 'openai' | 'ollama' | 'typesafe'
  model: string
  inputPerMillion: string | null
  outputPerMillion: string | null
  perCallReservationAmount: string | null
}
export interface UsagePolicy {
  configured: boolean
  version: number
  enabled: boolean
  tokenScope: 'CHAT'
  currency: 'CNY'
  timezone: 'Asia/Shanghai'
  tenantLimits: UsageLimits | null
  defaultUserLimits: UsageLimits | null
  rates: UsagePriceCard[]
  coverageStart: string | null
  historicalUsageKnown: false
  updatedAt: string | null
}
export interface UsagePolicySave {
  expectedVersion: number
  enabled: boolean
  tenantLimits: UsageLimits
  defaultUserLimits: UsageLimits
  rates: UsagePriceCard[]
  reason: string
}
export interface UsageMetric {
  limit: string | null
  used: string
  reserved: string
  unknown: string | null
  remaining: string | null
}
export interface UsagePeriod {
  start: string
  end: string
  tokenScope: 'CHAT'
  calls: UsageMetric
  tokens: UsageMetric
  amount: UsageMetric
  knownUnknownAmount: string
  unknownTokenCalls: string
  unknownChatTokenCalls: string
  unknownJevTokenCalls: string
  actualTotalTokens: string | null
  unpricedCalls: string
  calculatedAmountIsEstimate: true
  usageComplete: boolean
  coverageComplete: boolean
  blocked: boolean
  blockReason: string | null
}
export interface UsageSubject {
  subjectType: 'TENANT' | 'USER'
  subjectId: number | null
  day: UsagePeriod
  month: UsagePeriod
  minute: { start: string; end: string; limit: number; admitted: string; remaining: string }
  live: { limit: number; active: string; staleReserved: string; remaining: string }
}
export interface UsageSummary {
  tokenScope: 'CHAT'
  currency: 'CNY'
  timezone: 'Asia/Shanghai'
  generatedAt: string
  coverageStart: string | null
  historicalUsageKnown: false
  policyConfigured: boolean
  policyEnabled: boolean
  tenant: UsageSubject
  user: UsageSubject
}
export interface UsageRow {
  id: string
  version: number
  userId: number
  purpose: ModelPurpose
  channel: 'CHAT' | 'JEV'
  provider: string
  model: string
  servedModel: string | null
  status: UsageStatus
  outcome: string | null
  usageBasis: 'REPORTED' | 'RESERVATION_HELD' | 'UNPRICED' | 'NONE' | 'MANUAL_CONFIRMED'
  tokenScope: 'CHAT'
  actualInputTokens: string | null
  actualOutputTokens: string | null
  actualTotalTokens: string | null
  reservedTokens: string | null
  unknownHeldTokens: string | null
  reservedAmount: string | null
  calculatedAmount: string | null
  unknownHeldAmount: string | null
  accountedTokens: string | null
  accountedAmount: string | null
  confirmedInputTokens: string | null
  confirmedOutputTokens: string | null
  confirmedAmount: string | null
  currency: 'CNY'
  priceSnapshot: (UsagePriceCard & { currency: 'CNY'; policyVersion: number }) | null
  policyVersion: number | null
  safeErrorKind: string | null
  httpStatus: number | null
  durationMs: string | null
  createdAt: string
  dispatchedAt: string | null
  settledAt: string | null
  staleAfter: string | null
  canReconcile: boolean
  lastReconciliationReason: string | null
  lastReconciledBy: number | null
  lastReconciledAt: string | null
}
export interface UsagePageParams {
  from?: string
  to?: string
  purpose?: ModelPurpose
  status?: UsageStatus
  userId?: number
  pageNo: number
  pageSize: number
}
export interface UsageReconcile {
  usageId: string
  expectedVersion: number
  action: 'CLOSE_AS_UNKNOWN' | 'CONFIRM_ACTUAL' | 'CONFIRM_NOT_DISPATCHED'
  inputTokens: string | null
  outputTokens: string | null
  confirmedAmount: string | null
  reason: string
}
