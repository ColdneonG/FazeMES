<template>
  <Dialog title="业务标签预览" v-model="visible" width="960px">
    <div v-loading="loading">
      <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" class="mb-3" />
      <el-alert
        v-else-if="hasUnregistered"
        title="部分对象尚未登记条码，请先生成后再打印。"
        type="warning"
        :closable="false"
        class="mb-3"
      />
      <el-form inline>
        <el-form-item label="打印模板">
          <el-select
            v-model="selectedTemplate"
            class="!w-260px"
            :disabled="loading || printing"
            @change="rebuild"
          >
            <el-option
              v-for="preset in LABEL_TEMPLATES"
              :key="preset.key"
              :label="preset.name"
              :value="preset.key"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="每种份数">
          <el-input-number
            v-model="copies"
            :min="1"
            :max="50"
            :precision="0"
            :disabled="loading || printing"
            @change="rebuild"
          />
        </el-form-item>
      </el-form>
      <p class="mb-3 text-sm text-gray-500"
        >{{ contexts.length }} 种，共 {{ contexts.length * copies }} 张。打印时选择对应纸张和 100%
        实际尺寸。</p
      >
      <el-table :data="contexts" max-height="180" class="mb-3">
        <el-table-column prop="bizCode" label="业务编码" />
        <el-table-column prop="bizName" label="业务名称" />
        <el-table-column label="条码登记" width="180">
          <template #default="{ row }">
            <span v-if="row.registered">已登记</span>
            <el-button
              v-else
              type="primary"
              link
              :disabled="loading || printing"
              @click="generate(row)"
              v-hasPermi="['mes:wm-barcode:create']"
              >生成条码</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <iframe
        v-if="previewHtml"
        ref="previewRef"
        :srcdoc="previewHtml"
        sandbox="allow-same-origin"
        title="标签预览"
        class="label-frame"
        @load="onPreviewLoad"
      ></iframe>
    </div>
    <template #footer>
      <el-button @click="refresh()" :disabled="loading || printing">刷新</el-button>
      <el-button
        v-if="contexts.length === 1"
        @click="downloadBarcode"
        :disabled="!ready || loading || printing"
        >下载条码 SVG</el-button
      >
      <el-button type="primary" @click="print" :disabled="!ready || loading || printing"
        >打印标签</el-button
      >
      <el-button @click="close">关闭</el-button>
    </template>
  </Dialog>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  WmBarcodeApi,
  type BarcodeLabelContext,
  type LabelSelector,
  type LabelTemplate
} from '@/api/mes/wm/barcode'
import { checkPermi } from '@/utils/permission'
import {
  assertContext,
  assertSelector,
  contextSignature,
  encodeBarcode,
  LABEL_TEMPLATES,
  labelPrintHtml,
  waitForLabelDocument,
  type PrintableLabel
} from '../labelPrint'

defineOptions({ name: 'LabelPrintDialog' })
const message = useMessage()
const visible = ref(false),
  loading = ref(false),
  printing = ref(false),
  ready = ref(false)
const errorText = ref(''),
  contexts = ref<BarcodeLabelContext[]>([]),
  previewHtml = ref('')
const selectedTemplate = ref<LabelTemplate>('LABEL_50X30'),
  copies = ref(1)
const previewRef = ref<HTMLIFrameElement>()
const hasUnregistered = computed(() => contexts.value.some((row) => !row.registered))
let selectors: LabelSelector[] = [],
  encoded: PrintableLabel[] = [],
  epoch = 0,
  renderVersion = 0
