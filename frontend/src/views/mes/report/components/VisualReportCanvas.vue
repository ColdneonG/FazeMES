<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { ReportBlock, ReportBlockType, ReportCanvas, ReportPreview } from '@/api/mes/report'
import { blockLabel, pageSize } from '../designerModel'
import ReportBlockContent from './ReportBlockContent.vue'
const props = withDefaults(
  defineProps<{
    modelValue: ReportCanvas
    selectedId?: string
    result?: ReportPreview
    editable?: boolean
    zoom?: number
    showGrid?: boolean
  }>(),
  { editable: false, zoom: 1, showGrid: false }
)
const emit = defineEmits<{
  'update:modelValue': [value: ReportCanvas]
  select: [id: string]
  add: [type: ReportBlockType, x: number, y: number]
  remove: [id: string]
}>()
const paperRef = ref<HTMLElement>()
const size = computed(() => pageSize(props.modelValue))
const ghost = ref<{ id: string; x: number; y: number; width: number; height: number }>()
let cleanupDrag: (() => void) | undefined
const bounds = (block: ReportBlock) => (ghost.value?.id === block.id ? ghost.value : block)
const clamp = (value: number, minimum: number, maximum: number) =>
  Math.max(minimum, Math.min(maximum, value))
const snap = (value: number) => Math.round(value / 8) * 8
function dragBlock(event: PointerEvent, block: ReportBlock, resize = false) {
  if (!props.editable || event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  cleanupDrag?.()
  emit('select', block.id)
  const start = { x: event.clientX, y: event.clientY }
  const original = {
    id: block.id,
    x: block.x,
    y: block.y,
    width: block.width,
    height: block.height
  }
  ghost.value = { ...original }
  const move = (pointer: PointerEvent) => {
    const dx = (pointer.clientX - start.x) / props.zoom
    const dy = (pointer.clientY - start.y) / props.zoom
    ghost.value = resize
      ? {
          ...original,
          width: clamp(snap(original.width + dx), 40, size.value.width - original.x),
          height: clamp(snap(original.height + dy), 16, size.value.height - original.y)
        }
      : {
          ...original,
          x: clamp(snap(original.x + dx), 0, size.value.width - original.width),
          y: clamp(snap(original.y + dy), 0, size.value.height - original.height)
        }
  }
  const finish = () => {
    const value = ghost.value
    cleanupDrag?.()
    if (value)
      emit('update:modelValue', {
        ...props.modelValue,
        blocks: props.modelValue.blocks.map((item) =>
          item.id === block.id ? { ...item, ...value } : item
        )
      })
  }
  cleanupDrag = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', finish)
    window.removeEventListener('pointercancel', cancel)
    ghost.value = undefined
    cleanupDrag = undefined
  }
  const cancel = () => cleanupDrag?.()
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', finish, { once: true })
  window.addEventListener('pointercancel', cancel, { once: true })
}
function dropBlock(event: DragEvent) {
  if (!props.editable) return
  const type = event.dataTransfer?.getData(
    'application/fazemes-report-component'
  ) as ReportBlockType
  if (!['text', 'table', 'bar', 'line', 'pie', 'metric', 'divider'].includes(type)) return
  const rectangle = paperRef.value!.getBoundingClientRect()
  emit(
    'add',
    type,
    Math.max(0, snap((event.clientX - rectangle.left) / props.zoom)),
    Math.max(0, snap((event.clientY - rectangle.top) / props.zoom))
  )
}
function keyBlock(event: KeyboardEvent, block: ReportBlock) {
  if (!props.editable || event.target !== event.currentTarget) return
  if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    emit('remove', block.id)
    return
  }
  const direction = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[
    event.key
  ]
  if (!direction) return
  event.preventDefault()
  const step = event.shiftKey ? 1 : 8
  const x = clamp(block.x + direction[0] * step, 0, size.value.width - block.width)
  const y = clamp(block.y + direction[1] * step, 0, size.value.height - block.height)
  emit('update:modelValue', {
    ...props.modelValue,
    blocks: props.modelValue.blocks.map((item) => (item.id === block.id ? { ...item, x, y } : item))
  })
}
onBeforeUnmount(() => cleanupDrag?.())
</script>

