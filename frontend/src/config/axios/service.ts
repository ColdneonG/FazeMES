import axios, { AxiosError, AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import qs from 'qs'
import { config } from '@/config/axios/config'
import {
  getAccessToken,
  getRefreshToken,
  getTenantId,
  getVisitTenantId,
  removeToken,
  setToken
} from '@/utils/auth'
import errorCode from './errorCode'

import { resetRouter } from '@/router'
import { deleteUserCache } from '@/hooks/web/useCache'
import { ApiEncrypt } from '@/utils/encrypt'
import { ensureFileMediaSession, setFileMediaSessionTokenRefresh } from '@/utils/fileMediaSession'

const tenantEnable = import.meta.env.VITE_APP_TENANT_ENABLE
const { result_code, base_url, request_timeout } = config

// 需要忽略的提示。忽略后，自动 Promise.reject('error')
const ignoreMsgs = [
  '无效的刷新令牌', // 刷新令牌被删除时，不用提示
  '刷新令牌已过期' // 使用刷新令牌，刷新获取新的访问令牌时，结果因为过期失败，此时需要忽略。否则，会导致继续 401，无法跳转到登出界面
]
// 是否显示重新登录
export const isRelogin = { show: false }
// Axios 无感知刷新令牌，参考 https://www.dashingdog.cn/article/11 与 https://segmentfault.com/a/1190000020210980 实现
// Share refresh work with business requests and the idle media-cookie renewal timer.
let refreshing: Promise<void> | undefined
// 请求白名单，无须 token 的接口
const whiteList: string[] = ['/login', '/refresh-token']

// 创建axios实例
const service: AxiosInstance = axios.create({
  baseURL: base_url, // api 的 base_url
  timeout: request_timeout, // 请求超时时间
  withCredentials: false, // 禁用 Cookie 等信息
  // 自定义参数序列化函数
  paramsSerializer: (params) => {
    return qs.stringify(params, { allowDots: true })
  }
})

// request拦截器
service.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    // 是否需要设置访问租户；该配置仅供前端请求拦截器使用，不作为请求头发送
    const isVisitTenant = config.headers.get('isVisitTenant') !== false
    config.headers.delete('isVisitTenant')
    // 是否需要设置 token；命中白名单的接口（如 /login）不带 token
    let isToken = (config!.headers || {}).isToken !== false
    if (isToken && whiteList.some((v) => config.url?.includes(v))) {
      isToken = false
    }
    if (getAccessToken() && isToken) {
      config.headers.Authorization = 'Bearer ' + getAccessToken() // 让每个请求携带自定义 token
      // A business response may immediately mount an image/SOP. Finish the matching browser
      // media session first; the native-fetch helper is independent of this axios queue.
      await ensureFileMediaSession(getAccessToken(), getTenantId()).catch(() => undefined)
    }
    // 设置租户
    if (tenantEnable && tenantEnable === 'true') {
      const tenantId = getTenantId()
      if (tenantId) config.headers['tenant-id'] = tenantId
      // 只有登录时，才设置 visit-tenant-id 访问租户
      const visitTenantId = getVisitTenantId()
      if (config.headers.Authorization && visitTenantId && isVisitTenant) {
        config.headers['visit-tenant-id'] = visitTenantId
      }
    }
    const method = config.method?.toUpperCase()
    // 防止 GET 请求缓存
    if (method === 'GET') {
      config.headers['Cache-Control'] = 'no-cache'
      config.headers['Pragma'] = 'no-cache'
    }
    // 自定义参数序列化函数
    else if (method === 'POST') {
      const contentType = config.headers['Content-Type'] || config.headers['content-type']
      if (contentType === 'application/x-www-form-urlencoded') {
        if (config.data && typeof config.data !== 'string') {
          config.data = qs.stringify(config.data)
        }
      }
    }
    // 是否 API 加密
    if ((config!.headers || {}).isEncrypt && !(config!.headers || {}).isEncrypted) {
      try {
        // 加密请求数据
        if (config.data) {
          config.data = ApiEncrypt.encryptRequest(config.data)
          // 设置加密标识头
          config.headers[ApiEncrypt.getEncryptHeader()] = 'true'
        }
      } catch (error) {
        console.error('请求数据加密失败')
        throw error
      }
    }
    return config
  },
  (error: AxiosError) => {
    // Do something with request error
    return Promise.reject(error)
  }
)