const permitted = () => checkPermi(['mes:wm-barcode:query'])
const current = (version: number) => version === epoch && visible.value && permitted()
const clearPreview = () => {
  ready.value = false
  previewHtml.value = ''
  encoded = []
  renderVersion++
}
const close = () => {
  epoch++
  visible.value = false
  loading.value = false
  printing.value = false
  clearPreview()
  contexts.value = []
  selectors = []
  errorText.value = ''
}
const fail = (error: unknown) => {
  clearPreview()
  errorText.value = error instanceof Error ? error.message : '无法核对当前标签，请刷新后重试'
}
const fetchContexts = async (version: number) => {
  const result: BarcodeLabelContext[] = []
  for (let index = 0; index < selectors.length; index += 4) {
    const chunk = selectors.slice(index, index + 4)
    const rows = await Promise.all(
      chunk.map(async (selector) => {
        const context = await WmBarcodeApi.getLabelContext(selector)
        assertContext(context, selector)
        return context
      })
    )
    if (!current(version)) return undefined
    result.push(...rows)
  }
  return result
}
const rebuild = async () => {
  const version = epoch
  clearPreview()
  const render = renderVersion
  if (!current(version) || !contexts.value.length || hasUnregistered.value) return
  errorText.value = ''
  try {
    const labels = await Promise.all(
      contexts.value.map(async (context) => ({
        context: { ...context },
        image: await encodeBarcode(context.content ?? '', context.format)
      }))
    )
    if (!current(version) || render !== renderVersion) return
    encoded = labels
    previewHtml.value = labelPrintHtml(labels, selectedTemplate.value, copies.value).replace(
      '<body>',
      `<body data-preview-version="${render}">`
    )
  } catch (error) {
    if (current(version) && render === renderVersion) fail(error)
  }
}
const onPreviewLoad = async () => {
  const version = epoch,
    render = renderVersion,
    document = previewRef.value?.contentDocument
  if (!document || !previewHtml.value || document.body?.dataset.previewVersion !== String(render))
    return
  try {
    await waitForLabelDocument(document)
    if (current(version) && render === renderVersion) ready.value = true
  } catch (error) {
    if (current(version) && render === renderVersion) fail(error)
  }
}
const refresh = async (useDefault = false) => {
  if (!permitted()) {
    close()
    return
  }
  if (!selectors.length) {
    fail(new Error('请选择有效条码后刷新'))
    return
  }
  const version = ++epoch
  loading.value = true
  errorText.value = ''
  contexts.value = []
  clearPreview()
  try {
    const rows = await fetchContexts(version)
    if (!rows || !current(version)) return
    contexts.value = rows
    if (useDefault && rows.length) selectedTemplate.value = rows[0].defaultTemplate
    await rebuild()
  } catch (error) {
    if (current(version)) fail(error)
  } finally {
    if (current(version)) loading.value = false
  }
}
const open = async (requested: LabelSelector[]) => {
  close()
  if (!permitted()) {
    message.warning('需要条码查询权限才能打印')
    return
  }
  visible.value = true
  try {
    if (!requested.length || requested.length > 100) throw new Error('请选择1至100种条码')
    requested.forEach(assertSelector)
    if (new Set(requested.map((item) => JSON.stringify(item))).size !== requested.length)
      throw new Error('同一条码不能重复选择，请使用份数设置')
    selectors = requested.map((item) => ({ ...item }))
    copies.value = 1
    await refresh(true)
  } catch (error) {
    fail(error)
  }
}
const generate = async (row: BarcodeLabelContext) => {
  if (!permitted() || !checkPermi(['mes:wm-barcode:create']) || loading.value || printing.value)
    return
  const version = epoch
  loading.value = true
  clearPreview()
  try {
    const selector = { bizType: row.bizType, bizId: row.bizId }
    const fresh = await WmBarcodeApi.getLabelContext(selector)
    if (!current(version)) return
    assertContext(fresh, selector)
    if (!fresh.registered)
      await WmBarcodeApi.createBarcode({
        bizType: fresh.bizType,
        bizId: fresh.bizId,
        bizCode: fresh.bizCode,
        bizName: fresh.bizName ?? '',
        status: 0,
        remark: ''
      })
    if (current(version)) await refresh()
  } catch (error) {
    if (current(version)) fail(error)
  } finally {
    if (current(version)) loading.value = false
  }
}
const verifyFresh = async (version: number) => {
  const rows = await fetchContexts(version)
  if (!rows || !current(version)) return false
  if (
    rows.some(
      (row, index) =>
        !row.registered || contextSignature(row) !== contextSignature(contexts.value[index])
    )
  ) {
    contexts.value = rows
    await rebuild()
    errorText.value = '标签数据已更新，请核对新的预览后再次操作'
    return false
  }
  return true
}
const print = async () => {
  if (!ready.value || loading.value || printing.value || !permitted()) return
  const version = epoch,
    html = previewHtml.value
  const popup = window.open('', '_blank', 'popup,width=1000,height=800')
  if (!popup) {
    message.warning('浏览器阻止了打印窗口，请允许弹出窗口')
    return
  }
  popup.opener = null
  printing.value = true
  try {
    if (!(await verifyFresh(version)) || !current(version) || popup.closed) {
      popup.close()
      return
    }
    popup.document.open()
    popup.document.write(html)
    popup.document.close()
    await waitForLabelDocument(popup.document)
    if (!current(version) || popup.closed) {
      popup.close()
      return
    }
    popup.focus()
    popup.print()
  } catch (error) {
    popup.close()
    if (current(version)) fail(error)
  } finally {
    if (current(version)) printing.value = false
  }
}
const downloadBarcode = async () => {
  if (!ready.value || encoded.length !== 1 || loading.value || printing.value || !permitted())
    return
  const version = epoch
  printing.value = true
  try {
    if (!(await verifyFresh(version)) || !current(version)) return
    const label = encoded[0],
      link = document.createElement('a')
    link.href = label.image.dataUrl
    link.download = `barcode_${label.context.bizCode.replace(/[^\p{L}\p{N}._-]/gu, '_').slice(0, 80)}.svg`
    link.click()
  } catch (error) {
    if (current(version)) fail(error)
  } finally {
    if (current(version)) printing.value = false
  }
}
watch(visible, (value) => {
  if (!value) close()
})
watch(permitted, (value) => {
  if (!value) close()
})
onBeforeUnmount(close)
defineExpose({ open, close })
</script>
<style scoped>
.label-frame {
  width: 100%;
  height: 500px;
  border: 1px solid var(--el-border-color);
}
</style>
