import request from '@/config/axios'
export interface OnlineSession {
  id: number
  userId: number
  username: string
  nickname: string
  clientId: string
  createTime: number
  expiresTime: number
}
export const OnlineSessionApi = {
  page: (params: any) => request.get({ url: '/system/online-session/page', params }),
  revoke: (id: number): Promise<boolean> =>
    request.delete({ url: '/system/online-session/revoke', params: { id } })
}
