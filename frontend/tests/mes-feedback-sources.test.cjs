const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function evaluate(source, filename, dependencies = {}, globals = {}) {
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022
      }
    }).outputText,
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || (name.endsWith('.vue') ? {} : require(name)),
      ...globals
    },
    { filename }
  )
  return module.exports
}
const quantityFile = path.join(__dirname, '../src/views/mes/pro/feedback/sourceQuantities.ts')
const coreQuantityFile = path.join(__dirname, '../src/utils/mesQuantity.ts')
const coreQuantities = evaluate(fs.readFileSync(coreQuantityFile, 'utf8'), coreQuantityFile)
const quantities = evaluate(fs.readFileSync(quantityFile, 'utf8'), quantityFile, {
  '@/utils/mesQuantity': coreQuantities
})
const flush = async () => {
  await Promise.resolve()
  await vue.nextTick()
  await Promise.resolve()
}

function setupComponent(t, relativePath, props, dependencies = {}, extraGlobals = {}) {
  const filename = path.join(__dirname, '../src/views/mes', relativePath)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'feedback-source-regression' }).content
  const messages = []
  const mounted = []
  const disposed = []
  const component = evaluate(
    source,
    filename,
    {
      './sourceQuantities': quantities,
      '@/views/mes/pro/feedback/sourceQuantities': quantities,
      vue,
      ...dependencies
    },
    {
      ...vue,
      onMounted: (callback) => mounted.push(callback),
      onBeforeUnmount: (callback) => disposed.push(callback),
      useI18n: () => ({ t: (value) => value }),
      useMessage: () => ({
        warning: (value) => messages.push(value),
        success: () => {},
        confirm: async () => {}
      }),
      ...extraGlobals
    }
  ).default
  const events = []
  const scope = vue.effectScope()
  const state = scope.run(() =>
    component.setup(props, {
      expose: () => {},
      emit: (name, value) => {
        events.push([name, value])
        if (name === 'update:modelValue') props.modelValue = value
      }
    })
  )
  t.after(() => {
    disposed.forEach((dispose) => dispose())
    scope.stop()
  })
  return { state, messages, events, mounted, disposed }
}

const option = (id = 1, available = '10') => ({
  id,
  code: `FB${id}`,
  processName: '前序',
  qualified: available,
  used: '0',
  available,
  complete: true,
  legacy: false
})
const context = (options = [option()]) => ({
  firstProcess: false,
  required: true,
  complete: false,
  legacy: false,
  allocations: [],
  options
})

test('source quantities retain six places and large DECIMAL values without number arithmetic', () => {
  assert.equal(quantities.sumQuantities(0.1, 0.2), '0.3')
  assert.equal(
    quantities.sumQuantities('99999999999999.123456', '0.000001'),
    '99999999999999.123457'
  )
  for (const bad of ['-1', '0.0000001', '1e2', 'Infinity', '', '100000000000000']) {
    assert.equal(quantities.quantityUnits(bad), undefined)
  }
  const sources = [{ sourceFeedbackId: 1, quantity: '99999999999999.123456' }]
  assert.equal(
    quantities.sourceValidationError(
      sources,
      [option(1, sources[0].quantity)],
      sources[0].quantity,
      true
    ),
    ''
  )
})

test('source validation rejects overuse, duplicate source, missing evidence and unequal totals', () => {
  const sources = [
    { sourceFeedbackId: 1, quantity: '0.1' },
    { sourceFeedbackId: 2, quantity: '0.2' }
  ]
  const options = [option(1, '0.1'), option(2, '0.2')]
  assert.equal(quantities.sourceValidationError(sources, options, '0.3', true), '')
  assert.match(quantities.sourceValidationError(sources, options, '0.4', true), /合计/)
  assert.match(
    quantities.sourceValidationError([...sources, sources[0]], options, '0.4', true),
    /一次/
  )
  assert.match(
    quantities.sourceValidationError(sources, [option(1, '0.09'), options[1]], '0.3', true),
    /超过/
  )
  assert.match(quantities.sourceValidationError(sources, [], '0.3', true), /来源已变化/)
  assert.equal(quantities.sourceValidationError([], options, '0.3', false), '')
})

