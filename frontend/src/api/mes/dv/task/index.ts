import request from '@/config/axios'
export interface DvTaskLine {
  id: number
  subjectName: string
  content: string
  standard: string
  result?: number
  description?: string
}
export interface DvTask {
  id: number
  machineryName: string
  planName: string
  dueTime: number
  status: number
  userId?: number
  recordId?: number
  repairId?: number
  lines?: DvTaskLine[]
}
export const DvTaskApi = {
  page: (params: any) => request.get({ url: '/mes/dv/task/page', params }),
  get: (id: number): Promise<DvTask> => request.get({ url: '/mes/dv/task/get', params: { id } }),
  claim: (id: number) => request.put({ url: '/mes/dv/task/claim', params: { id } }),
  complete: (data: any) => request.put({ url: '/mes/dv/task/complete', data })
}
