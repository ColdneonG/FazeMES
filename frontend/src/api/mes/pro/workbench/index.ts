import request from '@/config/axios'
import type { FeedbackSource } from '@/api/mes/pro/feedback'

export interface WorkstationOption {
  id: number
  code: string
  name: string
}

export interface WorkstationAvailability {
  workstationId: number
  checkedAt: string | number
  available: boolean
  reasons: string[]
  notes: string[]
}

export interface WorkbenchTask {
  id: number
  code: string
  name: string
  workstationId: number
  workstationName: string
  workOrderId: number
  workOrderCode: string
  workOrderName: string
  routeId: number
  processId: number
  processName: string
  itemId: number
  itemCode: string
  itemName: string
  itemSpecification?: string
  quantity: number
  producedQuantity: number
  startTime: string | number
  endTime: string | number
  checkFlag?: boolean
}

export interface WorkbenchContext {
  task: WorkbenchTask
  sops: { id: number; title: string; description?: string; url?: string; sort: number }[]
  approvers: { id: number; name: string }[]
}

export interface WorkbenchFeedback {
  taskId: number
  approveUserId: number
  feedbackQuantity?: number | string
  qualifiedQuantity?: number | string
  unqualifiedQuantity?: number | string
  remark?: string
  sources?: FeedbackSource[]
}

export const WorkbenchApi = {
  getWorkstations: (): Promise<WorkstationOption[]> =>
    request.get({ url: '/mes/pro/workbench/workstations' }),
  getTasks: (workstationId: number): Promise<WorkbenchTask[]> =>
    request.get({ url: '/mes/pro/workbench/tasks', params: { workstationId } }),
  getAvailability: (id: number): Promise<WorkstationAvailability> =>
    request.get({ url: '/mes/md-workstation/availability', params: { id } }),
  getMachines: (workstationId: number): Promise<WorkstationOption[]> =>
    request.get({ url: '/mes/pro/workbench/machines', params: { workstationId } }),
  getContext: (taskId: number): Promise<WorkbenchContext> =>
    request.get({ url: '/mes/pro/workbench/context', params: { taskId } }),
  submitFeedback: (data: WorkbenchFeedback): Promise<number> =>
    request.post({ url: '/mes/pro/workbench/feedback', data })
}
