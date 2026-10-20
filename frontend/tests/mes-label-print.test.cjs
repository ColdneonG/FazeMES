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
    {
      module,
      exports: module.exports,
      require: (name) => dependencies[name] || (name.endsWith('.vue') ? {} : require(name)),
      TextEncoder,
      btoa,
      Error,
      setTimeout,
      clearTimeout,
      ...globals
    },
    { filename }
  )
  return module.exports
}
const helperFile = path.join(__dirname, '../src/views/mes/wm/barcode/labelPrint.ts')
const labels = evaluate(fs.readFileSync(helperFile, 'utf8'), helperFile)
const constantsFile = path.join(__dirname, '../src/views/mes/utils/constants.ts')
const constants = evaluate(fs.readFileSync(constantsFile, 'utf8'), constantsFile)

function setup(t, relative, props = {}, dependencies = {}, globals = {}) {
  const filename = path.join(__dirname, '../src/views/mes/wm/barcode', relative)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const messages = [],
    events = []
  const component = evaluate(
    compileScript(descriptor, { id: 'label-print' }).content,
    filename,
    {
      vue: { ...vue, onBeforeUnmount: () => {} },
      'element-plus': { ElMessage: { warning: (text) => messages.push(text) } },
      '@/utils/permission': { checkPermi: () => true },
      '../labelPrint': labels,
      './labelPrint': labels,
      '@/views/mes/utils/constants': constants,
      '@/utils/constants': { CommonStatusEnum: { ENABLE: 0 } },
      '@/utils/dict': {},
      '@/api/mes/wm/warehouse/location': {},
      '@/api/mes/wm/warehouse/area': {},
      ...dependencies
    },
    {
      ...vue,
      useMessage: () => ({
        warning: (text) => messages.push(text),
        error: (text) => messages.push(text),
        success: () => {}
      }),
      onBeforeUnmount: () => {},
      useI18n: () => ({ t: (value) => value }),
      ...globals
    }
  ).default
  const scope = vue.effectScope(),
    reactiveProps = vue.reactive(props)
  const state = scope.run(() =>
    component.setup(reactiveProps, { expose: () => {}, emit: (...args) => events.push(args) })
  )
  t.after(() => scope.stop())
  return { state, props: reactiveProps, messages, events }
}

test('business label button opens a real server-identity preview instead of a placeholder toast', async (t) => {
  const opened = []
  const { state } = setup(t, 'components/PrinterLabel.vue', {
    bizId: 32,
    bizCode: 'UNTRUSTED',
    bizType: 'ITEM',
    labelText: '标签打印'
  })
  if (state.printDialogRef)
    state.printDialogRef.value = {
      open: (selectors) => opened.push(JSON.parse(JSON.stringify(selectors)))
    }
  await state.handlePrint()
  assert.deepEqual(opened, [[{ bizType: 600, bizId: 32 }]])
})

const flush = async () => {
  await Promise.resolve()
  await vue.nextTick()
  await Promise.resolve()
  await vue.nextTick()
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}
const context = (id = 32, overrides = {}) => ({
  barcodeId: id + 1000,
  registered: true,
  configId: 4,
  bizType: 600,
  bizId: id,
  bizCode: `ITEM-${id}`,
  bizName: '轴承',
  format: 1,
  content: `ITEM-${id}`,
  candidateContent: null,
  defaultTemplate: 'LABEL_50X30',
  ...overrides
})
const api = (getLabelContext, extra = {}) => ({
  '@/api/mes/wm/barcode': { WmBarcodeApi: { getLabelContext, ...extra } }
})
const documentStub = (images = [], fonts = Promise.resolve()) => ({
  fonts: { ready: fonts },
  images,
  querySelector: () => ({}),
  querySelectorAll: () => [],
  open() {},
  close() {},
  write() {}
})
async function setReady(state, images = []) {
  const document = documentStub(images)
  document.body = {
    dataset: { previewVersion: state.previewHtml.value.match(/data-preview-version="(\d+)"/)[1] }
  }
  state.previewRef.value = { contentDocument: document }
  await state.onPreviewLoad()
  assert.equal(state.ready.value, true)
}

