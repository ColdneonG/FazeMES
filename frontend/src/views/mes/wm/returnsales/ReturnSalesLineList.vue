<!-- MES 销售退货单行列表子组件 -->
<template>
  <div class="overflow-hidden">
    <el-button v-if="isUpdate" type="primary" plain @click="openForm('create')" class="mb-10px">
      <Icon icon="ep:plus" class="mr-5px" /> 添加物料
    </el-button>
    <el-table
      v-loading="loading"
      :data="list"
      :stripe="true"
      :show-overflow-tooltip="true"
      border
      :row-key="(row: any) => row.id"
    >
      <el-table-column type="expand">
        <template #default="scope">
          <ReturnSalesDetailList
            :ref="(el: any) => setDetailListRef(scope.row.id, el)"
            :return-id="props.returnId"
            :line-id="scope.row.id"
            :item-id="scope.row.itemId"
            :form-type="props.formType"
            @edit-detail="
              (detailId: number) =>
                openDetailForm('update', scope.row.id, scope.row.itemId, detailId)
            "
          />
        </template>
      </el-table-column>
      <el-table-column label="物料编码" align="center" prop="itemCode" min-width="120" />
      <el-table-column label="物料名称" align="center" prop="itemName" min-width="140" />
      <el-table-column label="规格型号" align="center" prop="specification" min-width="120" />
      <el-table-column label="单位" align="center" prop="unitMeasureName" width="80" />
      <el-table-column label="退货数量" align="center" prop="quantity" width="100" />
      <el-table-column label="原销售出库" prop="originSalesCode" min-width="150" />
      <el-table-column label="原流水ID" prop="originTransactionId" width="110" />
      <el-table-column label="本行可退额度" min-width="130"
        ><template #default="{ row }">{{
          returnBalanceText(row.editableAvailableQuantity)
        }}</template></el-table-column
      >
      <el-table-column label="来源核验" min-width="200"
        ><template #default="{ row }">{{
          row.sourceWarning || (row.sourceReliable ? '凭据已核对' : '历史来源待核对')
        }}</template></el-table-column
      >
      <el-table-column label="批次号" align="center" prop="batchCode" min-width="120" />
      <el-table-column label="是否需要质检" align="center" prop="rqcCheckFlag">
        <template #default="scope">
          <dict-tag :type="DICT_TYPE.INFRA_BOOLEAN_STRING" :value="scope.row.rqcCheckFlag" />
        </template>
      </el-table-column>
      <el-table-column label="质量状态" align="center" prop="qualityStatus" width="100">
        <template #default="scope">
          <dict-tag :type="DICT_TYPE.MES_WM_QUALITY_STATUS" :value="scope.row.qualityStatus" />
        </template>
      </el-table-column>
      <el-table-column
        v-if="isUpdate || isStock"
        label="操作"
        align="center"
        width="160"
        fixed="right"
      >
        <template #default="scope">
          <el-button v-if="isUpdate" link type="primary" @click="openForm('update', scope.row.id)">
            编辑
          </el-button>
          <el-button v-if="isUpdate" link type="danger" @click="handleDelete(scope.row.id)">
            删除
          </el-button>
          <el-button v-if="isStock" link type="success" @click="handleStock(scope.row.id)">
            上架
          </el-button>
          <PrinterLabel
            v-if="isStock"
            :bizId="scope.row.batchId || scope.row.itemId"
            :bizCode="scope.row.batchCode"
            :bizType="scope.row.batchId ? 'BATCH' : 'ITEM'"
          />
        </template>
      </el-table-column>
    </el-table>
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </div>

  <!-- 添加/编辑行弹窗 -->
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="960px">
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="110px"
      v-loading="formLoading"
    >
      <el-row>
        <el-col :span="24">
          <el-form-item label="原出库流水" prop="originTransactionId">
            <ReturnSalesSourceSelect
              v-model="formData.originTransactionId"
              :return-id="props.returnId"
              :line-id="formData.id"
              :disabled="formLoading"
              @change="handleSourceChange"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="物料">
            <el-input
              :model-value="source ? `${source.itemCode} · ${source.itemName}` : ''"
              readonly
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="退货数量" prop="quantity">
            <el-input
              v-model="formData.quantity"
              inputmode="decimal"
              maxlength="21"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="批次">
            <el-input :model-value="source?.batchCode || '无批次'" readonly />
          </el-form-item>
        </el-col>
      </el-row>
      <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" />
      <p v-if="source">
        原出库 {{ returnBalanceText(source.outboundQuantity) }}；累计已退
        {{ returnBalanceText(source.returnedQuantity) }}； 未入库预留
        {{ returnBalanceText(source.reservedQuantity) }}；本行可用
        {{ returnBalanceText(source.editableAvailableQuantity) }} {{ source.unitMeasureName }}
      </p>
      <el-alert
        v-if="source?.sourceWarning"
        :title="source.sourceWarning"
        type="warning"
        :closable="false"
      />
      <el-row>
        <el-col :span="8">
          <el-form-item label="需要质检" prop="rqcCheckFlag">
            <el-switch v-model="formData.rqcCheckFlag" />
          </el-form-item>
        </el-col>
        <el-col :span="16">
          <el-form-item label="备注" prop="remark">
            <el-input v-model="formData.remark" type="textarea" placeholder="请输入备注" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button @click="submitForm" type="primary" :disabled="formLoading">确 定</el-button>
      <el-button @click="dialogVisible = false">取 消</el-button>
    </template>
  </Dialog>

  <!-- 上架明细添加/编辑弹窗 -->
  <ReturnSalesDetailForm
    ref="detailFormRef"
    :return-id="props.returnId"
    @success="onDetailFormSuccess"
  />