test('draft may save without sources but submit needs exact total; refresh preserves edits', async (t) => {
  const props = vue.reactive({
    taskId: 1,
    feedbackId: undefined,
    modelValue: [],
    quantity: '0.3',
    readonly: false
  })
  const { state, messages } = setupComponent(t, 'pro/feedback/FeedbackSources.vue', props, {
    '@/api/mes/pro/feedback': {
      ProFeedbackApi: {
        getSourceOptions: async () => context([option(1, '0.1'), option(2, '0.2')])
      }
    }
  })
  assert.equal(state.validate(), false, 'submit must wait for the current source context')
  await flush()
  assert.equal(state.validate(false), true)
  assert.equal(state.validate(), false)
  state.toggleSource(state.context.value.options[0], true)
  state.toggleSource(state.context.value.options[1], true)
  assert.equal(props.modelValue[0].quantity, '0.1')
  assert.equal(props.modelValue[1].quantity, '0.2')
  assert.equal(state.allocatedTotal.value, '0.3')
  assert.equal(state.validate(), true)
  state.updateQuantity(2, '0.1')
  await state.loadSources(false)
  assert.equal(props.modelValue[1].quantity, '0.1')
  assert.equal(state.validate(), false)
  assert.match(messages.at(-1), /合计/)
})

test('stale task responses cannot overwrite the new selection or enable submission', async (t) => {
  const pending = []
  const props = vue.reactive({
    taskId: 1,
    feedbackId: undefined,
    modelValue: [],
    quantity: '1',
    readonly: false
  })
  const { state } = setupComponent(t, 'pro/feedback/FeedbackSources.vue', props, {
    '@/api/mes/pro/feedback': {
      ProFeedbackApi: { getSourceOptions: () => new Promise((resolve) => pending.push(resolve)) }
    }
  })
  props.taskId = 2
  await vue.nextTick()
  pending[1]({ ...context([option(2)]), allocations: [{ sourceFeedbackId: 2, quantity: '1' }] })
  await flush()
  assert.equal(state.validate(), true)
  pending[0]({ ...context(), allocations: [{ sourceFeedbackId: 1, quantity: '1' }] })
  await flush()
  assert.equal(props.modelValue[0].sourceFeedbackId, 2)
  assert.equal(state.context.value.options[0].id, 2)
})

test('failed source request blocks draft and submit, while first process requires no allocation', async (t) => {
  let fail = true
  const props = vue.reactive({
    taskId: 1,
    feedbackId: undefined,
    modelValue: [],
    quantity: '1',
    readonly: false
  })
  const { state } = setupComponent(t, 'pro/feedback/FeedbackSources.vue', props, {
    '@/api/mes/pro/feedback': {
      ProFeedbackApi: {
        getSourceOptions: async () => {
          if (fail) throw new Error('offline')
          return { ...context([]), firstProcess: true }
        }
      }
    }
  })
  await flush()
  assert.equal(state.validate(false), false)
  assert.equal(state.validate(), false)
  fail = false
  await state.loadSources(false)
  assert.equal(state.validate(), true)
})

test('saved source evidence is loaded and historical missing evidence remains visible', async (t) => {
  const props = vue.reactive({
    taskId: 1,
    feedbackId: 5,
    modelValue: [],
    quantity: '1',
    readonly: true
  })
  const allocations = [
    {
      sourceFeedbackId: 1,
      sourceFeedbackCode: 'OLD1',
      quantity: '1',
      complete: false,
      legacy: true
    }
  ]
  const { state } = setupComponent(t, 'pro/feedback/FeedbackSources.vue', props, {
    '@/api/mes/pro/feedback': {
      ProFeedbackApi: {
        getSourceOptions: async () => ({ ...context(), complete: false, allocations })
      }
    }
  })
  await flush()
  assert.equal(props.modelValue[0].sourceFeedbackId, 1)
  assert.equal(state.hasIncompleteSource.value, true)
  assert.equal(state.context.value.complete, false)
})

