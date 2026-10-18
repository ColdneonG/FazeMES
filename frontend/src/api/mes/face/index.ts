import request from '@/config/axios'
import type { TokenType } from '@/api/login/types'
export interface FaceStatus {
  ready: boolean
  enrolled?: boolean
  comparison: string
  livenessSupported: boolean
  model?: string
}
const anonymous = { isToken: false }
export const FaceApi = {
  status: () => request.get<FaceStatus>({ url: '/mes/face/status' }),
  publicStatus: () =>
    request.get<FaceStatus>({ url: '/mes/face/login-status', headers: anonymous }),
  challenge: (username: string) =>
    request.post<{ challenge: string; expiresIn: number }>({
      url: '/mes/face/challenge',
      data: { username },
      headers: anonymous
    }),
  login: (username: string, challenge: string, imageBase64: string) =>
    request.post<TokenType>({
      url: '/mes/face/login',
      data: { username, challenge, imageBase64 },
      headers: anonymous
    }),
  enroll: (password: string, images: string[], consent: boolean) =>
    request.post({ url: '/mes/face/enroll', data: { password, images, consent } }),
  disable: (password: string) => request.post({ url: '/mes/face/disable', data: { password } })
}