test('supported business aliases resolve exact identities and unsupported types fail closed', () => {
  assert.equal(labels.barcodeBusinessType('ITEM'), 600)
  assert.equal(labels.barcodeBusinessType('BATCH'), 107)
  assert.equal(labels.barcodeBusinessType('ITEM_BATCH'), 107)
  assert.equal(labels.barcodeBusinessType('PROCARD'), 300)
  for (const invalid of [302, 303, 'TRANSORDER', 'FORGED', undefined])
    assert.equal(labels.barcodeBusinessType(invalid), undefined)
  for (const selector of [
    { barcodeId: 0 },
    { barcodeId: 1, bizId: 32 },
    { bizType: 302, bizId: 1 },
    { bizType: 600, bizId: 9007199254740992 }
  ])
    assert.throws(() => labels.assertSelector(selector))
})

test('actual QR, EAN13, CODE39 and UPC encoders produce safe SVG with strict complete checksum payloads', async () => {
  for (const [format, content] of [
    [1, '中文物料-032'],
    [2, '5901234123457'],
    [3, 'ITEM-032'],
    [4, '036000291452']
  ]) {
    const encoded = await labels.encodeBarcode(content, format)
    const svg = Buffer.from(encoded.dataUrl.split(',')[1], 'base64').toString('utf8')
    assert.match(svg, /^<svg xmlns="http:\/\/www.w3.org\/2000\/svg"/)
    assert.match(svg, /fill="#000"/)
    if (format !== 1) assert.match(svg, /preserveAspectRatio="none"/)
    assert.ok(encoded.modules > 20)
    assert.doesNotMatch(svg, /script|foreignObject|onload=|href=|<text/)
  }
  const invalid = [
    [2, '590123412345'],
    [2, '5901234123458'],
    [4, '03600029145'],
    [4, '036000291453'],
    [3, 'abc'],
    [3, '*ITEM*'],
    [3, 'A'.repeat(129)],
    [1, '汉'.repeat(683)],
    [1, ''],
    [1, 'A\nB'],
    [99, 'ABC']
  ]
  for (const [format, content] of invalid)
    await assert.rejects(labels.encodeBarcode(content, format))
  assert.equal(constants.BARCODE_FORMAT_MAP[4], 'UPC')
})

test('barcode clears previously printable image on empty/error and ignores late encoding for a replaced identity', async (t) => {
  const first = deferred(),
    second = deferred()
  let calls = 0
  const { state, props } = setup(
    t,
    'components/Barcode.vue',
    { content: 'A', format: 1, width: 120, height: 60, displayValue: true },
    {
      '../labelPrint': { encodeBarcode: () => (++calls === 1 ? first.promise : second.promise) }
    }
  )
  props.content = 'B'
  await flush()
  second.resolve({ dataUrl: 'data:B' })
  await flush()
  assert.equal(state.getImageBase64(), 'data:B')
  first.resolve({ dataUrl: 'data:A' })
  await flush()
  assert.equal(state.getImageBase64(), 'data:B')
  props.content = ''
  await flush()
  assert.equal(state.getImageBase64(), '')
  props.content = 'C'
  props.width = -1
  await flush()
  assert.equal(state.getImageBase64(), '')
  assert.match(state.errorText.value, /尺寸/)
})

test('barcode dimension and text changes regenerate and invalidate prior readiness', async (t) => {
  const pending = deferred()
  let calls = 0
  const { state, props, events } = setup(
    t,
    'components/Barcode.vue',
    { content: 'A', format: 1, width: 120, height: 60, displayValue: true },
    {
      '../labelPrint': {
        encodeBarcode: async () => {
          calls++
          return calls === 1 ? { dataUrl: 'first' } : pending.promise
        }
      }
    }
  )
  await flush()
  assert.equal(state.getImageBase64(), 'first')
  props.width = 200
  props.height = 80
  props.displayValue = false
  await flush()
  assert.equal(state.getImageBase64(), '')
  assert.equal(events.at(-1)[1], '')
  pending.resolve({ dataUrl: 'second' })
  await flush()
  assert.equal(state.getImageBase64(), 'second')
})

