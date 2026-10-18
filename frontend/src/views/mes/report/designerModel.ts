import type {
  ReportBlock,
  ReportBlockType,
  ReportCanvas,
  ReportConfig,
  ReportDataset,
  ReportPreview
} from '@/api/mes/report'

export const BLOCK_TYPES: { type: ReportBlockType; label: string; icon: string; hint: string }[] = [
  { type: 'text', label: '文本标题', icon: 'T', hint: '标题、说明与签核文字' },
  { type: 'table', label: '数据表格', icon: '▦', hint: '绑定显示列和业务明细' },
  { type: 'metric', label: '指标卡', icon: '#', hint: '当前页数值汇总' },
  { type: 'bar', label: '柱状图', icon: '▥', hint: '比较维度和数量' },
  { type: 'line', label: '折线图', icon: '⌁', hint: '观察数据变化' },
  { type: 'pie', label: '环形图', icon: '◔', hint: '分析单个数值的构成' },
  { type: 'divider', label: '分隔线', icon: '―', hint: '分隔报表内容区域' }
]

export const REPORT_TEMPLATES = [
  {
    key: 'general',
    name: '通用数据清单',
    category: '通用',
    dataset: 'workorders',
    description: 'A4纵向 · 标题、说明与明细表格',
    icon: '▦'
  },
  {
    key: 'production',
    name: '生产产量分析',
    category: '生产',
    dataset: 'feedback',
    description: 'A4纵向 · 产量指标、图表与报工明细',
    icon: '▥'
  },
  {
    key: 'quality',
    name: '过程质量报告',
    category: '质量',
    dataset: 'quality',
    description: 'A4纵向 · 检验指标、构成与数据清单',
    icon: '◔'
  },
  {
    key: 'print',
    name: '生产工单打印',
    category: '打印',
    dataset: 'workorders',
    description: 'A4横向 · 工单明细与签核栏',
    icon: '▤'
  }
]

export const cloneReport = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export const pageSize = (canvas: Pick<ReportCanvas, 'orientation'>) =>
  canvas.orientation === 'landscape' ? { width: 1123, height: 794 } : { width: 794, height: 1123 }
export const newBlockId = () =>
  `b_${globalThis.crypto?.randomUUID?.() || `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`}`
export const blockLabel = (type: string) =>
  BLOCK_TYPES.find((item) => item.type === type)?.label || type
export const visibleColumns = (config: ReportConfig) =>
  config.columns.filter((column) => column.visible !== false)
export const numericFields = (config: ReportConfig, datasets: ReportDataset[]) => {
  const fields = datasets.find((dataset) => dataset.key === config.dataset)?.fields || []
  return visibleColumns(config).filter(
    (column) =>
      column.aggregate || fields.some((field) => field.key === column.field && field.type === 'n')
  )
}

export function createBlock(
  type: ReportBlockType,
  config: ReportConfig,
  datasets: ReportDataset[]
): ReportBlock {
  const numbers = numericFields(config, datasets)
  const preferred = numbers.filter(
    (column) => column.field !== 'id' && !column.field.endsWith('_id')
  )
  const metrics = (preferred.length ? preferred : numbers)
    .slice(0, type === 'pie' || type === 'metric' ? 1 : 3)
    .map((column) => column.field)
  const dimension = visibleColumns(config).find((column) => !metrics.includes(column.field))?.field
  return {
    id: newBlockId(),
    type,
    x: 40,
    y: 140,
    width: type === 'metric' ? 344 : 714,
    height: type === 'text' ? 64 : type === 'divider' ? 16 : type === 'metric' ? 100 : 280,
    title: type === 'text' ? '' : blockLabel(type),
    text: type === 'text' ? '点击右侧属性编辑文字' : '',
    style: {
      fontSize: type === 'text' ? 24 : 12,
      color: '#183247',
      background: '#ffffff',
      align: 'left',
      bold: type === 'text',
      border: type === 'table'
    },
    binding: { dimension, metrics, summary: 'SUM', rowLimit: 10 }
  }
}

export function normalizeCanvas(config: ReportConfig, datasets: ReportDataset[]): ReportConfig {
  const result = cloneReport(config)
  if (!result.canvas) {
    const title = createBlock('text', result, datasets)
    Object.assign(title, { x: 40, y: 40, width: 714, height: 58, text: '业务报表' })
    const content = createBlock(
      ['bar', 'line', 'pie'].includes(result.chart) ? (result.chart as ReportBlockType) : 'table',
      result,
      datasets
    )
    Object.assign(content, { x: 40, y: 140, width: 714, height: 780 })
    result.canvas = {
      schemaVersion: 1,
      paper: 'A4',
      orientation: 'portrait',
      background: '#ffffff',
      blocks: [title, content]
    }
  }
  return result
}