</template>

<script setup lang="ts">
import { DICT_TYPE } from '@/utils/dict'
import {
  WmReturnSalesLineApi,
  type WmReturnSalesLineVO,
  type ReturnSalesSource
} from '@/api/mes/wm/returnsales/line'
import { quantityRule } from '@/utils/mesQuantity'
import ReturnSalesSourceSelect from './ReturnSalesSourceSelect.vue'
import { returnQuantityError, returnBalanceText } from './sourceQuantity'
import ReturnSalesDetailList from './ReturnSalesDetailList.vue'
import ReturnSalesDetailForm from './ReturnSalesDetailForm.vue'
import { PrinterLabel } from '@/views/mes/wm/barcode/components'

defineOptions({ name: 'ReturnSalesLineList' })

const props = defineProps<{
  returnId: number
  formType: string
  clientId?: number // 客户 ID，用于透传给批次选择器过滤
  salesOrderCode?: string // 销售订单编号，用于透传给批次选择器过滤
}>()

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗

const isUpdate = computed(() => ['create', 'update'].includes(props.formType)) // 是否为编辑模式
const isStock = computed(() => props.formType === 'stock') // 是否为上架模式

// ==================== 列表 ====================
const loading = ref(false) // 列表的加载中
const list = ref<WmReturnSalesLineVO[]>([]) // 行列表
const total = ref(0) // 列表的总页数
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  returnId: undefined as number | undefined
})
let listVersion = 0

/** 查询行列表 */
const getList = async () => {
  const version = ++listVersion,
    returnId = props.returnId
  if (!returnId) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    queryParams.returnId = returnId
    const data = await WmReturnSalesLineApi.getReturnSalesLinePage({ ...queryParams })
    if (version !== listVersion || returnId !== props.returnId) return
    list.value = data.list
    total.value = data.total
  } finally {
    if (version === listVersion) loading.value = false
  }
}

/** 删除按钮操作 */
const handleDelete = async (id: number) => {
  try {
    await message.delConfirm()
    await WmReturnSalesLineApi.deleteReturnSalesLine(id)
    message.success(t('common.delSuccess'))
    await getList()
  } catch {}
}

// ==================== 添加/编辑表单 ====================
const dialogVisible = ref(false) // 弹窗的是否展示
const dialogTitle = ref('') // 弹窗的标题
const formLoading = ref(false) // 表单的加载中
const lineFormType = ref('') // 行表单的类型
const source = ref<ReturnSalesSource>()
const sourceError = ref('')
let formVersion = 0
const formData = ref({
  id: undefined as number | undefined,
  returnId: undefined as number | undefined,
  itemId: undefined as number | undefined,
  originTransactionId: undefined as number | undefined,
  quantity: '',
  batchId: undefined as number | null | undefined,
  rqcCheckFlag: true,
  remark: undefined as string | undefined
})
const formRules = reactive({
  originTransactionId: [{ required: true, message: '请选择原销售出库流水', trigger: 'change' }],
  quantity: [quantityRule(true)],
  rqcCheckFlag: [{ required: true, message: '需要质检不能为空', trigger: 'change' }]
})
const formRef = ref() // 表单 Ref