test('missing registration remains unprintable and GET never causes automatic creation', async (t) => {
  let created = 0,
    opened = 0
  const missing = context(32, {
    barcodeId: null,
    registered: false,
    content: null,
    candidateContent: 'ITEM-32'
  })
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(async () => missing, { createBarcode: async () => created++ }),
    {
      window: {
        open: () => {
          opened++
        }
      }
    }
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  assert.equal(state.hasUnregistered.value, true)
  assert.equal(state.previewHtml.value, '')
  await state.print()
  assert.equal(created, 0)
  assert.equal(opened, 0)
})

test('explicit registration uses fresh canonical fields and fresh registered response before enabling preview', async (t) => {
  let registered = false,
    reads = 0
  const saved = []
  const missing = () =>
    context(32, {
      barcodeId: null,
      registered: false,
      content: null,
      candidateContent: 'CANONICAL'
    })
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(
      async () => {
        reads++
        return registered ? context() : missing()
      },
      {
        createBarcode: async (row) => {
          saved.push(JSON.parse(JSON.stringify(row)))
          registered = true
        }
      }
    )
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await state.generate({ ...missing(), bizCode: 'FORGED' })
  assert.equal(reads, 3)
  assert.equal(saved.length, 1)
  assert.equal(saved[0].bizCode, 'ITEM-32')
  assert.equal(saved[0].bizId, 32)
  assert.equal('content' in saved[0], false)
  assert.match(state.previewHtml.value, /data:image\/svg\+xml;base64/)
  assert.equal(state.ready.value, false)
  await setReady(state)
})

test('registration without create permission is blocked even when query is allowed', async (t) => {
  let created = 0
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    {
      ...api(async () => context(32, { registered: false, barcodeId: null, content: null }), {
        createBarcode: async () => created++
      }),
      '@/utils/permission': {
        checkPermi: (permissions) => !permissions.includes('mes:wm-barcode:create')
      }
    }
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await state.generate(state.contexts.value[0])
  assert.equal(created, 0)
})

test('late identity response and response after dialog close cannot restore printable data', async (t) => {
  const first = deferred(),
    late = deferred()
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api((selector) =>
      selector.bizId === 32
        ? first.promise
        : selector.bizId === 34
          ? late.promise
          : Promise.resolve(context(33))
    )
  )
  const openingA = state.open([{ bizType: 600, bizId: 32 }])
  await state.open([{ bizType: 600, bizId: 33 }])
  first.resolve(context(32))
  await openingA
  assert.equal(state.contexts.value[0].bizId, 33)
  const openingLate = state.open([{ bizType: 600, bizId: 34 }])
  state.close()
  late.resolve(context(34))
  await openingLate
  assert.equal(state.previewHtml.value, '')
  assert.equal(state.contexts.value.length, 0)
})

test('forged server identity, malformed selectors and excessive copies never produce a print document', async (t) => {
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(async () => context(99))
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  assert.equal(state.previewHtml.value, '')
  assert.match(state.errorText.value, /身份/)
  await state.open([{ barcodeId: 1 }, { barcodeId: 1 }])
  assert.match(state.errorText.value, /重复/)
  const image = await labels.encodeBarcode('ITEM-32', 1)
  for (const copies of [0, 51, 1.5])
    assert.throws(() =>
      labels.labelPrintHtml([{ context: context(), image }], 'LABEL_50X30', copies)
    )
  assert.throws(() =>
    labels.labelPrintHtml(Array(5).fill({ context: context(), image }), 'A4_2COL', 50)
  )
})

test('print opens popup during gesture, refreshes identity, then waits images/fonts before printing', async (t) => {
  const fresh = deferred(),
    image = deferred(),
    fonts = deferred()
  let reads = 0,
    opened = 0,
    printed = 0,
    written = ''
  const printDocument = documentStub([{ decode: () => image.promise }], fonts.promise)
  printDocument.write = (html) => {
    written = html
  }
  const popup = {
    document: printDocument,
    closed: false,
    close() {
      this.closed = true
    },
    focus() {},
    print() {
      printed++
    }
  }
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(() => (++reads === 1 ? Promise.resolve(context()) : fresh.promise)),
    {
      window: {
        open: () => {
          opened++
          return popup
        }
      }
    }
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await setReady(state)
  const printing = state.print()
  assert.equal(opened, 1)
  assert.equal(printed, 0)
  fresh.resolve(context())
  await flush()
  assert.equal(printed, 0)
  image.resolve()
  await flush()
  assert.equal(printed, 0)
  fonts.resolve()
  await printing
  assert.equal(printed, 1)
  assert.match(written, /size:50mm 30mm/)
})