// response 拦截器
service.interceptors.response.use(
  async (response: AxiosResponse<any>) => {
    let { data } = response
    const config = response.config
    if (!data) {
      // 返回“[HTTP]请求没有返回值”;
      throw new Error()
    }

    // 检查是否需要解密响应数据
    const encryptHeader = ApiEncrypt.getEncryptHeader()
    const isEncryptResponse =
      response.headers[encryptHeader] === 'true' ||
      response.headers[encryptHeader.toLowerCase()] === 'true'
    if (isEncryptResponse && typeof data === 'string') {
      try {
        // 解密响应数据
        data = ApiEncrypt.decryptResponse(data)
      } catch (error) {
        console.error('响应数据解密失败')
        throw new Error('响应数据解密失败: ' + (error as Error).message)
      }
    }

    const { t } = useI18n()
    // 未设置状态码则默认成功状态
    // 二进制数据则直接返回，例如说 Excel 导出
    if (
      response.request.responseType === 'blob' ||
      response.request.responseType === 'arraybuffer'
    ) {
      // 注意：如果导出的响应为 json，说明可能失败了，不直接返回进行下载
      if (response.data.type !== 'application/json') {
        return response.data
      }
      data = await new Response(response.data).json()
    }
    const code = data.code ?? result_code
    // 获取错误信息
    const msg = data.msg || errorCode[code] || errorCode['default']
    if (ignoreMsgs.indexOf(msg) !== -1) {
      // 如果是忽略的错误码，直接返回 msg 异常
      return Promise.reject(msg)
    } else if (code === 401) {
      const retryConfig = config as typeof config & { authRetried?: boolean }
      if (retryConfig.authRetried) return handleAuthorized()
      try {
        await refreshAccessToken()
      } catch {
        return handleAuthorized()
      }
      await ensureFileMediaSession(getAccessToken(), getTenantId()).catch(() => undefined)
      retryConfig.authRetried = true
      if (config.headers?.isEncrypt) config.headers.isEncrypted = true
      return service(retryConfig)
    } else if (code === 500) {
      ElMessage.error(t('sys.api.errMsg500'))
      return Promise.reject(new Error(msg))
    } else if (code === 901) {
      ElMessage.error(t('sys.api.errMsg901'))
      return Promise.reject(new Error(msg))
    } else if (code !== 0 && code !== 200) {
      if (msg === '无效的刷新令牌') {
        // hard coding：忽略这个提示，直接登出
        return handleAuthorized()
      } else {
        ElNotification.error({ title: msg })
      }
      return Promise.reject(new Error(msg))
    } else {
      return data
    }
  },
  (error: AxiosError) => {
    let { message } = error
    const { t } = useI18n()
    if (message === 'Network Error') {
      message = t('sys.api.errorMessage')
    } else if (message.includes('timeout')) {
      message = t('sys.api.apiTimeoutMessage')
    } else if (message.includes('Request failed with status code')) {
      message = t('sys.api.apiRequestFailed') + message.substr(message.length - 3)
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

const refreshAccessToken = (): Promise<void> => {
  if (refreshing) return refreshing
  const credential = getRefreshToken()
  if (!credential) return Promise.reject(new Error('无可用刷新令牌'))
  refreshing = (async () => {
    const response = await axios.post(
      base_url + '/system/auth/refresh-token?refreshToken=' + encodeURIComponent(credential),
      undefined,
      { headers: { 'tenant-id': getTenantId() } }
    )
    const result = response.data
    if (
      ![0, 200].includes(result.code ?? 0) ||
      !result.data?.accessToken ||
      !result.data?.refreshToken
    ) {
      throw new Error('登录续期失败')
    }
    // A response for a previous account must never revive that session after logout/switch.
    if (getRefreshToken() !== credential) throw new Error('登录身份已变化')
    setToken(result.data)
  })().finally(() => {
    refreshing = undefined
  })
  return refreshing
}
setFileMediaSessionTokenRefresh(async () => {
  await refreshAccessToken()
  return { accessToken: getAccessToken(), tenantId: getTenantId() }
})
const handleAuthorized = () => {
  const { t } = useI18n()
  if (!isRelogin.show) {
    // 如果已经到登录页面则不进行弹窗提示
    if (window.location.href.includes('login')) {
      return
    }
    isRelogin.show = true
    ElMessageBox.confirm(t('sys.api.timeoutMessage'), t('common.confirmTitle'), {
      showCancelButton: false,
      closeOnClickModal: false,
      showClose: false,
      closeOnPressEscape: false,
      confirmButtonText: t('login.relogin'),
      type: 'warning'
    }).then(() => {
      resetRouter() // 重置静态路由表
      deleteUserCache() // 删除用户缓存
      removeToken()
      isRelogin.show = false
      // 干掉token后再走一次路由让它过router.beforeEach的校验
      window.location.href = window.location.href
    })
  }
  return Promise.reject(t('sys.api.timeoutMessage'))
}
export { service }
