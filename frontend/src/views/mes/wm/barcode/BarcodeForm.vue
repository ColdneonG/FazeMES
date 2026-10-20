<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="600px">
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="100px"
      v-loading="formLoading"
    >
      <el-form-item label="业务类型" prop="bizType">
        <el-select v-model="formData.bizType" placeholder="请选择业务类型" class="!w-1/1">
          <el-option
            v-for="dict in getIntDictOptions(DICT_TYPE.MES_WM_BARCODE_BIZ_TYPE)"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="formData.bizType === BarcodeBizTypeEnum.WAREHOUSE"
        label="仓库"
        prop="bizId"
      >
        <WmWarehouseSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.LOCATION"
        label="库区"
        prop="bizId"
      >
        <div class="space-y-2">
          <WmWarehouseSelect
            v-model="locationWarehouseId"
            @change="handleLocationWarehouseChange"
            class="!w-1/1"
            placeholder="请选择仓库"
          />
          <WmWarehouseLocationSelect
            v-model="formData.bizId"
            :warehouse-id="locationWarehouseId"
            @change="handleBizSelect"
            class="!w-1/1"
            placeholder="请选择库区"
          />
        </div>
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.AREA"
        label="库位"
        prop="bizId"
      >
        <div class="space-y-2">
          <WmWarehouseSelect
            v-model="areaWarehouseId"
            @change="handleAreaWarehouseChange"
            class="!w-1/1"
            placeholder="请选择仓库"
          />
          <WmWarehouseLocationSelect
            v-model="areaLocationId"
            :warehouse-id="areaWarehouseId"
            @change="handleAreaLocationChange"
            class="!w-1/1"
            placeholder="请选择库区"
          />
          <WmWarehouseAreaSelect
            v-model="formData.bizId"
            :location-id="areaLocationId"
            @change="handleBizSelect"
            class="!w-1/1"
            placeholder="请选择库位"
          />
        </div>
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.WORKORDER"
        label="工单"
        prop="bizId"
      >
        <ProWorkOrderSelect
          v-model="formData.bizId"
          :status="MesProWorkOrderStatusEnum.CONFIRMED"
          @change="handleBizSelect"
          class="!w-1//1"
        />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.MACHINERY"
        label="设备"
        prop="bizId"
      >
        <DvMachinerySelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1//1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.ITEM"
        label="产品物料"
        prop="bizId"
        class="!w-1/1"
      >
        <MdItemSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.VENDOR"
        label="供应商"
        prop="bizId"
      >
        <MdVendorSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1//1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.WORKSTATION"
        label="工作站"
        prop="bizId"
      >
        <MdWorkstationSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.WORKSHOP"
        label="车间"
        prop="bizId"
      >
        <MdWorkshopSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.USER"
        label="人员"
        prop="bizId"
      >
        <UserSelectV2 v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.CLIENT"
        label="客户"
        prop="bizId"
      >
        <MdClientSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.TOOL"
        label="工具"
        prop="bizId"
      >
        <TmToolSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.PACKAGE"
        label="装箱单"
        prop="bizId"
      >
        <WmPackageSelect
          v-model="formData.bizId"
          :childable-only="true"
          @change="handleBizSelect"
          class="!w-1/1"
        />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.STOCK"
        label="库存"
        prop="bizId"
      >
        <WmMaterialStockSelect
          v-model="formData.bizId"
          :outbound-only="false"
          @change="handleBizSelect"
          class="!w-1/1"
        />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.BATCH"
        label="批次"
        prop="bizId"
      >
        <WmBatchSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.PROCARD"
        label="流转卡"
        prop="bizId"
      >
        <ProCardSelect v-model="formData.bizId" @change="handleBizSelect" class="!w-1/1" />
      </el-form-item>
      <el-form-item
        v-else-if="formData.bizType === BarcodeBizTypeEnum.SN"
        label="SN编号"
        prop="bizId"
      >
        <el-input-number
          v-model="formData.bizId"
          :min="1"
          class="!w-240px"
          placeholder="请输入业务编号"
        />
        <el-button @click="loadSnIdentity" :loading="contentLoading">核对 SN</el-button>
      </el-form-item>
      <el-form-item v-else-if="formData.bizType" label="暂未接入">
        <el-alert
          title="此业务类型尚未接入，请选择已接入的业务对象。"
          type="warning"
          :closable="false"
        />
      </el-form-item>
      <el-form-item label="业务编码" prop="bizCode">
        <el-input v-model="formData.bizCode" placeholder="请输入业务编码" disabled />
      </el-form-item>
      <el-form-item label="业务名称" prop="bizName">
        <el-input v-model="formData.bizName" placeholder="请输入业务名称" disabled />
      </el-form-item>
      <el-form-item label="条码内容" prop="content">
        <el-input v-model="formData.content" placeholder="请输入条码内容或自动生成" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio
            v-for="dict in getIntDictOptions(DICT_TYPE.COMMON_STATUS)"
            :key="dict.value"
            :label="dict.value"
          >
            {{ dict.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="formData.remark" type="textarea" placeholder="请输入备注" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="submitForm" type="primary" :disabled="formLoading || contentLoading"
        >确 定</el-button
      >
      <el-button @click="dialogVisible = false">取 消</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import { CommonStatusEnum } from '@/utils/constants'
import { WmBarcodeApi, type WmBarcodeVO } from '@/api/mes/wm/barcode'
import { BarcodeBizTypeEnum, MesProWorkOrderStatusEnum } from '@/views/mes/utils/constants'
import WmWarehouseSelect from '@/views/mes/wm/warehouse/components/WmWarehouseSelect.vue'
import WmWarehouseAreaSelect from '@/views/mes/wm/warehouse/components/WmWarehouseAreaSelect.vue'
import WmWarehouseLocationSelect from '@/views/mes/wm/warehouse/components/WmWarehouseLocationSelect.vue'
import ProWorkOrderSelect from '@/views/mes/pro/workorder/components/ProWorkOrderSelect.vue'
import DvMachinerySelect from '@/views/mes/dv/machinery/components/DvMachinerySelect.vue'
import MdItemSelect from '@/views/mes/md/item/components/MdItemSelect.vue'
import MdVendorSelect from '@/views/mes/md/vendor/components/MdVendorSelect.vue'
import MdWorkstationSelect from '@/views/mes/md/workstation/components/MdWorkstationSelect.vue'
import MdWorkshopSelect from '@/views/mes/md/workstation/components/MdWorkshopSelect.vue'
import MdClientSelect from '@/views/mes/md/client/components/MdClientSelect.vue'
import TmToolSelect from '@/views/mes/tm/tool/components/TmToolSelect.vue'
import UserSelectV2 from '@/views/system/user/components/UserSelectV2.vue'
import WmPackageSelect from '@/views/mes/wm/packages/components/WmPackageSelect.vue'
import WmMaterialStockSelect from '@/views/mes/wm/materialstock/components/WmMaterialStockSelect.vue'
import WmBatchSelect from '@/views/mes/wm/batch/components/WmBatchSelect.vue'
import ProCardSelect from '@/views/mes/pro/card/components/ProCardSelect.vue'
import { WmWarehouseLocationApi } from '@/api/mes/wm/warehouse/location'
import { WmWarehouseAreaApi } from '@/api/mes/wm/warehouse/area'
import { assertContext, barcodeBusinessType, positiveId } from './labelPrint'

defineOptions({ name: 'BarcodeForm' })

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗

const dialogVisible = ref(false) // 弹窗的是否展示
const dialogTitle = ref('') // 弹窗的标题
const formLoading = ref(false) // 表单的加载中：1）修改时的数据加载；2）提交的按钮禁用
const formType = ref('') // 表单的类型：create - 新增；update - 修改
const formData = ref<WmBarcodeVO>({
  id: undefined,
  bizType: undefined,
  bizId: undefined,
  bizCode: undefined,
  bizName: undefined,
  content: undefined,
  status: CommonStatusEnum.ENABLE,
  remark: ''
})
const formRules = reactive({
  bizType: [{ required: true, message: '业务类型不能为空', trigger: 'change' }],
  bizId: [{ required: true, message: '业务编号不能为空', trigger: 'blur' }],
  bizCode: [{ required: true, message: '业务编码不能为空', trigger: 'blur' }],
  bizName: [{ required: true, message: '业务名称不能为空', trigger: 'blur' }],
  content: [{ required: true, message: '条码内容不能为空', trigger: 'blur' }]
})
const formRef = ref()

const locationWarehouseId = ref<number>() // 库区选择器的临时数据：选择仓库后，传给库区选择器
const areaWarehouseId = ref<number>() // 库位选择器的临时数据：选择仓库后，传给库位选择器
const areaLocationId = ref<number>() // 库位选择器的临时数据：选择库区后，传给库位选择器
const isLoadingData = ref(false) // 标记是否正在加载数据，避免 watch 清空字段
const contentLoading = ref(false)
let openVersion = 0,
  selectionVersion = 0

/** 业务 Select 选中回调：自动填充 bizId、bizCode、bizName */
const handleBizSelect = async (item: any) => {
  const version = ++selectionVersion
  contentLoading.value = false
  formData.value.content = undefined
  if (!item) {
    formData.value.bizId = undefined
    formData.value.bizCode = undefined
    formData.value.bizName = undefined
    formData.value.content = undefined
    return
  }
  formData.value.bizId = item.id
  // 根据业务类型映射字段
  switch (formData.value.bizType) {
    case BarcodeBizTypeEnum.STOCK:
      formData.value.bizCode = String(item.id)
      formData.value.bizName = item.itemName
      break
    case BarcodeBizTypeEnum.PACKAGE:
      formData.value.bizCode = item.code
      formData.value.bizName = item.code
      break
    case BarcodeBizTypeEnum.BATCH:
      formData.value.bizCode = item.code
      formData.value.bizName = item.itemName || item.code
      break
    case BarcodeBizTypeEnum.PROCARD:
      formData.value.bizCode = item.code
      formData.value.bizName = item.code
      break
    default:
      formData.value.bizCode = item.code || item.username
      formData.value.bizName = item.name || item.nickname
  }

  // 自动生成条码内容
  if (formData.value.bizType && formData.value.bizCode) {
    const identity = { type: formData.value.bizType, id: formData.value.bizId }
    contentLoading.value = true
    try {
      const content = await WmBarcodeApi.generateBarcodeContent(
        formData.value.bizType,
        formData.value.bizCode
      )
      if (
        version === selectionVersion &&
        identity.type === formData.value.bizType &&
        identity.id === formData.value.bizId
      )
        formData.value.content = content
    } catch (error) {
      if (version === selectionVersion) {
        formData.value.content = undefined
        message.warning('无法生成当前业务的有效条码，请检查条码配置')
      }
    } finally {
      if (version === selectionVersion) contentLoading.value = false
    }
  }
}
const loadSnIdentity = async () => {
  if (!positiveId(formData.value.bizId)) return
  const version = ++selectionVersion
  const selector = { bizType: BarcodeBizTypeEnum.SN, bizId: formData.value.bizId }
  formData.value.bizCode = undefined
  formData.value.bizName = undefined
  formData.value.content = undefined
  contentLoading.value = true
  try {
    const context = await WmBarcodeApi.getLabelContext(selector)
    if (
      version !== selectionVersion ||
      formData.value.bizType !== selector.bizType ||
      formData.value.bizId !== selector.bizId
    )
      return
    assertContext(context, selector)
    formData.value.bizCode = context.bizCode
    formData.value.bizName = context.bizName ?? context.bizCode
    formData.value.content = context.content ?? context.candidateContent ?? undefined
  } catch {
    if (version === selectionVersion) message.warning('无法核对该 SN，请检查编号及条码配置')
  } finally {
    if (version === selectionVersion) contentLoading.value = false
  }
}

/** 库区仓库选择回调：清空库区 */
const handleLocationWarehouseChange = () => {
  selectionVersion++
  contentLoading.value = false
  formData.value.bizId = undefined
  formData.value.bizCode = undefined
  formData.value.bizName = undefined
  formData.value.content = undefined
}

/** 库位仓库选择回调：清空库区和库位 */
const handleAreaWarehouseChange = () => {
  selectionVersion++
  contentLoading.value = false
  areaLocationId.value = undefined
  formData.value.bizId = undefined
  formData.value.bizCode = undefined
  formData.value.bizName = undefined
  formData.value.content = undefined
}

/** 库位库区选择回调：清空库位 */
const handleAreaLocationChange = () => {
  selectionVersion++
  contentLoading.value = false
  formData.value.bizId = undefined
  formData.value.bizCode = undefined
  formData.value.bizName = undefined
  formData.value.content = undefined
}

/** 加载级联选择器的数据（编辑时回填上级数据） */
const loadCascadeData = async (version: number) => {
  if (!formData.value.bizType || !formData.value.bizId) {
    return
  }
  try {
    // 库区类型：需要查询库区信息，获取所属仓库ID
    if (formData.value.bizType === BarcodeBizTypeEnum.LOCATION) {
      const location = await WmWarehouseLocationApi.getWarehouseLocation(formData.value.bizId)
      if (version !== openVersion) return
      if (location?.warehouseId) {
        locationWarehouseId.value = location.warehouseId
      }
    }
    // 库位类型：需要查询库位信息，获取所属仓库ID和库区ID
    else if (formData.value.bizType === BarcodeBizTypeEnum.AREA) {
      const area = await WmWarehouseAreaApi.getWarehouseArea(formData.value.bizId)
      if (version !== openVersion) return
      if (area?.warehouseId) {
        areaWarehouseId.value = area.warehouseId
      }
      if (area?.locationId) {
        areaLocationId.value = area.locationId
      }
    }
  } catch (error) {
    console.error('加载级联数据失败:', error)
  }
}

/** bizType 切换时，清空业务字段 */
watch(
  () => formData.value.bizType,
  () => {
    // 加载数据时不清空字段
    if (isLoadingData.value) {
      return
    }
    selectionVersion++
    contentLoading.value = false

    formData.value.bizId = undefined
    formData.value.bizCode = undefined
    formData.value.bizName = undefined
    formData.value.content = undefined
    // 清空仓库层级的临时数据
    locationWarehouseId.value = undefined
    areaWarehouseId.value = undefined
    areaLocationId.value = undefined
  }
)

/** 打开弹窗 */
const open = async (type: string, id?: number) => {
  const version = ++openVersion
  selectionVersion++
  contentLoading.value = false
  isLoadingData.value = Boolean(id)
  formLoading.value = Boolean(id)
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增条码' : '修改条码'
  formType.value = type
  resetForm()
  if (id) {
    formLoading.value = true
    isLoadingData.value = true
    try {
      const saved = await WmBarcodeApi.getBarcode(id)
      if (version !== openVersion) return
      formData.value = { ...saved }
      // 编辑时，需要回填级联选择器的中间层级数据
      await loadCascadeData(version)
      await nextTick()
    } finally {
      if (version === openVersion) {
        formLoading.value = false
        isLoadingData.value = false
      }
    }
  }
}
defineExpose({ open })

/** 提交表单 */
const emit = defineEmits(['success'])
const submitForm = async () => {
  if (
    formLoading.value ||
    contentLoading.value ||
    !barcodeBusinessType(formData.value.bizType ?? -1)
  )
    return
  await formRef.value.validate()
  const version = openVersion
  formLoading.value = true
  try {
    const data = { ...formData.value } as unknown as WmBarcodeVO
    if (formType.value === 'create') {
      await WmBarcodeApi.createBarcode(data)
      if (version !== openVersion) return
      message.success(t('common.createSuccess'))
    } else {
      await WmBarcodeApi.updateBarcode(data)
      if (version !== openVersion) return
      message.success(t('common.updateSuccess'))
    }
    dialogVisible.value = false
    emit('success')
  } finally {
    if (version === openVersion) formLoading.value = false
  }
}
watch(dialogVisible, (value) => {
  if (!value) {
    openVersion++
    selectionVersion++
    contentLoading.value = false
  }
})
watch(
  () => formData.value.bizId,
  () => {
    if (formData.value.bizType === BarcodeBizTypeEnum.SN && !isLoadingData.value) {
      selectionVersion++
      contentLoading.value = false
      formData.value.bizCode = undefined
      formData.value.bizName = undefined
      formData.value.content = undefined
    }
  },
  { flush: 'sync' }
)

/** 重置表单 */
const resetForm = () => {
  formData.value = {
    id: undefined,
    bizType: undefined,
    bizId: undefined,
    bizCode: undefined,
    bizName: undefined,
    content: undefined,
    status: CommonStatusEnum.ENABLE,
    remark: ''
  }
  formRef.value?.resetFields()
}
</script>
