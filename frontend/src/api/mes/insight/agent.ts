import request from '@/config/axios'

export interface AgentOperation {
  kind: 'CREATE_INCIDENT' | 'MOVE_INCIDENT'
  title?: string
  description?: string
  orderCode?: string
  category?: string
  incidentId?: number
  status?: string
  ownerId?: number
  rootCause?: string
  correctiveAction?: string
  evidence?: string
  note?: string
}
export interface AgentTable {
  kind: 'AGENT_TABLE'
  module: 'WORKORDER' | 'DEVICE'
  title: string
  list: Record<string, unknown>[]
  total: number
  basis: string
  asOf: string
  report: boolean
}
export interface AgentPreview {
  id: string
  token: string
  payload: Record<string, unknown>
  expiresAt: string
  status: string
}
export interface AgentReceipt {
  id: string
  status: string
  businessId: number
  message: string
  executedAt: string
}
export const AgentApi = {
  prepare: (data: AgentOperation, turnId: number) =>
    request.post<AgentPreview>({ url: '/mes/insight/agent/prepare', data, params: { turnId } }),
  execute: (data: Pick<AgentPreview, 'id' | 'token'>) =>
    request.post<AgentReceipt>({ url: '/mes/insight/agent/execute', data }),
  report: (turnId: number) =>
    request.download<Blob>({ url: '/mes/insight/agent/report', params: { turnId } }),
  evidence: (turnId: number, pageNo: number) =>
    request.get<AgentTable>({
      url: '/mes/insight/conversation/evidence',
      params: { turnId, pageNo, pageSize: 20 }
    })
}