test('changed or revoked registration aborts printing and requires reviewing current preview', async (t) => {
  let reads = 0,
    printed = 0,
    closed = 0
  const popup = {
    closed: false,
    close() {
      closed++
    },
    document: documentStub(),
    focus() {},
    print() {
      printed++
    }
  }
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(async () => (++reads === 1 ? context() : context(32, { bizName: '新名称' }))),
    { window: { open: () => popup } }
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await setReady(state)
  await state.print()
  assert.equal(printed, 0)
  assert.equal(closed, 1)
  assert.equal(state.contexts.value[0].bizName, '新名称')
  assert.match(state.errorText.value, /已更新/)
})

test('image failure, overflow and image timeout prevent printing readiness', async () => {
  await assert.rejects(
    labels.waitForLabelDocument(
      documentStub([
        {
          decode: async () => {
            throw new Error('bad image')
          }
        }
      ])
    )
  )
  const overflow = documentStub()
  overflow.querySelectorAll = () => [
    { scrollHeight: 101, clientHeight: 20, scrollWidth: 10, clientWidth: 10 }
  ]
  await assert.rejects(labels.waitForLabelDocument(overflow), /超出/)
  await assert.rejects(
    labels.waitForLabelDocument(documentStub([{ decode: () => new Promise(() => {}) }]), 10),
    /超时/
  )
})

test('fixed templates escape labels, paginate A4 and reject arbitrary template/image and too-dense symbols', async () => {
  const image = await labels.encodeBarcode('ITEM-32', 1)
  const label = {
    context: context(32, { bizCode: '<script>alert(1)</script>', bizName: '" onload="bad' }),
    image
  }
  const html = labels.labelPrintHtml([label], 'A4_2COL', 11)
  assert.equal((html.match(/class="sheet"/g) || []).length, 2)
  assert.equal((html.match(/<article /g) || []).length, 11)
  assert.ok(html.includes('&lt;script&gt;'))
  assert.ok(!html.includes('<script>'))
  assert.match(html, /default-src 'none'/)
  assert.throws(() => labels.labelPrintHtml([label], '<html>', 1), /模板/)
  assert.throws(
    () =>
      labels.labelPrintHtml(
        [{ ...label, image: { ...image, dataUrl: 'https://foreign/image' } }],
        'LABEL_50X30',
        1
      ),
    /图片/
  )
  const dense = await labels.encodeBarcode('A'.repeat(128), 3)
  assert.throws(
    () =>
      labels.labelPrintHtml(
        [{ context: context(32, { format: 3, content: 'A'.repeat(128) }), image: dense }],
        'LABEL_50X30',
        1
      ),
    /过密/
  )
})

test('old detail row content is ignored and detail uses canonical server selector', async (t) => {
  const opened = [],
    { state } = setup(t, 'components/BarcodeDetail.vue')
  state.printDialogRef.value = {
    open: (selector) => opened.push(JSON.parse(JSON.stringify(selector)))
  }
  await state.open({ id: 10, bizId: 32, bizType: 600, content: 'FORGED' })
  await state.openByBusiness(32, 600, 'FORGED', 'FORGED')
  assert.deepEqual(opened, [[{ barcodeId: 10 }], [{ bizType: 600, bizId: 32 }]])
})

test('barcode form uses actual stock record ID and discards stale generated content', async (t) => {
  const first = deferred(),
    codes = []
  const { state } = setup(
    t,
    'BarcodeForm.vue',
    {},
    api(async () => context(), {
      generateBarcodeContent: async (_type, code) => {
        codes.push(code)
        return code === '81' ? first.promise : `STOCK-${code}`
      }
    })
  )
  state.formData.value.bizType = constants.BarcodeBizTypeEnum.STOCK
  await flush()
  const old = state.handleBizSelect({ id: 81, itemCode: 'SAME-ITEM', itemName: '原料' })
  await state.handleBizSelect({ id: 82, itemCode: 'SAME-ITEM', itemName: '原料' })
  first.resolve('STOCK-81')
  await old
  assert.deepEqual(codes, ['81', '82'])
  assert.equal(state.formData.value.bizCode, '82')
  assert.equal(state.formData.value.content, 'STOCK-82')
})

