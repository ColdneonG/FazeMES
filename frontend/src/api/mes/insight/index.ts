import request from '@/config/axios'

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
}
export interface IncidentData {
  cases: Incident[]
  andons: { id: number; reason: string; workOrderId?: number; workstationId?: number }[]
  owners: Option[]
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
export interface Answer {
  answer: string
  mode: string
  topic: string
  evidence: Record<string, unknown>
  suggestions: Draft[]
  generatedAt: string
}
const base = '/mes/insight'
export const InsightApi = {
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
  ask: (question: string, useModel: boolean) =>
    request.post<Answer>({ url: `${base}/assistant`, data: { question, useModel } }),
  drafts: () => request.get<Draft[]>({ url: `${base}/drafts` }),
  saveDraft: (data: Pick<Draft, 'title' | 'content' | 'workOrderId'>) =>
    request.post<number>({ url: `${base}/draft`, data }),
  convertDraft: (id: number) =>
    request.post<number>({ url: `${base}/draft-convert`, params: { id } })
}
