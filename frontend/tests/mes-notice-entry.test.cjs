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
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    { module, exports: module.exports, require: (name) => dependencies[name] ||
      (name.endsWith('.vue') ? {} : require(name)), ...globals },
    { filename }
  )
  return module.exports
}
const constantsFile = path.join(__dirname, '../src/views/mes/utils/constants.ts')
const constants = evaluate(fs.readFileSync(constantsFile, 'utf8'), constantsFile)
const flush = async () => {
  for (let i = 0; i < 5; i++) { await Promise.resolve(); await vue.nextTick() }
}
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
function setup(t, name, dependencies = {}, globals = {}) {
  const filename = path.join(__dirname, '../src/views/mes/wm', name)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const messages = []
  const routerCalls = []
  const component = evaluate(compileScript(descriptor, { id: 'notice-entry' }).content, filename, {
    vue,
    '@/utils/dict': {},
    '@/utils/formatTime': {},
    '@/utils/download': {},
    '@/views/mes/cost/navigation': {},
    '@/views/mes/utils/constants': constants,
    '@/utils/permission': { checkPermi: () => true },
    '@/api/mes/wm/productsales': { WmProductSalesApi: { getQcOptions: async () => [] } },
    '@/api/mes/wm/salesnotice': { WmSalesNoticeApi: {} },
    '@/api/mes/wm/productreceipt': { WmProductReceiptApi: {} },
    '@/api/mes/md/autocode/record': { AutoCodeRecordApi: {} },
    ...dependencies
  }, {
    ...vue,
    useI18n: () => ({ t: (v) => v }),
    useRoute: () => vue.reactive({ query: {}, path: '/mes/wm/product-sales' }),
    useRouter: () => ({ push: async (v) => { routerCalls.push(v) } }),
    useMessage: () => Object.fromEntries(['warning', 'error', 'info', 'success'].map(
      (key) => [key, (value) => messages.push(value)])),
    ...globals
  }).default
  const scope = vue.effectScope()
  const state = scope.run(() => component.setup({}, { expose: () => {}, emit: () => {} }))
  t.after(() => scope.stop())
  return { state, messages, routerCalls }
}
const notice = (id = 10, status = 3) => ({
  id, code: `NOTICE-${id}`, status, clientId: 20, salesOrderCode: 'SO-1', salesDate: '1793318400000',
  recipientName: '张三', recipientTelephone: '13800000000', recipientAddress: '测试地址'
})

test('approved notice navigates to its sales list without creating or posting documents', async (t) => {
  const { state, routerCalls } = setup(t, 'salesnotice/SalesNoticeForm.vue')
  state.formData.value = notice()
  state.dialogVisible.value = true
  await state.handleFinish()
  assert.equal(routerCalls.length, 1)
  assert.equal(routerCalls[0].path, '/mes/wm/product-sales')
  assert.equal(routerCalls[0].query.noticeId, 10)
  assert.equal(state.dialogVisible.value, false)
})

test('notice entry respects permission and status, and stays open on failed navigation', async (t) => {
  const denied = setup(t, 'salesnotice/SalesNoticeForm.vue', {
    '@/utils/permission': { checkPermi: () => false }
  })
  denied.state.formData.value = notice()
  denied.state.dialogVisible.value = true
  await denied.state.handleFinish()
  assert.equal(denied.routerCalls.length, 0)
  assert.match(denied.messages[0], /权限/)
  const completed = setup(t, 'salesnotice/SalesNoticeForm.vue')
  completed.state.formData.value = notice(10, 4)
  await completed.state.handleFinish()
  assert.equal(completed.routerCalls.length, 0)
  const failed = setup(t, 'salesnotice/SalesNoticeForm.vue', {}, {
    useRouter: () => ({ push: async () => ({ type: 4 }) })
  })
  failed.state.formData.value = notice()
  failed.state.dialogVisible.value = true
  await failed.state.handleFinish()
  assert.equal(failed.state.dialogVisible.value, true)
  assert.equal(failed.state.formLoading.value, false)
})

test('notice context filters list, survives search reset and prefills new forms', async (t) => {
  const calls = []
  const route = vue.reactive({ query: { noticeId: '10' }, path: '/mes/wm/product-sales' })
  const { state } = setup(t, 'productsales/index.vue', {
    '@/api/mes/wm/salesnotice': { WmSalesNoticeApi: { getSalesNotice: async () => notice() } },
    '@/api/mes/wm/productsales': { WmProductSalesApi: {
      getProductSalesPage: async (q) => { calls.push(q); return { list: [{ id: 30 }], total: 1 } }
    } }
  }, { useRoute: () => route })
  await flush()
  assert.equal(calls[0].noticeId, 10)
  assert.equal(state.list.value[0].id, 30)
  state.queryFormRef.value = { resetFields: () => {} }
  state.resetQuery()
  await flush()
  assert.equal(calls.at(-1).noticeId, 10)
  let opened
  state.formRef.value = { open: (...args) => { opened = args } }
  state.openForm('create')
  assert.equal(opened[2].noticeId, 10)
  state.queryParams.code = 'OLD-FILTER'
  route.query = {}
  await flush()
  assert.equal(calls.at(-1).noticeId, undefined)
  assert.equal(calls.at(-1).code, undefined)
})

