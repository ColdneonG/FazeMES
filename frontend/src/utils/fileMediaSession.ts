/** Browser images/PDFs cannot attach Authorization headers. Use a short-lived HttpOnly media cookie.
 * File URLs in business records remain unchanged; neither access nor refresh credentials go in URLs.
 */
let credentials: { accessToken: string; tenantId: string } | undefined
let renewal: ReturnType<typeof setTimeout> | undefined
let pending: Promise<void> | undefined
let validUntil = 0
let generation = 0
let serial: Promise<void> = Promise.resolve()

const endpoint = () =>
  `${import.meta.env.VITE_BASE_URL || ''}${import.meta.env.VITE_API_URL || '/admin-api'}/infra/file/media-session`

const schedule = (version: number, milliseconds: number) => {
  if (renewal) clearTimeout(renewal)
  renewal = setTimeout(() => {
    if (version !== generation || !credentials) return
    const { accessToken, tenantId } = credentials
    ensureFileMediaSession(accessToken, tenantId).catch(() => {
      if (version === generation) schedule(version, 30_000)
    })
  }, milliseconds)
}

export const ensureFileMediaSession = (accessToken: string, tenantId: unknown): Promise<void> => {
  const tenant = String(tenantId ?? '')
  if (!accessToken || !tenant) return Promise.resolve()
  if (credentials?.accessToken !== accessToken || credentials.tenantId !== tenant) {
    generation++
    validUntil = 0
    pending = undefined
    if (renewal) clearTimeout(renewal)
    credentials = { accessToken, tenantId: tenant }
  }
  if (pending) return pending
  if (validUntil > Date.now() + 90_000) return Promise.resolve()
  const version = generation
  // Serialize cookie mutations. A late response from an old account must never overwrite the
  // cookie of the next account or recreate one after logout.
  pending = serial
    .catch(() => undefined)
    .then(async () => {
      if (version !== generation) return
      const response = await fetch(endpoint(), {
        method: 'POST',
        credentials: 'include',
        signal: AbortSignal.timeout(8_000),
        headers: { Authorization: `Bearer ${accessToken}`, 'tenant-id': tenant }
      })
      const result = await response.json()
      if (!response.ok || result.code !== 0 || !result.data) throw new Error('附件会话暂不可用')
      if (version !== generation) return
      const expires = Date.parse(result.data)
      if (!Number.isFinite(expires) || expires <= Date.now()) throw new Error('附件会话有效期无效')
      validUntil = expires
      schedule(version, Math.max(15_000, expires - Date.now() - 90_000))
    })
    .catch((error) => {
      if (version === generation) schedule(version, 30_000)
      throw error
    })
    .finally(() => {
      if (version === generation) pending = undefined
    })
  serial = pending
  return pending
}

export const clearFileMediaSession = (): void => {
  generation++
  credentials = undefined
  pending = undefined
  validUntil = 0
  if (renewal) clearTimeout(renewal)
  renewal = undefined
  // Clearing this read-only cookie also works after the main token has expired.
  serial = serial
    .catch(() => undefined)
    .then(async () => {
      await fetch(endpoint(), {
        method: 'DELETE',
        credentials: 'include',
        signal: AbortSignal.timeout(8_000)
      }).catch(() => undefined)
    })
}
