import QRCode from 'qrcode'
import JsBarcode from 'jsbarcode'
import type { BarcodeLabelContext, LabelSelector, LabelTemplate } from '@/api/mes/wm/barcode'

export const LABEL_TEMPLATES = [
  { key: 'LABEL_50X30' as const, name: '50 × 30 毫米标签', width: 50, height: 30 },
  { key: 'LABEL_70X40' as const, name: '70 × 40 毫米标签', width: 70, height: 40 },
  { key: 'A4_2COL' as const, name: 'A4 双列（每页 10 张）', width: 90, height: 50 }
]
export const MAX_LABELS = 200
const FORMATS: Record<number, string> = { 2: 'EAN13', 3: 'CODE39', 4: 'UPC' }
const BIZ_TYPES: Record<string, number> = {
  WAREHOUSE: 102,
  LOCATION: 103,
  AREA: 104,
  PACKAGE: 105,
  STOCK: 106,
  BATCH: 107,
  ITEM_BATCH: 107,
  SN: 109,
  PROCARD: 300,
  WORKORDER: 301,
  MACHINERY: 400,
  TOOL: 500,
  ITEM: 600,
  VENDOR: 601,
  WORKSTATION: 602,
  WORKSHOP: 603,
  USER: 604,
  CLIENT: 605
}
export const positiveId = (id: unknown): id is number =>
  typeof id === 'number' && Number.isSafeInteger(id) && id > 0
export function barcodeBusinessType(value: string | number): number | undefined {
  const type = typeof value === 'number' ? value : BIZ_TYPES[value]
  return Object.values(BIZ_TYPES).includes(type) ? type : undefined
}
export function assertSelector(value: LabelSelector): void {
  if ('barcodeId' in value) {
    if (!positiveId(value.barcodeId) || 'bizId' in value || 'bizType' in value)
      throw new Error('请选择有效条码记录')
  } else if (!positiveId(value.bizId) || !barcodeBusinessType(value.bizType)) {
    throw new Error('请选择已接入的真实业务对象')
  }
}
export function assertContext(context: BarcodeLabelContext, selector: LabelSelector): void {
  assertSelector(selector)
  if (
    !context ||
    !positiveId(context.bizId) ||
    !barcodeBusinessType(context.bizType) ||
    typeof context.bizCode !== 'string' ||
    !context.bizCode.length ||
    ('barcodeId' in selector
      ? context.barcodeId !== selector.barcodeId
      : context.bizType !== selector.bizType || context.bizId !== selector.bizId)
  )
    throw new Error('返回的标签身份与当前选择不一致，请刷新')
  template(context.defaultTemplate)
  if (context.registered && (!positiveId(context.barcodeId) || !context.content))
    throw new Error('条码登记数据不完整，请修正后再打印')
}
export function template(key: string) {
  const preset = LABEL_TEMPLATES.find((entry) => entry.key === key)
  if (!preset) throw new Error('打印模板无效，请在条码配置中选择有效模板')
  return preset
}
export function validateBarcodeContent(content: string, format: number): void {
  if (!content || !content.trim() || /[\u0000-\u001f\u007f]/.test(content))
    throw new Error('条码内容不能为空或包含控制字符')
  for (let index = 0; index < content.length; index++) {
    const value = content.charCodeAt(index)
    if (value >= 0xd800 && value <= 0xdbff) {
      const next = content.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error('条码内容包含无效字符')
    } else if (value >= 0xdc00 && value <= 0xdfff) throw new Error('条码内容包含无效字符')
  }
  if (format === 1) {
    if (new TextEncoder().encode(content).length > 2048) throw new Error('二维码内容超过 2048 字节')
    return
  }
  if (format === 3) {
    if (content.length > 128 || !/^[A-Z0-9 .$/+%\-]+$/.test(content))
      throw new Error('CODE39 仅支持大写字母、数字及许可符号，最多128字符，不能包含 *')
    return
  }
  if (format !== 2 && format !== 4) throw new Error('不支持此条码格式，请修正条码配置')
  const length = format === 2 ? 13 : 12
  if (!new RegExp(`^[0-9]{${length}}$`).test(content))
    throw new Error(`${format === 2 ? 'EAN13' : 'UPC-A'} 必须包含完整的 ${length} 位数字及校验位`)
  let sum = 0
  for (let i = length - 2, weight = 3; i >= 0; i--, weight = weight === 3 ? 1 : 3)
    sum += Number(content[i]) * weight
  if ((10 - (sum % 10)) % 10 !== Number(content[length - 1]))
    throw new Error('条码校验位不正确，请修正登记内容')
}

export interface EncodedBarcode {
  dataUrl: string
  modules: number
  square: boolean
}
export async function encodeBarcode(content: string, format: number): Promise<EncodedBarcode> {
  validateBarcodeContent(content, format)
  let width: number,
    height: number,
    bars = ''
  if (format === 1) {
    const qr = QRCode.create(content, { errorCorrectionLevel: 'M' })
    width = height = qr.modules.size + 8
    for (let y = 0; y < qr.modules.size; y++)
      for (let x = 0; x < qr.modules.size; x++)
        if (qr.modules.get(y, x)) bars += `M${x + 4},${y + 4}h1v1h-1z`
  } else {
    const output: { encodings?: Array<{ data: string }> } = {}
    JsBarcode(output, content, {
      format: FORMATS[format],
      displayValue: false,
      width: 1,
      margin: 0
    })
    const data = output.encodings?.map((part) => part.data).join('')
    if (!data || !/^[01]+$/.test(data)) throw new Error('条码编码失败')
    width = data.length + 20
    height = 60
    for (let x = 0; x < data.length; x++) if (data[x] === '1') bars += `M${x + 10},0h1v60h-1z`
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" preserveAspectRatio="${format === 1 ? 'xMidYMid meet' : 'none'}" shape-rendering="crispEdges"><path fill="#fff" d="M0,0h${width}v${height}H0z"/><path fill="#000" d="${bars}"/></svg>`
  return { dataUrl: `data:image/svg+xml;base64,${btoa(svg)}`, modules: width, square: format === 1 }
}
export function contextSignature(context: BarcodeLabelContext): string {
  return JSON.stringify([
    context.barcodeId,
    context.registered,
    context.configId,
    context.bizType,
    context.bizId,
    context.bizCode,
    context.bizName,
    context.format,
    context.content,
    context.defaultTemplate
  ])
}
export interface PrintableLabel {
  context: BarcodeLabelContext
  image: EncodedBarcode
}
const escape = (value: unknown) =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!
  )

