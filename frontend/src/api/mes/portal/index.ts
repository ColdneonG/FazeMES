import axios from 'axios'
import request from '@/config/axios'

export interface PortalAccount {
  id?: number
  clientId?: number
  clientName?: string
  username: string
  displayName: string
  enabled: boolean
  expireTime?: string
  lockedUntil?: string
  failedAttempts?: number
  password?: string
}
export interface PortalProduct {
  productId: number
  productCode: string
  productName: string
  specification?: string
  unitName?: string
  description: string
  enabled?: boolean
  referenceSalePrice?: string | null
  currency?: 'CNY'
  taxBasis?: 'EXCLUSIVE'
}
export interface PortalRequestLine {
  productId: number
  productCode?: string
  productName?: string
  specification?: string
  unitName?: string
  quantity: number
}
export interface PortalOrderRequest {
  id: number
  code: string
  clientName?: string
  deliveryDate: string
  remark: string
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED'
  reviewNote: string
  createTime: string
  reviewTime?: string
  items: PortalRequestLine[]
}
export interface PortalOrder {
  id: number
  code: string
  name: string
  productName: string
  specification: string
  quantity: number
  quantityProduced: number
  requestDate?: string
  finishDate?: string
  status: number
}
export interface PortalIdentity {
  displayName: string
  clientName: string
}
interface PortalSession {
  token: string
  expiresAt: string
  account: PortalIdentity
}
const sessionKey = 'fazemes.customer-portal.session'
export const readPortalSession = (): PortalSession | undefined => {
  try {
    const session = JSON.parse(sessionStorage.getItem(sessionKey) || 'null') as PortalSession | null
    if (session && session.token && new Date(session.expiresAt).getTime() > Date.now())
      return session
  } catch {
    // Invalid local state is discarded; the server remains the authority for every request.
  }
  sessionStorage.removeItem(sessionKey)
  return undefined
}
export const clearPortalSession = () => sessionStorage.removeItem(sessionKey)

// An independent client: no administrator bearer token, visit-tenant header or admin refresh flow.
const portal = axios.create({
  baseURL: `${import.meta.env.VITE_BASE_URL || ''}/app-api/mes/customer-portal`,
  timeout: 30000,
  withCredentials: false
})
portal.interceptors.request.use((config) => {
  const session = readPortalSession()
  if (session && config.url !== '/login') config.headers.set('X-Portal-Token', session.token)
  return config
})
const portalCall = async <T>(method: 'GET' | 'POST', url: string, data?: unknown): Promise<T> => {
  const response = await portal.request<{ code: number; msg: string; data: T }>({
    method,
    url,
    data
  })
  if (response.data.code !== 0) {
    if (response.data.code === 401) clearPortalSession()
    throw new Error(response.data.msg || '客户服务请求失败')
  }
  return response.data.data
}
export const CustomerPortalApi = {
  login: async (data: { tenantId: number; username: string; password: string }) => {
    const session = await portalCall<PortalSession>('POST', '/login', data)
    sessionStorage.setItem(sessionKey, JSON.stringify(session))
    return session
  },
  me: () => portalCall<PortalIdentity>('GET', '/me'),
  logout: async () => {
    try {
      await portalCall('POST', '/logout')
    } finally {
      clearPortalSession()
    }
  },
  catalog: () => portalCall<PortalProduct[]>('GET', '/catalog'),
  orders: () => portalCall<PortalOrder[]>('GET', '/orders'),
  requests: () => portalCall<PortalOrderRequest[]>('GET', '/requests'),
  submit: (data: {
    requestKey: string
    deliveryDate: string
    remark: string
    items: { productId: number; quantity: number }[]
  }) => portalCall<{ id: number; code: string; status: string }>('POST', '/requests', data)
}
export const PortalAdminApi = {
  accounts: () => request.get<PortalAccount[]>({ url: '/mes/portal/accounts' }),
  saveAccount: (data: PortalAccount) =>
    request.post<number>({ url: '/mes/portal/account/save', data }),
  clients: () =>
    request.get<{ id: number; code: string; name: string }[]>({ url: '/mes/portal/clients' }),
  products: () =>
    request.get<{ id: number; code: string; name: string; specification?: string }[]>({
      url: '/mes/portal/products'
    }),
  catalog: () => request.get<PortalProduct[]>({ url: '/mes/portal/catalog' }),
  saveCatalog: (data: { productId: number; description: string; enabled: boolean }) =>
    request.post({ url: '/mes/portal/catalog/save', data }),
  requests: () => request.get<PortalOrderRequest[]>({ url: '/mes/portal/requests' }),
  review: (data: { id: number; status: string; reviewNote: string }) =>
    request.post({ url: '/mes/portal/request/review', data })
}
