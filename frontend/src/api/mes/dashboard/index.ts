import request from '@/config/axios'
export interface ProductionDashboard {
  produced: number
  qualified: number
  rejected: number
  target: number
  taskCount: number
  feedbackCount: number
  pendingInspection: number
  activeAndon: number
  shortageAvailable: boolean
  shortageWorkOrderCount?: number
  updatedAt: string
  date: string
  basis: string
  regionalDistribution: {
    cities: { name: string; value: number; coordinate: [number, number] }[]
    totalOrderCount: number
    locatedOrderCount: number
    unlocatedOrderCount: number
    basis: string
  }
  workshops: { id: number; name: string }[]
  workstations: { id: number; name: string; workshopId: number }[]
  trend: { day: string; produced: number; qualified: number }[]
  devices: { status: number; count: number }[]
  tasks: {
    id: number
    code: string
    name: string
    workstationId: number
    workOrderId: number
    quantity: number
    producedQuantity: number
    status: number
    startTime: string
    endTime: string
  }[]
  workOrders: {
    id: number
    code: string
    name: string
    quantity: number
    producedQuantity: number
    status: number
  }[]
}
export const DashboardApi = {
  production: (params: { workshopId?: number; workstationId?: number; date: string }) =>
    request.get<ProductionDashboard>({ url: '/mes/dashboard/production', params })
}
