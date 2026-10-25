const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '../src/views/mes/pro/task/scheduleReview.ts'), 'utf8')
const moduleState = { exports: {} }
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
} }).outputText, { module: moduleState, exports: moduleState.exports })
const { reviewScheduleChanges } = moduleState.exports
const check = (overrides = {}) => ({
  workstationId: 10, startTime: '2026-11-02T08:00:00', endTime: '2026-11-02T16:00:00',
  resourceAvailable: true, blockingReasons: [], warnings: [], notes: [], ...overrides
})

test('unavailable planned resources block saving even without time conflicts', async () => {
  const errors = []
  let confirmed = false
  const accepted = await reviewScheduleChanges([{ id: 1 }], async () => check({
    resourceAvailable: false, blockingReasons: ['工作站所属车间已停用']
  }), async () => { confirmed = true }, (error) => errors.push(error))
  assert.equal(accepted, false)
  assert.equal(confirmed, false)
  assert.match(errors[0], /车间已停用/)
})

test('a batch checks the proposed intervals against each other before any save', async () => {
  const events = []
  const changes = [{ id: 1 }, { id: 2 }]
  const accepted = await reviewScheduleChanges(changes, async ({ id }) => {
    events.push(`check ${id}`)
    return check()
  }, async (message) => {
    events.push('confirm')
    assert.match(message, /任务 1 与 2.*时间重叠/)
  }, assert.fail)
  assert.equal(accepted, true)
  assert.deepEqual(events, ['check 1', 'check 2', 'confirm'])
})

test('adjacent half-open intervals and different workstations need no override', async () => {
  const results = [check(), check({ startTime: '2026-11-02T16:00:00', endTime: '2026-11-03T00:00:00' }),
    check({ workstationId: 11 })]
  assert.equal(await reviewScheduleChanges([{ id: 1 }, { id: 2 }, { id: 3 }],
    async () => results.shift(), async () => assert.fail('no conflicts'), assert.fail), true)
})

test('cancelling a time-conflict confirmation retains changes without saving', async () => {
  const accepted = await reviewScheduleChanges([{ id: 1 }], async () => check({ warnings: ['前序任务晚于本次开始'] }),
    async () => { throw new Error('cancel') }, assert.fail)
  assert.equal(accepted, false)
})

test('a failed check cannot be treated as permission to save', async () => {
  await assert.rejects(reviewScheduleChanges([{ id: 1 }], async () => { throw new Error('network') },
    async () => assert.fail('no confirmation'), assert.fail), /network/)
})

test('batch predecessor checks use the proposed end on another workstation', async () => {
  let confirmations = 0
  const results = [check({ workstationId: 10, startTime: '2026-11-02T16:00:00', endTime: '2026-11-03T00:00:00' }),
    check({ workstationId: 11, startTime: '2026-11-02T16:00:00', endTime: '2026-11-03T00:00:00',
      predecessors: [{ id: 1, code: 'PREVIOUS', endTime: '2026-11-02T16:00:00' }] })]
  assert.equal(await reviewScheduleChanges([{ id: 1 }, { id: 2 }], async () => results.shift(), async (message) => {
    confirmations++
    assert.match(message, /PREVIOUS.*结束晚于本任务开始/)
  }, assert.fail), true)
  assert.equal(confirmations, 1)
})

test('moving a predecessor earlier resolves its old conflict in the same batch', async () => {
  const results = [check(), check({ workstationId: 11, startTime: '2026-11-02T16:00:00', endTime: '2026-11-03T00:00:00',
    predecessors: [{ id: 1, code: 'PREVIOUS', endTime: '2026-11-03T00:00:00' }] })]
  assert.equal(await reviewScheduleChanges([{ id: 1 }, { id: 2 }], async () => results.shift(),
    async () => assert.fail('resolved predecessor'), assert.fail), true)
})