test('workbench submits actual source allocations with the exact reported total', async (t) => {
  const calls = []
  let sourceValid = false
  const { state } = setupComponent(
    t,
    'pro/workbench/index.vue',
    {},
    {
      '@/api/mes/pro/task': { ProTaskApi: {} },
      '@/views/mes/utils/constants': { MesProTaskStatusEnum: { PREPARE: 0 } },
      '@/api/mes/pro/workbench': {
        WorkbenchApi: {
          getAvailability: async () => ({ available: true, reasons: [], notes: [] }),
          submitFeedback: async (data) => {
            calls.push(data)
            return 9
          },
          getTasks: async () => []
        }
      },
      '@/utils/formatTime': { formatDate: () => '' },
      '@/utils/permission': { checkPermi: () => true }
    },
    { useRouter: () => ({ push: () => {} }), useRoute: () => ({ query: {} }) }
  )
  state.stationId.value = 3
  state.context.value = {
    task: { id: 10, workstationId: 3, checkFlag: false },
    approvers: [],
    sops: []
  }
  state.formRef.value = { validate: async () => true }
  state.sourcesRef.value = { validate: () => sourceValid }
  Object.assign(state.form, {
    approveUserId: 1,
    qualifiedQuantity: 0.1,
    unqualifiedQuantity: 0.2,
    sources: [{ sourceFeedbackId: 8, quantity: '0.3' }]
  })
  await state.submit()
  assert.equal(calls.length, 0)
  sourceValid = true
  await state.submit()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].feedbackQuantity, '0.3')
  assert.equal(calls[0].sources[0].sourceFeedbackId, 8)
  assert.equal(calls[0].sources[0].quantity, '0.3')
})

test('ordinary report save preserves the full DECIMAL(20,6) in form and actual submitted payload', async (t) => {
  let payload
  const validationModes = []
  const { state } = setupComponent(
    t,
    'pro/feedback/FeedbackForm.vue',
    {},
    {
      '@/api/mes/pro/feedback': {
        ProFeedbackApi: {
          createFeedback: async (data) => {
            payload = data
            return 5
          }
        }
      },
      '@/api/mes/md/autocode/record': {},
      '@/api/mes/pro/route/process': {},
      '@/utils/dict': { DICT_TYPE: {}, getIntDictOptions: () => [] },
      '@/utils/auth': { getCurrentUserId: () => 1 },
      '@/views/mes/utils/constants': {
        MesProFeedbackStatusEnum: { PREPARE: 0 },
        MesProTaskStatusEnum: {},
        MesProWorkOrderStatusEnum: {},
        MesAutoCodeRuleCode: {}
      }
    }
  )
  state.formRef.value = { validate: async () => true }
  state.sourcesRef.value = {
    validate: (requireTotal) => {
      validationModes.push(requireTotal)
      return true
    }
  }
  state.checkFlag.value = false
  Object.assign(state.formData.value, {
    qualifiedQuantity: '99999999999999.123455',
    unqualifiedQuantity: '0.000001',
    sources: [{ sourceFeedbackId: 8, quantity: '99999999999999.123456' }]
  })
  state.handleQuantityChanged()
  await state.submitForm()
  assert.equal(payload.feedbackQuantity, '99999999999999.123456')
  assert.equal(payload.sources[0].sourceFeedbackId, 8)
  assert.equal(payload.sources[0].quantity, '99999999999999.123456')
  assert.equal(state.formData.value.feedbackQuantity, '99999999999999.123456')
  assert.equal(typeof state.formData.value.feedbackQuantity, 'string')
  assert.equal(validationModes[0], false, 'saving a draft may leave source allocation incomplete')
})

test('batch coverage never reuses an earlier batch response or treats lookup failure as complete', async (t) => {
  const pending = []
  const { state } = setupComponent(
    t,
    'qc/batchtrace/BatchTraceDetail.vue',
    {},
    {
      '@/api/mes/wm/batch': {
        BatchApi: {
          getTraceCoverage: () =>
            new Promise((resolve, reject) => pending.push({ resolve, reject }))
        }
      }
    }
  )
  const first = state.open({ code: 'A' })
  const second = state.open({ code: 'B' })
  pending[1].resolve({
    complete: false,
    legacyFeedbackIds: [1],
    truncated: false,
    scope: 'BATCH_POOL',
    notes: ['历史缺证']
  })
  await second
  pending[0].resolve({
    complete: true,
    legacyFeedbackIds: [],
    truncated: false,
    scope: 'BATCH_POOL',
    notes: []
  })
  await first
  assert.equal(state.coverage.value.complete, false)
  assert.match(state.coverageTitle.value, /不完整/)
  const third = state.open({ code: 'C' })
  pending[2].reject(new Error('offline'))
  await third
  assert.equal(state.coverage.value, undefined)
  assert.match(state.coverageError.value, /不能据此判定/)
  const fourth = state.loadCoverage()
  state.dialogVisible.value = false
  await vue.nextTick()
  pending[3].resolve({
    complete: true,
    legacyFeedbackIds: [],
    truncated: false,
    scope: 'BATCH_POOL',
    notes: []
  })
  await fourth
  assert.equal(state.coverage.value, undefined)
})