/** Fixed templates only. No server-provided HTML or external image/font resource is executed. */
export function labelPrintHtml(
  labels: PrintableLabel[],
  key: LabelTemplate,
  copies: number
): string {
  const preset = template(key)
  if (
    !Number.isInteger(copies) ||
    copies < 1 ||
    copies > 50 ||
    !labels.length ||
    labels.length * copies > MAX_LABELS
  )
    throw new Error(`份数须为1至50，单次最多${MAX_LABELS}张标签`)
  const all = labels.flatMap((label) => Array.from({ length: copies }, () => label))
  const cards = all.map(({ context, image }) => {
    if (!context.registered || !positiveId(context.barcodeId))
      throw new Error('请先生成并登记条码，再打印标签')
    validateBarcodeContent(context.content ?? '', context.format)
    if (!/^data:image\/svg\+xml;base64,[A-Za-z0-9+/]+=*$/.test(image.dataUrl))
      throw new Error('条码图片来源无效')
    const size = image.square
      ? Math.min(preset.height - 6, preset.width * 0.48)
      : preset.width - 4.3
    if (!Number.isInteger(image.modules) || image.modules < 1 || size / image.modules < 0.2)
      throw new Error(
        '条码过密，当前模板无法清晰打印；请选择更大模板，或调整内容及格式后重新生成条码'
      )
    return `<article class="label ${image.square ? 'qr' : 'linear'}"><div class="label-body"><img class="symbol" src="${image.dataUrl}" alt="条码" style="${image.square ? `width:${size}mm;height:${size}mm` : 'width:100%;height:14mm'}"/><div class="identity"><div class="code">${escape(context.bizCode)}</div><div class="name">${escape(context.bizName)}</div>${image.square ? '' : `<div class="encoded">${escape(context.content)}</div>`}</div></div></article>`
  })
  const perPage = key === 'A4_2COL' ? 10 : 1
  const pages: string[] = []
  for (let i = 0; i < cards.length; i += perPage)
    pages.push(`<section class="sheet">${cards.slice(i, i + perPage).join('')}</section>`)
  const a4 = key === 'A4_2COL'
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src 'none'; base-uri 'none'; form-action 'none'"><title>业务标签</title><style>
@page{size:${a4 ? 'A4' : `${preset.width}mm ${preset.height}mm`};margin:${a4 ? '10mm' : '0'}}
*{box-sizing:border-box}html,body{margin:0;padding:0;font-family:'Microsoft YaHei',Arial,sans-serif;color:#000;background:#fff}
.sheet{width:${a4 ? 190 : preset.width}mm;height:${a4 ? 277 : preset.height}mm;display:grid;grid-template-columns:${a4 ? 'repeat(2,90mm)' : `${preset.width}mm`};grid-template-rows:${a4 ? 'repeat(5,50mm)' : `${preset.height}mm`};gap:${a4 ? '5mm 10mm' : '0'};break-after:page;page-break-after:always}.sheet:last-child{break-after:auto;page-break-after:auto}
.label{width:${preset.width}mm;height:${preset.height}mm;padding:2mm;overflow:hidden;border:.15mm dashed #bbb;break-inside:avoid}.label-body{width:100%;height:100%;min-width:0;min-height:0}.qr .label-body{display:flex;align-items:center;gap:2mm}.linear .label-body{display:flex;flex-direction:column;justify-content:center;gap:1mm}.symbol{display:block;flex-shrink:0;object-fit:fill}.identity{min-width:0;overflow-wrap:anywhere;font-size:2.4mm;line-height:1.25}.code{font-weight:bold;font-size:2.7mm}.name{margin-top:1mm}.encoded{font-family:monospace;font-size:2.2mm;margin-top:.5mm}.linear .identity{text-align:center}.linear .name{margin-top:.4mm}@media print{.label{border-color:transparent}}
</style></head><body>${pages.join('')}</body></html>`
}

export async function waitForLabelDocument(document: Document, timeoutMs = 10000): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    await Promise.race([
      Promise.all([document.fonts?.ready, ...Array.from(document.images, (img) => img.decode())]),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('标签图片加载超时，请重试')), timeoutMs)
      })
    ])
    if (!document.querySelector('.label')) throw new Error('标签文档尚未就绪')
    for (const body of document.querySelectorAll<HTMLElement>('.label-body')) {
      if (body.scrollHeight > body.clientHeight + 1 || body.scrollWidth > body.clientWidth + 1)
        throw new Error('文字或图片超出标签，请选择更大的模板后重试')
      for (const text of body.querySelectorAll<HTMLElement>('.identity'))
        if (text.scrollHeight > body.clientHeight + 1 || text.scrollWidth > body.clientWidth + 1)
          throw new Error('文字超出标签，请选择更大的模板后重试')
    }
  } finally {
    if (timer) clearTimeout(timer)
  }
}
