<!-- MES 仓库选择器：纯下拉，前端过滤（支持 name、code），悬停 tooltip 展示详情 -->
<template>
  <el-tooltip :disabled="!selectedItem" placement="top" :show-after="500">
    <template #content>
      <div v-if="selectedItem" class="leading-6">
        <div>编码：{{ selectedItem.code || '-' }}</div>
        <div>名称：{{ selectedItem.name || '-' }}</div>
        <div>地址：{{ selectedItem.address || '-' }}</div>
        <div v-if="isQualityQuarantineWarehouse(selectedItem.code)"
          >质量隔离仓：允许入库，禁止出库</div
        >
      </div>
    </template>
    <el-select
      v-bind="$attrs"
      v-model="selectValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :clearable="clearable"
      filterable
      :filter-method="handleFilter"
      class="!w-1/1"
      @change="handleChange"
    >
      <el-option v-for="item in filteredList" :key="item.id" :label="item.name" :value="item.id">
        <div class="flex items-center gap-8px">
          <span>{{ item.name }}</span>
          <el-tag v-if="isQualityQuarantineWarehouse(item.code)" size="small" type="danger"
            >质量隔离</el-tag
          >
          <el-tag v-if="item.code" size="small" type="info" class="ml-4px">
            编号: {{ item.code }}
          </el-tag>
        </div>
      </el-option>
      <template #empty>
        <div class="px-12px py-10px text-center text-13px">
          <span
            v-if="
              quarantineFilter === 'only' &&
              listLoaded &&
              !allList.some((item) => isQualityQuarantineWarehouse(item.code))
            "
          >
            暂无质量隔离仓，请管理员在仓库管理中点击“初始化隔离仓”。
          </span>
          <span v-else>暂无匹配的仓库</span>
        </div>
      </template>
    </el-select>
  </el-tooltip>
</template>

<script setup lang="ts">
import { WmWarehouseApi, WmWarehouseVO } from '@/api/mes/wm/warehouse'
import {
  isQualityQuarantineWarehouse,
  matchesQuarantineFilter,
  type QuarantineFilter
} from '../../qualityPolicy'

defineOptions({ name: 'WmWarehouseSelect', inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue?: number
    disabled?: boolean
    clearable?: boolean
    placeholder?: string
    quarantineFilter?: QuarantineFilter
  }>(),
  {
    disabled: false,
    clearable: true,
    quarantineFilter: 'all',
    placeholder: '请选择仓库'
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: number | undefined]
  change: [item: WmWarehouseVO | undefined]
}>()

const allList = ref<WmWarehouseVO[]>([])
const filteredList = ref<WmWarehouseVO[]>([])
const selectedItem = ref<WmWarehouseVO | undefined>() // 当前选中的仓库对象（用于 tooltip 展示）
const filterQuery = ref('')
const listLoaded = ref(false)

const syncSelectedItem = () => {
  selectedItem.value = allList.value.find((item) => item.id === props.modelValue)
  if (
    listLoaded.value &&
    selectedItem.value &&
    !matchesQuarantineFilter(selectedItem.value.code, props.quarantineFilter)
  ) {
    selectedItem.value = undefined
    emit('update:modelValue', undefined)
    emit('change', undefined)
  }
}

const selectValue = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

/** 前端过滤（name + code） */
const handleFilter = (query: string) => {
  filterQuery.value = query
  const keyword = query.toLowerCase()
  filteredList.value = allList.value.filter(
    (item) =>
      matchesQuarantineFilter(item.code, props.quarantineFilter) &&
      (!keyword ||
        item.name?.toLowerCase().includes(keyword) ||
        item.code?.toLowerCase().includes(keyword))
  )
}

watch(
  () => props.quarantineFilter,
  () => {
    handleFilter(filterQuery.value)
    syncSelectedItem()
  }
)

/** 选中变化 */
const handleChange = (val: number | undefined) => {
  const item = allList.value.find((o) => o.id === val)
  selectedItem.value = item
  emit('change', item)
}

/** 根据 modelValue 同步 selectedItem（用于编辑回显） */
watch(() => props.modelValue, syncSelectedItem)

/** 加载仓库列表 */
onMounted(async () => {
  allList.value = await WmWarehouseApi.getWarehouseSimpleList()
  listLoaded.value = true
  handleFilter(filterQuery.value)
  // 列表加载完成后，回显 selectedItem
  syncSelectedItem()
})
</script>