test('query permission revocation clears existing evidence and blocks subsequent print', async (t) => {
  const permission = vue.ref(true)
  let opened = 0
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    {
      ...api(async () => context()),
      '@/utils/permission': { checkPermi: () => permission.value }
    },
    { window: { open: () => opened++ } }
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await setReady(state)
  permission.value = false
  await flush()
  await state.print()
  assert.equal(state.contexts.value.length, 0)
  assert.equal(state.previewHtml.value, '')
  assert.equal(state.ready.value, false)
  assert.equal(opened, 0)
})

test('preview load belonging to an old render cannot make the current render ready', async (t) => {
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(async () => context())
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  const old = documentStub()
  old.body = { dataset: { previewVersion: '-1' } }
  state.previewRef.value = { contentDocument: old }
  await state.onPreviewLoad()
  assert.equal(state.ready.value, false)
  await setReady(state)
  state.copies.value = 2
  await state.rebuild()
  assert.equal(state.ready.value, false)
  await state.onPreviewLoad()
  assert.equal(state.ready.value, false)
  await setReady(state)
})

test('barcode download waits for fresh registry and produces an SVG rather than a mislabeled PNG', async (t) => {
  const downloads = []
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(async () => context()),
    {
      document: {
        createElement: () => ({
          click() {
            downloads.push({ href: this.href, download: this.download })
          }
        })
      }
    }
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await setReady(state)
  await state.downloadBarcode()
  assert.equal(downloads.length, 1)
  assert.match(downloads[0].href, /^data:image\/svg\+xml;base64,/)
  assert.equal(downloads[0].download, 'barcode_ITEM-32.svg')
})

test('configuration picker saves an actual safe preset and refuses an unsupported historical template', async (t) => {
  const saved = []
  const { state } = setup(
    t,
    'config/BarcodeConfigForm.vue',
    {},
    {
      '@/api/mes/wm/barcode/config': {
        WmBarcodeConfigApi: {
          getBarcodeConfig: async () => ({
            id: 1,
            defaultTemplate: '<html>',
            format: 1,
            bizType: 600
          }),
          updateBarcodeConfig: async (value) => saved.push(JSON.parse(JSON.stringify(value)))
        }
      }
    }
  )
  state.formRef.value = { validate: async () => true, resetFields() {} }
  await state.open('update', 1)
  await state.submitForm()
  assert.equal(saved.length, 0)
  state.formData.value.defaultTemplate = 'LABEL_70X40'
  await state.submitForm()
  assert.equal(saved[0].defaultTemplate, 'LABEL_70X40')
})

test('a rejected new selection cannot refresh and resurrect previously selected labels', async (t) => {
  let reads = 0
  const { state } = setup(
    t,
    'components/LabelPrintDialog.vue',
    {},
    api(async () => {
      reads++
      return context()
    })
  )
  await state.open([{ bizType: 600, bizId: 32 }])
  await setReady(state)
  await state.open([{ bizType: 302, bizId: 1 }])
  await state.refresh()
  assert.equal(reads, 1)
  assert.equal(state.contexts.value.length, 0)
  assert.equal(state.previewHtml.value, '')
  assert.equal(state.ready.value, false)
})

test('invalid UTF16 and QR control characters cannot be silently replaced when encoding registered content', async () => {
  for (const value of [
    '\ud800',
    '\udc00',
    'A\ud800X',
    'A\udc00X',
    'A\u0000B',
    'A\u001fB',
    'A\u007fB'
  ])
    await assert.rejects(labels.encodeBarcode(value, 1))
  await assert.rejects(labels.encodeBarcode('ITEM\\X', 3))
  const emoji = await labels.encodeBarcode('中文😀', 1)
  assert.match(emoji.dataUrl, /^data:image\/svg\+xml;base64,/)
})
