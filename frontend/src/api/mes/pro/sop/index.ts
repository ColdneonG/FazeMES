import request from '@/config/axios'

export interface SopParameter {
  key: string
  label: string
  required: boolean
  min?: number
  max?: number
  unit?: string
}

export interface SopStep {
  id?: string
  title: string
  instruction: string
  required: boolean
  minViewSeconds: number
  photoRequired: boolean
  attachments: string[]
  parameters: SopParameter[]
}

export interface SopDocument {
  id: number
  code: string
  name: string
  itemId: number
  processId: number
}

export interface SopVersion {
  id?: number
  documentId: number
  versionNo: string
  title: string
  changeNote?: string
  status?: string
  modelUrl: string
  attachments: string[]
  steps: SopStep[]
  submittedBy?: number
  reviewedBy?: number
  publishedBy?: number
  reviewComment?: string
  disableReason?: string
}

export interface SopRecord {
  stepId: string
  viewedAt?: string | number
  viewedBy?: number
  confirmedAt?: string | number
  confirmedBy?: number
  parametersJson?: string
  photosJson?: string
}

export interface SopTaskPackage {
  taskId: number
  required: boolean
  documentId?: number
  versionId?: number
  versionNo?: string
  title?: string
  modelUrl?: string
  attachments: string[]
  steps: SopStep[]
  records: SopRecord[]
}

export const SopApi = {
  documents: (keyword?: string): Promise<SopDocument[]> =>
    request.get({ url: '/mes/sop/document/list', params: { keyword } }),
  createDocument: (data: Omit<SopDocument, 'id'>): Promise<number> =>
    request.post({ url: '/mes/sop/document/create', data }),
  versions: async (documentId: number): Promise<SopVersion[]> => {
    const rows = await request.get({ url: '/mes/sop/version/list', params: { documentId } })
    return rows.map((row: any) => ({
      ...row,
      steps: JSON.parse(row.stepsJson || '[]'),
      attachments: JSON.parse(row.attachmentsJson || '[]')
    }))
  },
  saveVersion: (data: SopVersion): Promise<number> =>
    request.post({ url: '/mes/sop/version/save', data }),
  action: (id: number, action: string, comment?: string): Promise<boolean> =>
    request.post({ url: `/mes/sop/version/${action}`, data: { id, comment } }),
  task: (taskId: number): Promise<SopTaskPackage> =>
    request.get({ url: '/mes/sop/task', params: { taskId } }),
  view: (taskId: number, stepId: string): Promise<boolean> =>
    request.post({ url: '/mes/sop/task/view', data: { taskId, stepId } }),
  confirm: (
    taskId: number,
    stepId: string,
    parameters: Record<string, number>,
    photos: string[]
  ): Promise<boolean> =>
    request.post({ url: '/mes/sop/task/confirm', data: { taskId, stepId, parameters, photos } })
}
