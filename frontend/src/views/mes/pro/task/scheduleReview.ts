import type { ProTaskVO, ScheduleCheck } from '@/api/mes/pro/task'

type Change = Partial<ProTaskVO>
const timestamp = (value: string | number): number =>
  typeof value === 'number' || /^\d+$/.test(value) ? Number(value) : new Date(value).getTime()

/** Check every proposed change before any task is saved, including batch conflicts. */
export const reviewScheduleChanges = async (
  changes: Change[],
  check: (change: Change) => Promise<ScheduleCheck>,
  confirm: (message: string, title?: string) => Promise<unknown>,
  blocked: (message: string) => void
): Promise<boolean> => {
  const checks: ScheduleCheck[] = []
  for (const change of changes) checks.push(await check(change))
  const reasons = checks.flatMap((result) =>
    result.resourceAvailable ? [] : result.blockingReasons
  )
  if (checks.some((result) => !result.resourceAvailable)) {
    blocked(reasons.length ? [...new Set(reasons)].join('；') : '计划时刻资源不可用，请重新检查')
    return false
  }
  const warnings = new Set(checks.flatMap((result) => result.warnings))
  const proposedEnds = new Map(
    changes.flatMap((change, index) =>
      change.id === undefined ? [] : [[change.id, checks[index].endTime] as const]
    )
  )
  checks.forEach((result, index) => {
    for (const previous of result.predecessors ?? []) {
      const endTime = proposedEnds.get(previous.id) ?? previous.endTime
      if (timestamp(endTime) > timestamp(result.startTime)) {
        warnings.add(
          `任务 ${changes[index].id ?? index + 1} 的前序任务「${previous.code}」计划结束晚于本任务开始，请核对流转安排`
        )
      }
    }
  })
  for (let i = 0; i < checks.length; i++) {
    for (let j = i + 1; j < checks.length; j++) {
      const a = checks[i]
      const b = checks[j]
      if (
        a.workstationId === b.workstationId &&
        timestamp(a.startTime) < timestamp(b.endTime) &&
        timestamp(a.endTime) > timestamp(b.startTime)
      ) {
        warnings.add(
          `本次修改的任务 ${changes[i].id ?? i + 1} 与 ${changes[j].id ?? j + 1} 在同一工作站时间重叠`
        )
      }
    }
  }
  if (!warnings.size) return true
  try {
    await confirm([...warnings].join('\n') + '\n请核对资源与前后序安排后确认保存。', '排产时间冲突')
    return true
  } catch {
    return false
  }
}