/** 打开表单弹窗 */
const openForm = async (type: string, id?: number) => {
  const version = ++formVersion
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '添加销售退货单行' : '修改销售退货单行'
  lineFormType.value = type
  resetForm()
  formLoading.value = true
  try {
    if (id) {
      const saved = await WmReturnSalesLineApi.getReturnSalesLine(id)
      if (version !== formVersion) return
      formData.value = {
        id: saved.id,
        returnId: props.returnId,
        originTransactionId: saved.originTransactionId ?? undefined,
        itemId: saved.itemId,
        batchId: saved.batchId,
        quantity: String(saved.quantity),
        rqcCheckFlag: saved.rqcCheckFlag,
        remark: saved.remark
      }
      if (saved.originTransactionId) {
        const data = await WmReturnSalesLineApi.getSourceOptions({
          returnId: props.returnId,
          lineId: id,
          originTransactionId: saved.originTransactionId,
          pageNo: 1,
          pageSize: 1
        })
        if (version !== formVersion) return
        source.value = data.list.find(
          (row) => row.originTransactionId === saved.originTransactionId
        )
      }
      if (!source.value)
        sourceError.value = saved.sourceWarning || '此行缺少可核对的原出库凭据，请重新选择来源'
    }
  } catch {
    if (version === formVersion) sourceError.value = '原出库来源无法核对，请重新打开后选择'
  } finally {
    if (version === formVersion) formLoading.value = false
  }
}

const handleSourceChange = (fresh: ReturnSalesSource | undefined) => {
  sourceError.value = ''
  if (!fresh) {
    source.value = undefined
    formData.value.originTransactionId = undefined
    formData.value.itemId = undefined
    formData.value.batchId = undefined
    formData.value.quantity = ''
    return
  }
  const previous = source.value?.originTransactionId ?? formData.value.originTransactionId
  if (previous !== fresh.originTransactionId || !formData.value.quantity)
    formData.value.quantity = fresh.editableAvailableQuantity ?? ''
  source.value = fresh
  formData.value.originTransactionId = fresh.originTransactionId
  formData.value.itemId = fresh.itemId
  formData.value.batchId = fresh.batchId
}

/** 提交表单 */
const submitForm = async () => {
  if (formLoading.value || !isUpdate.value) return
  await formRef.value.validate()
  const validationError = returnQuantityError(formData.value.quantity, source.value)
  if (!formData.value.originTransactionId || validationError) {
    message.warning(validationError || '请选择原出库流水')
    return
  }
  const version = formVersion,
    returnId = props.returnId
  const { id, originTransactionId, quantity, rqcCheckFlag, remark } = formData.value
  formLoading.value = true
  try {
    const options = await WmReturnSalesLineApi.getSourceOptions({
      returnId,
      lineId: id,
      originTransactionId,
      pageNo: 1,
      pageSize: 1
    })
    if (version !== formVersion || returnId !== props.returnId) return
    const fresh = options.list.find((row) => row.originTransactionId === originTransactionId)
    source.value = fresh
    const error = returnQuantityError(quantity, fresh)
    if (error) {
      message.warning(error)
      return
    }
    const data = { id, originTransactionId, quantity, rqcCheckFlag, remark, returnId }
    if (lineFormType.value === 'create') {
      await WmReturnSalesLineApi.createReturnSalesLine(data)
      message.success(t('common.createSuccess'))
    } else {
      await WmReturnSalesLineApi.updateReturnSalesLine(data)
      message.success(t('common.updateSuccess'))
    }
    if (version !== formVersion) return
    dialogVisible.value = false
    await getList()
  } finally {
    if (version === formVersion) formLoading.value = false
  }
}

/** 重置表单 */
const resetForm = () => {
  source.value = undefined
  sourceError.value = ''
  formData.value = {
    id: undefined,
    returnId: undefined,
    itemId: undefined,
    originTransactionId: undefined,
    quantity: '',
    batchId: undefined,
    rqcCheckFlag: true,
    remark: undefined
  }
  formRef.value?.resetFields()
}

// ==================== 展开行：上架明细 ====================
const detailListRefs = ref<Record<number, InstanceType<typeof ReturnSalesDetailList>>>({})

/** 缓存子组件 ref */
const setDetailListRef = (lineId: number, el: any) => {
  if (el) {
    detailListRefs.value[lineId] = el
  }
}

// ==================== 上架明细表单（LineList 层级持有） ====================
const detailFormRef = ref()

/** 上架：直接打开明细创建表单 */
const handleStock = (lineId: number) => {
  const row = list.value.find((r) => r.id === lineId)
  openDetailForm('create', lineId, row?.itemId)
}

/** 打开上架明细表单 */
const openDetailForm = (type: string, lineId: number, itemId?: number, detailId?: number) => {
  detailFormRef.value.open(type, lineId, itemId, detailId)
}

/** 明细表单提交成功后，刷新已展开行的 DetailList */
const onDetailFormSuccess = (lineId: number) => {
  detailListRefs.value[lineId]?.getList()
}

/** 初始化 */
watch(
  () => [props.returnId, props.formType],
  () => {
    formVersion++
    dialogVisible.value = false
    formLoading.value = false
    resetForm()
    void getList()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  formVersion++
  listVersion++
})
</script>
