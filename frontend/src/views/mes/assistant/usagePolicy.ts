import type {
  UsageLimits,
  UsagePolicy,
  UsagePolicySave,
  UsagePriceCard,
  UsageReconcile,
  UsageRow,
  ModelPurpose,
  UsageStatus,
  UsagePageParams
} from '@/api/mes/assistant/usage'

export const purposeNames: Record<ModelPurpose, string> = {
  KNOWLEDGE_CHAT: '知识问答',
  BUSINESS_INSIGHT: '业务模型分析',
  MODEL_CONNECTION_TEST: '模型连接测试',
  JEV_TRIAGE: 'Jev异常分流',
  JEV_CONNECTION_TEST: 'Jev连接测试'
}
export const statusNames: Record<UsageStatus, string> = {
  NEW: '新建',
  RESERVED: '已预留',
  DISPATCHED: '已发送',
  SETTLED: '已结算',
  DENIED: '已拒绝',
  CANCELLED: '已取消',
  RECONCILED: '已核定'
}
export const measured = (value: string | null | undefined) => (value == null ? '待核定' : value)
export const limitText = (value: string | null | undefined) => (value == null ? '未启用' : value)
const bigintMax = 9223372036854775807n
export function integer(value: string, positive = false): string {
  if (
    typeof value !== 'string' ||
    !/^(0|[1-9]\d*)$/.test(value) ||
    value.length > 19 ||
    BigInt(value) > bigintMax ||
    (positive && BigInt(value) === 0n)
  )
    throw new Error(
      positive
        ? '配额须为正整数，且不超过BIGINT范围'
        : 'Token须为非负整数字符串，且不超过BIGINT范围'
    )
  return value
}
export function money(value: string): string {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,15})(\.\d{1,8})?$/.test(value))
    throw new Error('金额须为非负十进制文本，最多16位整数和8位小数')
  return value
}
const limits = (value: UsageLimits): UsageLimits => {
  if (
    !Number.isInteger(value.maxConcurrency) ||
    value.maxConcurrency < 1 ||
    value.maxConcurrency > 128 ||
    !Number.isInteger(value.callsPerMinute) ||
    value.callsPerMinute < 1 ||
    value.callsPerMinute > 10000
  )
    throw new Error('并发数须在1至128，每分钟调用数须在1至10000')
  return {
    dayCalls: integer(value.dayCalls, true),
    monthCalls: integer(value.monthCalls, true),
    dayTokens: integer(value.dayTokens, true),
    monthTokens: integer(value.monthTokens, true),
    dayAmount: value.dayAmount == null ? null : money(value.dayAmount),
    monthAmount: value.monthAmount == null ? null : money(value.monthAmount),
    maxConcurrency: value.maxConcurrency,
    callsPerMinute: value.callsPerMinute
  }
}
export function policyRequest(form: UsagePolicySave): UsagePolicySave {
  const reason = form.reason.trim()
  if (!reason || reason.length > 1000) throw new Error('请填写1至1000字的变更依据')
  if (!Number.isSafeInteger(form.expectedVersion) || form.expectedVersion < 0)
    throw new Error('预算版本无效，请重新加载')
  if (typeof form.enabled !== 'boolean') throw new Error('请选择是否启用预算')
  const seen = new Set<string>()
  if (form.rates.length > 100) throw new Error('价格卡最多100项')
  const rates = form.rates.map((r): UsagePriceCard => {
    if (
      (r.channel === 'CHAT' && !['openai', 'ollama'].includes(r.provider)) ||
      (r.channel === 'JEV' && r.provider !== 'typesafe') ||
      !['CHAT', 'JEV'].includes(r.channel)
    )
      throw new Error('价格卡通道与服务类型不匹配')
    if (!/^[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,199}$/.test(r.model))
      throw new Error('价格卡须填写已配置的准确模型名称')
    const key = `${r.channel}\n${r.provider}\n${r.model}`
    if (seen.has(key)) throw new Error('同一通道、服务和模型只能配置一张价格卡')
    seen.add(key)
    return {
      channel: r.channel,
      provider: r.provider,
      model: r.model,
      inputPerMillion: r.channel === 'CHAT' ? money(r.inputPerMillion ?? '') : null,
      outputPerMillion: r.channel === 'CHAT' ? money(r.outputPerMillion ?? '') : null,
      perCallReservationAmount: r.channel === 'JEV' ? money(r.perCallReservationAmount ?? '') : null
    }
  })
  return {
    expectedVersion: form.expectedVersion,
    enabled: form.enabled,
    tenantLimits: limits(form.tenantLimits),
    defaultUserLimits: limits(form.defaultUserLimits),
    rates,
    reason
  }
}
const emptyLimits = (): UsageLimits => ({
  dayCalls: '',
  monthCalls: '',
  dayTokens: '',
  monthTokens: '',
  dayAmount: null,
  monthAmount: null,
  maxConcurrency: 1,
  callsPerMinute: 1
})
export function policyForm(policy: UsagePolicy): UsagePolicySave {
  return {
    expectedVersion: policy.version,
    enabled: policy.enabled,
    tenantLimits: policy.tenantLimits ? { ...policy.tenantLimits } : emptyLimits(),
    defaultUserLimits: policy.defaultUserLimits ? { ...policy.defaultUserLimits } : emptyLimits(),
    rates: policy.rates.map((r) => ({ ...r })),
    reason: ''
  }
}
export function reconcileRequest(
  row: UsageRow,
  action: UsageReconcile['action'],
  input: string,
  output: string,
  amount: string,
  reason: string
): UsageReconcile {
  if (!row.canReconcile) throw new Error('此调用尚不可核定，请刷新后重试')
  if (!['CLOSE_AS_UNKNOWN', 'CONFIRM_ACTUAL', 'CONFIRM_NOT_DISPATCHED'].includes(action))
    throw new Error('请选择核定方式')
  reason = reason.trim()
  if (!reason || reason.length > 1000) throw new Error('请填写1至1000字的核定依据')
  const data: UsageReconcile = {
    usageId: row.id,
    expectedVersion: row.version,
    action,
    inputTokens: null,
    outputTokens: null,
    confirmedAmount: null,
    reason
  }
  if (action === 'CONFIRM_ACTUAL') {
    if (row.channel === 'CHAT') {
      data.inputTokens = integer(input)
      data.outputTokens = integer(output)
    } else if (input || output) {
      if (!input || !output) throw new Error('实际Token须同时提供输入与输出')
      data.inputTokens = integer(input)
      data.outputTokens = integer(output)
    }
    if (
      data.inputTokens != null &&
      data.outputTokens != null &&
      BigInt(data.inputTokens) + BigInt(data.outputTokens) > bigintMax
    )
      throw new Error('输入与输出Token合计超过有效范围')
    if (amount) data.confirmedAmount = money(amount)
    if (data.inputTokens == null && data.confirmedAmount == null)
      throw new Error('请提供至少一项可核实的实际用量')
  }
  return data
}
export function pageRequest(
  filters: { from: string; to: string; purpose: string; status: string; userId: string },
  pageNo: number,
  pageSize: number
): UsagePageParams {
  const params: UsagePageParams = { pageNo, pageSize }
  if (
    !Number.isSafeInteger(pageNo) ||
    pageNo < 1 ||
    !Number.isInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > 100
  )
    throw new Error('分页参数无效')
  const date = (s: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(s) &&
    Number.isFinite(Date.parse(s)) &&
    new Date(s).toISOString().slice(0, 10) === s
  if (filters.from || filters.to) {
    if (!date(filters.from) || !date(filters.to))
      throw new Error('请选择有效的开始日期和结束日期（不含结束日）')
    const days = (Date.parse(filters.to) - Date.parse(filters.from)) / 86400000
    if (days <= 0 || days > 366) throw new Error('审计日期范围须在1至366天')
    params.from = filters.from
    params.to = filters.to
  }
  if (filters.purpose) {
    if (!Object.hasOwn(purposeNames, filters.purpose)) throw new Error('调用用途无效')
    params.purpose = filters.purpose as ModelPurpose
  }
  if (filters.status) {
    if (!Object.hasOwn(statusNames, filters.status)) throw new Error('审计状态无效')
    params.status = filters.status as UsageStatus
  }
  if (filters.userId) {
    integer(filters.userId, true)
    const userId = Number(filters.userId)
    if (!Number.isSafeInteger(userId)) throw new Error('用户ID超过页面可精确处理的范围')
    params.userId = userId
  }
  return params
}