test('invalid, missing or inaccessible notice context cannot show all documents or create', async (t) => {
  for (const value of ['', '0', '-1', '1.5', '10x', ['10', '11'], '9007199254740992', '99']) {
    let listCalls = 0
    const route = vue.reactive({ query: { noticeId: value }, path: '/mes/wm/product-sales' })
    const { state } = setup(t, 'productsales/index.vue', {
      '@/api/mes/wm/salesnotice': { WmSalesNoticeApi: { getSalesNotice: async () => null } },
      '@/api/mes/wm/productsales': { WmProductSalesApi: {
        getProductSalesPage: async () => { listCalls++; return { list: [], total: 0 } }
      } }
    }, { useRoute: () => route })
    await flush()
    assert.equal(listCalls, 0)
    assert.equal(state.list.value.length, 0)
    assert.match(state.noticeContextError.value, /无效|无法读取/)
    state.openForm('create') // no form ref: this must be blocked before accessing it
  }
})

test('slow list or notice response cannot replace a newer notice context', async (t) => {
  const oldNotice = deferred()
  const oldList = deferred()
  const route = vue.reactive({ query: { noticeId: '10' }, path: '/mes/wm/product-sales' })
  const { state } = setup(t, 'productsales/index.vue', {
    '@/api/mes/wm/salesnotice': { WmSalesNoticeApi: {
      getSalesNotice: (id) => id === 10 ? oldNotice.promise : Promise.resolve(notice(id))
    } },
    '@/api/mes/wm/productsales': { WmProductSalesApi: {
      getProductSalesPage: (q) => q.noticeId === 11 ? oldList.promise : Promise.resolve({ list: [{ id: 12 }], total: 1 })
    } }
  }, { useRoute: () => route })
  route.query.noticeId = '11'
  await flush()
  route.query.noticeId = '12'
  await flush()
  oldNotice.resolve(notice(10))
  oldList.resolve({ list: [{ id: 11 }], total: 1 })
  await flush()
  assert.equal(state.noticeContext.value.id, 12)
  assert.equal(state.queryParams.noticeId, 12)
  assert.equal(state.list.value[0].id, 12)
})

test('new sales form refreshes notice and copies recipient, customer, order and date without saving', async (t) => {
  const { state } = setup(t, 'productsales/ProductSalesForm.vue', {
    '@/api/mes/wm/salesnotice': { WmSalesNoticeApi: { getSalesNotice: async () => notice() } }
  })
  await state.open('create', undefined, { noticeId: 10 })
  const data = state.formData.value
  assert.equal(data.noticeId, 10)
  assert.equal(data.clientId, 20)
  assert.equal(data.salesOrderCode, 'SO-1')
  assert.equal(data.contactName, '张三')
  assert.equal(data.contactTelephone, '13800000000')
  assert.equal(data.contactAddress, '测试地址')
  assert.equal(data.salesDate, notice().salesDate)
  assert.equal(data.id, undefined)
  assert.equal(state.dialogVisible.value, true)
})

test('new sales form rejects completed notice and ignores stale prefill after reopening', async (t) => {
  const old = deferred()
  const { state } = setup(t, 'productsales/ProductSalesForm.vue', {
    '@/api/mes/wm/salesnotice': { WmSalesNoticeApi: {
      getSalesNotice: (id) => id === 10 ? old.promise : Promise.resolve(notice(id, 4))
    } }
  })
  const first = state.open('create', undefined, { noticeId: 10 })
  await state.open('create', undefined, { noticeId: 11 })
  assert.equal(state.dialogVisible.value, false)
  await state.open('create')
  old.resolve(notice())
  await first
  assert.equal(state.formData.value.noticeId, undefined)
  assert.equal(state.formLoading.value, false)
})

test('receipt number generation uses its own rule, catches failure and ignores double click or stale response', async (t) => {
  const pending = deferred()
  const codes = []
  const { state } = setup(t, 'productreceipt/ProductReceiptForm.vue', {
    '@/api/mes/md/autocode/record': { AutoCodeRecordApi: {
      generateAutoCode: (code) => { codes.push(code); return pending.promise }
    } }
  })
  await state.open('create')
  const generating = state.generateCode()
  await state.generateCode()
  assert.equal(codes.length, 1)
  assert.equal(codes[0], 'PRODUCTRECPT_CODE')
  await state.open('create')
  pending.resolve('OLD-CODE')
  await generating
  assert.equal(state.formData.value.code, undefined)
  state.formType.value = 'finish'
  await state.generateCode()
  assert.equal(codes.length, 1)
  const failed = setup(t, 'productreceipt/ProductReceiptForm.vue', {
    '@/api/mes/md/autocode/record': { AutoCodeRecordApi: {
      generateAutoCode: async () => { throw new Error('network') }
    } }
  }).state
  await failed.open('create')
  failed.formData.value.code = 'MANUAL'
  await failed.generateCode()
  assert.equal(failed.formData.value.code, 'MANUAL')
  assert.equal(failed.generatingCode.value, false)
})