<template>
  <div
    class="canvas-stage"
    :style="{ width: `${size.width * zoom}px`, height: `${size.height * zoom}px` }"
  >
    <div
      ref="paperRef"
      class="report-paper"
      :class="{ 'paper-grid': showGrid && editable }"
      :style="{
        width: `${size.width}px`,
        height: `${size.height}px`,
        backgroundColor: modelValue.background,
        transform: `scale(${zoom})`
      }"
      @dragover.prevent
      @drop.prevent="dropBlock"
      @click.self="emit('select', '')"
    >
      <div v-if="!modelValue.blocks.length" class="paper-empty"
        >从组件面板拖入内容，或点击组件添加</div
      >
      <article
        v-for="block in modelValue.blocks"
        :key="block.id"
        class="canvas-block"
        :class="{
          'block-editable': editable,
          'block-selected': editable && selectedId === block.id
        }"
        :style="{
          left: `${bounds(block).x}px`,
          top: `${bounds(block).y}px`,
          width: `${bounds(block).width}px`,
          height: `${bounds(block).height}px`
        }"
        :tabindex="editable ? 0 : undefined"
        :aria-label="`${blockLabel(block.type)}：${block.title || block.text || '未命名'}，位置${block.x},${block.y}`"
        @click.stop="emit('select', block.id)"
        @focus="editable && emit('select', block.id)"
        @keydown="keyBlock($event, block)"
      >
        <div v-if="editable && selectedId === block.id" class="block-tools">
          <button
            type="button"
            class="block-move"
            title="拖动组件位置"
            @pointerdown="dragBlock($event, block)"
            >⠿ {{ blockLabel(block.type) }}</button
          >
          <button type="button" title="删除组件" @click.stop="emit('remove', block.id)">×</button>
        </div>
        <ReportBlockContent :block="block" :result="result" />
        <button
          v-if="editable && selectedId === block.id"
          type="button"
          class="block-resize"
          title="拖动调整组件尺寸"
          @pointerdown="dragBlock($event, block, true)"
        ></button>
      </article>
    </div>
  </div>
</template>

<style scoped>
.canvas-stage {
  position: relative;
  margin: 0 auto;
}

.report-paper {
  position: relative;
  background: #fff;
  box-shadow: 0 4px 24px rgb(24 50 71 / 12%);
  box-sizing: border-box;
  transform-origin: left top;
}

.paper-grid {
  background-image:
    linear-gradient(#dbe8ee66 1px, transparent 1px),
    linear-gradient(90deg, #dbe8ee66 1px, transparent 1px);
  background-size: 8px 8px;
}

.canvas-block {
  position: absolute;
  outline: none;
}

.block-editable {
  cursor: pointer;
}

.block-editable:hover {
  outline: 1px dashed #95b7c2;
}

.block-selected,
.block-selected:hover {
  z-index: 2;
  outline: 2px solid #14869b;
}

.block-tools {
  position: absolute;
  top: -27px;
  left: -2px;
  z-index: 3;
  display: flex;
  height: 25px;
  overflow: hidden;
  background: #14869b;
  border-radius: 4px 4px 0 0;
}

.block-tools button {
  padding: 0 9px;
  font-size: 10px;
  color: #fff;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.block-tools .block-move {
  cursor: move;
  touch-action: none;
}

.block-resize {
  position: absolute;
  right: -5px;
  bottom: -5px;
  z-index: 3;
  width: 10px;
  height: 10px;
  padding: 0;
  cursor: nwse-resize;
  background: #fff;
  border: 2px solid #14869b;
  touch-action: none;
}

.paper-empty {
  display: grid;
  height: 250px;
  font-size: 14px;
  color: #8b9ba5;
  place-items: center;
}
</style>