export function createTemplate(
  key: string,
  datasets: ReportDataset[]
): { name: string; config: ReportConfig } {
  const template = REPORT_TEMPLATES.find((item) => item.key === key) || REPORT_TEMPLATES[0]
  const dataset = datasets.find((item) => item.key === template.dataset) || datasets[0]
  const desired =
    template.dataset === 'feedback'
      ? [
          'code',
          'feedback_time',
          'feedback_quantity',
          'qualified_quantity',
          'unqualified_quantity',
          'status'
        ]
      : template.dataset === 'quality'
        ? [
            'code',
            'inspect_date',
            'check_quantity',
            'qualified_quantity',
            'unqualified_quantity',
            'check_result'
          ]
        : ['code', 'name', 'quantity', 'quantity_produced', 'status', 'request_date']
  const fields = desired
    .map((name) => dataset?.fields.find((field) => field.key === name))
    .filter((field): field is ReportDataset['fields'][number] => !!field)
  const config: ReportConfig = {
    dataset: dataset?.key || 'workorders',
    chart: 'table',
    columns: (fields.length ? fields : dataset?.fields.slice(0, 6) || []).map((field) => ({
      field: field.key,
      title: field.title,
      width: 160,
      visible: true
    })),
    filters: [],
    groupBy: [],
    sortDirection: 'ASC',
    canvas: {
      schemaVersion: 1,
      paper: 'A4',
      orientation: key === 'print' ? 'landscape' : 'portrait',
      background: '#ffffff',
      blocks: []
    }
  }
  const size = pageSize(config.canvas!)
  const width = size.width - 80
  const title = createBlock('text', config, datasets)
  Object.assign(title, { x: 40, y: 40, width, height: 52, text: template.name })
  title.style.align = key === 'print' ? 'center' : 'left'
  const note = createBlock('text', config, datasets)
  Object.assign(note, { x: 40, y: 100, width, height: 32, text: 'FazeMES · 业务数据报表' })
  Object.assign(note.style, { fontSize: 12, bold: false, color: '#6b8192' })
  config.canvas!.blocks.push(title, note)
  const table = createBlock('table', config, datasets)
  Object.assign(table, { x: 40, y: 156, width, height: size.height - 276, title: '业务数据明细' })
  table.binding.rowLimit = key === 'print' ? 12 : 20
  if (key === 'production' || key === 'quality') {
    const metric = createBlock('metric', config, datasets)
    Object.assign(metric, {
      x: 40,
      y: 156,
      width: 344,
      height: 100,
      title: key === 'quality' ? '检验数量' : '报工数量'
    })
    const second = cloneReport(metric)
    Object.assign(second, { id: newBlockId(), x: 410, title: '合格数量' })
    second.binding.metrics = ['qualified_quantity']
    const chart = createBlock(key === 'quality' ? 'pie' : 'bar', config, datasets)
    Object.assign(chart, {
      x: 40,
      y: 284,
      width,
      height: 270,
      title: key === 'quality' ? '检验数量分布' : '报工数量对比'
    })
    chart.binding.dimension = 'code'
    Object.assign(table, { y: 580, height: 388 })
    table.binding.rowLimit = 8
    config.canvas!.blocks.push(metric, second, chart)
  }
  const footer = createBlock('text', config, datasets)
  Object.assign(footer, {
    x: 40,
    y: size.height - 80,
    width,
    height: 36,
    text:
      key === 'print'
        ? '制表：________________    复核：________________    日期：________________'
        : 'FazeMES 制造运营 · 图表和指标基于当前预览页，完整数据请导出 Excel'
  })
  Object.assign(footer.style, { fontSize: 10, color: '#6b8192', bold: false })
  config.canvas!.blocks.push(table, footer)
  return { name: template.name, config }
}

export function cleanBindings(config: ReportConfig, datasets: ReportDataset[]) {
  const visible = new Set(visibleColumns(config).map((column) => column.field))
  const numeric = new Set(numericFields(config, datasets).map((column) => column.field))
  config.canvas?.blocks.forEach((block) => {
    if (block.binding.dimension && !visible.has(block.binding.dimension))
      block.binding.dimension = undefined
    block.binding.metrics = block.binding.metrics.filter(
      (field) => visible.has(field) && numeric.has(field)
    )
  })
}

export const querySignature = (config: ReportConfig) =>
  JSON.stringify({
    dataset: config.dataset,
    columns: config.columns,
    filters: config.filters,
    groupBy: config.groupBy,
    sortField: config.sortField,
    sortDirection: config.sortDirection
  })
export const formatReportValue = (value: unknown) =>
  value === null || value === undefined ? '—' : String(value)
export const resultColumn = (result: ReportPreview | undefined, field?: string) =>
  result?.columns.find((column) => column.field === field)
export function metricValue(block: ReportBlock, result?: ReportPreview): string {
  if (!result) return '—'
  const column = resultColumn(result, block.binding.metrics[0])
  if (!column) return '—'
  const values = result.rows
    .map((row) => row[column.key])
    .filter((value) => value !== null && value !== undefined && value !== '')
    .map(Number)
    .filter(Number.isFinite)
  if (block.binding.summary === 'COUNT') return String(values.length)
  if (!values.length) return '—'
  const sum = values.reduce((left, right) => left + right, 0)
  const value =
    block.binding.summary === 'AVG'
      ? sum / values.length
      : block.binding.summary === 'MIN'
        ? Math.min(...values)
        : block.binding.summary === 'MAX'
          ? Math.max(...values)
          : sum
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
