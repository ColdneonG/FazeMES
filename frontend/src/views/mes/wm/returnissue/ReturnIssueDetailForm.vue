<!-- MES 生产退料上架明细表单弹窗 -->
<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="960px">
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
      <el-row>
        <el-col :span="8">
          <el-form-item label="物料" prop="itemId">
            <MdItemSelect v-model="formData.itemId" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="库存记录" prop="materialStockId">
            <WmMaterialStockSelect
              v-model="formData.materialStockId"
              :item-id="formData.itemId"
              virtual-filter="only"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="数量" prop="quantity" :rules="[quantityRule(true, quantityMax)]">
            <MesQuantityInput v-model="formData.quantity" class="!w-1/1" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="8">
          <el-form-item label="入库仓库" prop="warehouseId">
            <WmWarehouseSelect
              v-model="formData.warehouseId"
              :quarantine-filter="qualityStatus === 2 ? 'only' : 'all'"
              @change="handleWarehouseChange"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="库区" prop="locationId">
            <WmWarehouseLocationSelect
              v-model="formData.locationId"
              :warehouse-id="formData.warehouseId"
              @change="formData.areaId = undefined"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="库位" prop="areaId">
            <WmWarehouseAreaSelect v-model="formData.areaId" :location-id="formData.locationId" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="8">
          <el-form-item label="批次号">
            <el-input :model-value="formData.batchCode" disabled />
          </el-form-item>
        </el-col>
      </el-row>
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
import MesQuantityInput from '@/views/mes/components/MesQuantityInput.vue'
import { quantityRule, minQuantity } from '@/utils/mesQuantity'
import { WmReturnIssueDetailApi, WmReturnIssueDetailVO } from '@/api/mes/wm/returnissue/detail'
import { WmMaterialStockApi } from '@/api/mes/wm/materialstock'
import { WmReturnIssueLineApi } from '@/api/mes/wm/returnissue/line'
import { returnQualityGuidance } from '../qualityPolicy'
import MdItemSelect from '@/views/mes/md/item/components/MdItemSelect.vue'
import WmMaterialStockSelect from '@/views/mes/wm/materialstock/components/WmMaterialStockSelect.vue'
import WmWarehouseSelect from '@/views/mes/wm/warehouse/components/WmWarehouseSelect.vue'
import WmWarehouseLocationSelect from '@/views/mes/wm/warehouse/components/WmWarehouseLocationSelect.vue'
import WmWarehouseAreaSelect from '@/views/mes/wm/warehouse/components/WmWarehouseAreaSelect.vue'

defineOptions({ name: 'ReturnIssueDetailForm' })

const props = defineProps<{
  issueId: number
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
const quantityMax = ref<number | string | undefined>(undefined) // 数量上限（在库数量）
const formRef = ref() // 表单 Ref
const formData = ref({
  id: undefined as number | undefined,
  lineId: undefined as number | undefined,
  issueId: undefined as number | undefined,
  itemId: undefined as number | undefined,
  materialStockId: undefined as number | undefined,
  quantity: undefined as number | string | undefined,
  batchId: undefined as number | undefined,
  batchCode: undefined as string | undefined,
  warehouseId: undefined as number | undefined,
  locationId: undefined as number | undefined,
  areaId: undefined as number | undefined
})
const formRules = reactive({
  itemId: [{ required: true, message: '物料不能为空', trigger: 'change' }],
  materialStockId: [{ required: true, message: '请选择库存记录', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择目标仓库', trigger: 'change' }],
  locationId: [{ required: true, message: '请选择目标库区', trigger: 'change' }],
  areaId: [{ required: true, message: '请选择目标库位', trigger: 'change' }],
  quantity: [{ required: true, message: '数量不能为空', trigger: 'blur' }]
})

const handleWarehouseChange = () => {
  formData.value.locationId = undefined
  formData.value.areaId = undefined
}

/** 打开弹窗 */
const open = async (type: string, lineId: number, _itemId?: number, detailId?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '添加上架明细' : '编辑上架明细'
  formType.value = type
  currentLineId.value = lineId
  resetForm()
  formLoading.value = true
  try {
    const line = await WmReturnIssueLineApi.getReturnIssueLine(lineId)
    qualityStatus.value = line.qualityStatus
    if (detailId) {
      formData.value = await WmReturnIssueDetailApi.getReturnIssueDetail(detailId)
    } else {
      formData.value.itemId = line.itemId
      formData.value.materialStockId = line.materialStockId
      formData.value.batchId = line.batchId
      formData.value.batchCode = line.batchCode
      const stock = await WmMaterialStockApi.getMaterialStock(line.materialStockId)
      quantityMax.value = minQuantity(line.quantity, stock.quantity)
      formData.value.quantity = quantityMax.value
    }
  } finally {
    formLoading.value = false
  }
}
defineExpose({ open })

/** 提交表单 */
const submitForm = async () => {
  if (qualityStatus.value == null || qualityStatus.value === 0) {
    message.warning(returnQualityGuidance(qualityStatus.value))
    return
  }
  // 校验表单
  await formRef.value.validate()
  // 提交请求
  formLoading.value = true
  try {
    const data = {
      ...formData.value,
      issueId: props.issueId,
      lineId: currentLineId.value
    } as unknown as WmReturnIssueDetailVO
    if (formType.value === 'create') {
      await WmReturnIssueDetailApi.createReturnIssueDetail(data)
      message.success(t('common.createSuccess'))
    } else {
      await WmReturnIssueDetailApi.updateReturnIssueDetail(data)
      message.success(t('common.updateSuccess'))
    }
    dialogVisible.value = false
    // 发送操作成功的事件
    emit('success', currentLineId.value)
  } finally {
    formLoading.value = false
  }
}

/** 重置表单 */
const resetForm = () => {
  qualityStatus.value = undefined
  formData.value = {
    id: undefined,
    lineId: undefined,
    issueId: undefined,
    itemId: undefined,
    materialStockId: undefined,
    quantity: undefined as number | string | undefined,
    batchId: undefined,
    batchCode: undefined,
    warehouseId: undefined,
    locationId: undefined,
    areaId: undefined
  }
  quantityMax.value = undefined
  formRef.value?.resetFields()
}
</script>
