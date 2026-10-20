<!-- MES 销售退货上架明细表单弹窗 -->
<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="600px">
    <el-alert
      :title="returnQualityGuidance(qualityStatus)"
      type="warning"
      :closable="false"
      show-icon
      class="!mb-16px"
    />
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="110px"
      v-loading="formLoading"
    >
      <el-form-item label="物料" prop="itemId">
        <MdItemSelect v-model="formData.itemId" disabled />
      </el-form-item>
      <el-form-item label="入库仓库" prop="warehouseId">
        <WmWarehouseSelect
          v-model="formData.warehouseId"
          :quarantine-filter="qualityStatus === 2 ? 'only' : 'all'"
          @change="handleWarehouseChange"
        />
      </el-form-item>
      <el-form-item label="库区" prop="locationId" v-if="formData.warehouseId">
        <WmWarehouseLocationSelect
          v-model="formData.locationId"
          :warehouse-id="formData.warehouseId"
          @change="formData.areaId = undefined"
        />
      </el-form-item>
      <el-form-item label="库位" prop="areaId" v-if="formData.locationId">
        <WmWarehouseAreaSelect v-model="formData.areaId" :location-id="formData.locationId" />
      </el-form-item>
      <el-form-item label="批次号" prop="batchCode">
        <el-input v-model="formData.batchCode" readonly placeholder="原出库批次" />
      </el-form-item>
      <el-form-item label="数量" prop="quantity">
        <el-input v-model="formData.quantity" inputmode="decimal" maxlength="21" class="!w-1/1" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button
        @click="submitForm"
        type="primary"
        :disabled="formLoading || qualityStatus == null || qualityStatus === 0"
        >确 定</el-button
      >
      <el-button @click="dialogVisible = false">取 消</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { WmReturnSalesDetailApi, WmReturnSalesDetailVO } from '@/api/mes/wm/returnsales/detail'
import { WmReturnSalesLineApi } from '@/api/mes/wm/returnsales/line'
import { quantityRule, quantityUnits } from '@/utils/mesQuantity'
import { returnQualityGuidance } from '../qualityPolicy'
import MdItemSelect from '@/views/mes/md/item/components/MdItemSelect.vue'
import WmWarehouseSelect from '@/views/mes/wm/warehouse/components/WmWarehouseSelect.vue'
import WmWarehouseLocationSelect from '@/views/mes/wm/warehouse/components/WmWarehouseLocationSelect.vue'
import WmWarehouseAreaSelect from '@/views/mes/wm/warehouse/components/WmWarehouseAreaSelect.vue'

defineOptions({ name: 'ReturnSalesDetailForm' })

const props = defineProps<{
  returnId: number
}>()

const emit = defineEmits(['success'])

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗

const dialogVisible = ref(false) // 弹窗的是否展示
const dialogTitle = ref('') // 弹窗的标题
const formLoading = ref(false) // 表单的加载中
const formType = ref('') // 表单的类型：create / update
const currentLineId = ref<number>() // 当前操作的行 ID
const qualityStatus = ref<number>()
const lineQuantity = ref('')
let openVersion = 0
const formRef = ref() // 表单 Ref
const formData = ref({
  id: undefined as number | undefined,
  lineId: undefined as number | undefined,
  returnId: undefined as number | undefined,
  itemId: undefined as number | undefined,
  quantity: '',
  batchId: undefined as number | null | undefined,
  batchCode: undefined as string | undefined,
  warehouseId: undefined as number | undefined,
  locationId: undefined as number | undefined,
  areaId: undefined as number | undefined
})
const formRules = reactive({
  itemId: [{ required: true, message: '物料不能为空', trigger: 'change' }],
  warehouseId: [{ required: true, message: '入库仓库不能为空', trigger: 'change' }],
  locationId: [{ required: true, message: '库区不能为空', trigger: 'change' }],
  areaId: [{ required: true, message: '库位不能为空', trigger: 'change' }],
  quantity: [quantityRule(true)]
})

/** 打开弹窗 */
const open = async (type: string, lineId: number, itemId?: number, detailId?: number) => {
  const version = ++openVersion
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '添加上架明细' : '编辑上架明细'
  formType.value = type
  currentLineId.value = lineId
  resetForm()
  formLoading.value = true
  try {
    const line = await WmReturnSalesLineApi.getReturnSalesLine(lineId)
    if (version !== openVersion) return
    if (line.returnId != null && line.returnId !== props.returnId) throw new Error('source changed')
    qualityStatus.value = line.qualityStatus
    lineQuantity.value = String(line.quantity ?? '')
    // 质量状态从当前单据行读取，不使用过期的列表快照。
    if (detailId) {
      const detail = await WmReturnSalesDetailApi.getReturnSalesDetail(detailId)
      if (version !== openVersion) return
      if (detail.lineId !== lineId || detail.returnId !== props.returnId)
        throw new Error('source changed')
      formData.value = { ...detail, quantity: String(detail.quantity) }
    } else {
      formData.value.itemId = line.itemId || itemId
      formData.value.batchCode = line.batchCode
      formData.value.batchId = line.batchId
    }
  } catch {
    if (version === openVersion) qualityStatus.value = undefined
  } finally {
    if (version === openVersion) formLoading.value = false
  }
}
defineExpose({ open })

/** 提交表单 */
const submitForm = async () => {
  if (formLoading.value) return
  if (qualityStatus.value == null || qualityStatus.value === 0) {
    message.warning(returnQualityGuidance(qualityStatus.value))
    return
  }
  // 校验表单
  await formRef.value.validate()
  const quantity = quantityUnits(formData.value.quantity),
    maximum = quantityUnits(lineQuantity.value)
  if (quantity === undefined || quantity <= 0n || maximum === undefined || quantity > maximum) {
    message.warning('请输入不超过退货行数量的正数，最多14位整数和6位小数')
    return
  }
  const version = openVersion
  // 提交请求
  formLoading.value = true
  try {
    const data = {
      ...formData.value,
      returnId: props.returnId,
      lineId: currentLineId.value
    } as unknown as WmReturnSalesDetailVO
    if (formType.value === 'create') {
      await WmReturnSalesDetailApi.createReturnSalesDetail(data)
      message.success(t('common.createSuccess'))
    } else {
      await WmReturnSalesDetailApi.updateReturnSalesDetail(data)
      message.success(t('common.updateSuccess'))
    }
    if (version !== openVersion) return
    dialogVisible.value = false
    // 发送操作成功的事件
    emit('success', currentLineId.value)
  } finally {
    if (version === openVersion) formLoading.value = false
  }
}

const handleWarehouseChange = () => {
  formData.value.locationId = undefined
  formData.value.areaId = undefined
}

/** 重置表单 */
const resetForm = () => {
  qualityStatus.value = undefined
  lineQuantity.value = ''
  formData.value = {
    id: undefined,
    lineId: undefined,
    returnId: undefined,
    itemId: undefined,
    quantity: '',
    batchId: undefined,
    batchCode: undefined,
    warehouseId: undefined,
    locationId: undefined,
    areaId: undefined
  }
  formRef.value?.resetFields()
}
watch(
  () => props.returnId,
  () => {
    openVersion++
    dialogVisible.value = false
    formLoading.value = false
    resetForm()
  }
)
onBeforeUnmount(() => {
  openVersion++
})
</script>
