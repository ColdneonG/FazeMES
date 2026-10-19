import dayjs from 'dayjs'

// 后端默认返回毫秒时间戳，同时兼容历史接口的日期字符串。
export function formatAssistantTime(value: unknown): string {
  if (typeof value !== 'number' && typeof value !== 'string') return ''
  if (typeof value === 'string' && !value.trim()) return ''
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm') : ''
}
