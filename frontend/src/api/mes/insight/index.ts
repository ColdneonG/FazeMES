import request from '@/config/axios'
import type {
  BusinessAnswer,
  BusinessAskInput,
  BusinessConversation,
  BusinessHistory,
  StockEvidence
} from './business'
export type { BusinessAnswer as Answer } from './business'

export interface Reason {
  type: string
  message: string
  action: string
}
export interface RiskOrder {
  id: number
  code: string
  name: string
  productName: string
  quantity: number
  producedQuantity: number
  dueAt?: string
  forecastAt?: string
  riskLevel: string
  reasons: Reason[]
  delayCalendarHours?: number
  queueCalendarHours: number
  tasks: Record<string, unknown>[]
  materials: Record<string, unknown>[]
  andons: Record<string, unknown>[]
  pendingInspections: Record<string, unknown>[]
}
export interface Option {
  id: number
  name: string
}
export interface RiskData {
  orders: RiskOrder[]
  summary: { open: number; high: number; unknown: number }
  warnings: string[]
  basis: string
  generatedAt: string
  workstations: Option[]
}
export interface SimulationInput {
  workOrderId?: number
  dailyHours: number
  hourlyCost: number
  workstationId?: number
  manualHours: number
}
export interface Impact {
  id: number
  code: string
  baselineAt?: string
  forecastAt?: string
  shiftHours?: number
  late: boolean
  selected: boolean
}
export interface Scenario {
  key: string
  name: string
  dailyHours: number
  selectedFinish?: string
  overtimeHours: number
  addedCost: number
  impacts: Impact[]
  slots: {
    taskId: number
    workOrderId: number
    workstationId: number
    name: string
    startAt: string
    endAt: string
    effortHours: number
    manual: boolean
  }[]
}
export interface SimulationData {
  scenarios: Scenario[]
  basis: string
  warnings: string[]
  manualEstimate: boolean
  generatedAt: string
}
export interface QualityGroup {
  dimension: string
  value: string
  product: string
  quantity: number
  rejected: number
  rate: number
  baselineRate: number
  difference: number
  records: number
  sufficient: boolean
  evidence: Record<string, unknown>[]
}
export interface QualityData {
  groups: QualityGroup[]
  records: number
  excluded: number
  basis: string
}
export interface FlowData {
  stations: {
    workstationId: number
    station: string
    tasks: number
    pending: number
    samples: number
    medianWaitHours?: number
    evidence: Record<string, unknown>[]
  }[]
  paths: { from: string; to: string; count: number; averageGapHours: number; orders: number[] }[]
  reportCount: number
  basis: string
}
export interface Incident {
  id: number
  title: string
  category: string
  workOrderId?: number
  workstationId?: number
  andonId?: number
  ownerId?: number
  ownerName?: string
  orderCode?: string
  dueAt?: string
  description?: string
  status: string
  rootCause?: string
  correctiveAction?: string
  evidence?: string
  version: number
  createdAt: string
  departmentId?: number
  departmentName?: string
}
export interface IncidentData {
  cases: Incident[]
  andons: { id: number; reason: string; workOrderId?: number; workstationId?: number }[]
  owners: Option[]
  departments: Option[]
  truncated: boolean
}
export interface IncidentInput {
  title: string
  category: string
  workOrderId?: number
  workstationId?: number
  andonId?: number
  ownerId?: number
  dueAt?: string
  description?: string
  departmentId?: number
  triageId?: number
}
export interface JevSettings {
  endpoint: string
  transport?: string
  model: string
  enabled: boolean
  configured: boolean
  apiKeyConfigured: boolean
}
export interface JevStatus {
  configured: boolean
  model: string
  departments: Option[]
  stats: { generated: number; reviewed: number; categoryAgreed: number; departmentAgreed: number }
}
export interface JevSuggestion {
  id: number
  model: string
  category: string
  departmentId?: number
  departmentName: string
  categoryConfidence: number
  departmentConfidence: number
  message: string
}
export interface IncidentMove {
  id: number
  version: number
  status: string
  ownerId?: number
  dueAt?: string
  rootCause: string
  correctiveAction: string
  evidence: string
  note: string
}
export interface Draft {
  id: number
  title: string
  content: string
  workOrderId?: number
  status?: string
  incidentId?: number
  at?: string
}
const base = '/mes/insight'
export const InsightApi = {
  jevStatus: () => request.get<JevStatus>({ url: `${base}/jev/status` }),
  jevSettings: () => request.get<JevSettings>({ url: `${base}/jev/config` }),
  saveJevSettings: (data: {
    model: string
    apiKey: string
    clearApiKey: boolean
    enabled: boolean
  }) => request.put<JevSettings>({ url: `${base}/jev/config`, data }),
  testJev: () =>
    request.post<{ connected: boolean; model: string; message: string }>({
      url: `${base}/jev/test`,
      timeout: 90000
    }),
  suggestIncident: (data: { title: string; description?: string }) =>
    request.post<JevSuggestion>({ url: `${base}/jev/suggest`, data, timeout: 90000 }),
  risks: () => request.get<RiskData>({ url: `${base}/risks` }),
  simulate: (data: SimulationInput) =>
    request.post<SimulationData>({ url: `${base}/simulate`, data }),
  quality: (days: number, minSample: number) =>
    request.get<QualityData>({ url: `${base}/quality`, params: { days, minSample } }),
  flow: (days: number) => request.get<FlowData>({ url: `${base}/flow`, params: { days } }),
  timeline: (workOrderId: number) =>
    request.get<{
      orderCode: string
      events: {
        at: string
        type: string
        label: string
        id: number
        quantity?: number
        rejected?: number
      }[]
      basis: string
    }>({ url: `${base}/timeline`, params: { workOrderId } }),
  incidents: () => request.get<IncidentData>({ url: `${base}/incidents` }),
  history: (id: number) =>
    request.get<Record<string, unknown>[]>({ url: `${base}/incident-history`, params: { id } }),
  createIncident: (data: IncidentInput) => request.post<number>({ url: `${base}/incident`, data }),
  moveIncident: (data: IncidentMove) => request.put({ url: `${base}/incident`, data }),
  ask: (input: string | BusinessAskInput, useModel = false) =>
    request.post<BusinessAnswer>({
      url: `${base}/assistant`,
      data: typeof input === 'string' ? { question: input, useModel } : input,
      timeout: 90000
    }),
  conversationPage: (pageNo = 1, pageSize = 20) =>
    request.get<{ list: BusinessConversation[]; total: number }>({
      url: `${base}/conversation/page`,
      params: { pageNo, pageSize }
    }),
  createConversation: (title?: string) =>
    request.post<BusinessConversation>({ url: `${base}/conversation/create`, data: { title } }),
  conversation: (id: number, beforeTurnId?: number, pageSize = 20) =>
    request.get<BusinessHistory>({
      url: `${base}/conversation/get`,
      params: { id, beforeTurnId, pageSize }
    }),
  deleteConversation: (id: number, version: number) =>
    request.delete<boolean>({ url: `${base}/conversation/delete`, params: { id, version } }),
  conversationEvidence: (turnId: number, pageNo = 1, pageSize = 20) =>
    request.get<StockEvidence>({
      url: `${base}/conversation/evidence`,
      params: { turnId, pageNo, pageSize }
    }),
  drafts: () => request.get<Draft[]>({ url: `${base}/drafts` }),
  saveDraft: (data: Pick<Draft, 'title' | 'content' | 'workOrderId'>) =>
    request.post<number>({ url: `${base}/draft`, data }),
  convertDraft: (id: number) =>
    request.post<number>({ url: `${base}/draft-convert`, params: { id } })
}
