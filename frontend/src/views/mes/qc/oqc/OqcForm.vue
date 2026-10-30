<!-- MES 出货检验单表单 -->
<template>
  <Dialog
    :title="dialogTitle"
    v-model="dialogVisible"
    :default-fullscreen="props.terminal"
    class="mes-qc-responsive"
    width="1080px"
  >
    <!-- 基本信息表单 -->
    <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" class="mb-4" />
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="120px"
      v-loading="formLoading"
      :disabled="isDetail"
    >
      <el-row :gutter="16">
        <el-col :span="24">
          <el-form-item label="检验单编号" prop="code">
            <el-input v-model="formData.code" placeholder="请输入检验单编号">
              <template #append>
                <el-button @click="generateCode"> 生成 </el-button>
              </template>
            </el-input>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="检验单名称" prop="name">
            <el-input v-model="formData.name" placeholder="请输入检验单名称" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16" v-if="formData.sourceDocType">
        <el-col :span="8">
          <el-form-item label="来源单据类型">
            <el-select v-model="formData.sourceDocType" class="!w-1/1" disabled>
              <el-option
                v-for="dict in getIntDictOptions(DICT_TYPE.MES_QC_SOURCE_DOC_TYPE)"
                :key="dict.value"
                :label="dict.label"
                :value="dict.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="来源单据编号">
            <el-input v-model="formData.sourceDocCode" disabled />
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider>物料与客户</el-divider>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="产品物料" prop="itemId">
            <MdItemSelect
              v-model="formData.itemId"
              placeholder="请选择产品物料"
              class="!w-1/1"
              :disabled="isFromPendingTask"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="客户" prop="clientId">
            <MdClientSelect
              v-model="formData.clientId"
              placeholder="请选择客户"
              class="!w-1/1"
              :disabled="isFromPendingTask"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="批次号" prop="batchCode">
            <el-input
              v-model="formData.batchCode"
              placeholder="请输入批次号"
              :disabled="isFromPendingTask"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider>检测情况</el-divider>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="发货数量" prop="outQuantity">
            <el-input
              v-model="formData.outQuantity"
              inputmode="decimal"
              placeholder="请输入发货数量"
              class="!w-1/1"
              :disabled="isFromPendingTask"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="检测数量" prop="checkQuantity">
            <el-input
              v-model="formData.checkQuantity"
              inputmode="decimal"
              placeholder="请输入检测数量"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="合格品数量" prop="qualifiedQuantity">
            <el-input
              v-model="formData.qualifiedQuantity"
              inputmode="decimal"
              placeholder="请输入合格品数量"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="不合格品数量" prop="unqualifiedQuantity">
            <el-input
              v-model="formData.unqualifiedQuantity"
              inputmode="decimal"
              placeholder="请输入不合格品数量"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="检测人员" prop="inspectorUserId">
            <UserSelectV2
              v-model="formData.inspectorUserId"
              placeholder="请选择检测人员"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="出货日期" prop="outDate">
            <el-date-picker
              v-model="formData.outDate"
              type="date"
              value-format="x"
              placeholder="请选择出货日期"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="检测日期" prop="inspectDate">
            <el-date-picker
              v-model="formData.inspectDate"
              type="date"
              value-format="x"
              placeholder="请选择检测日期"
              class="!w-1/1"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="检测结果" prop="checkResult">
            <el-select
              v-model="formData.checkResult"
              placeholder="请选择检测结果"
              clearable
              class="!w-1/1"
            >
              <el-option
                v-for="dict in getIntDictOptions(DICT_TYPE.MES_QC_CHECK_RESULT)"
                :key="dict.value"
                :label="dict.label"
                :value="dict.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="备注" prop="remark">
            <el-input type="textarea" v-model="formData.remark" placeholder="请输入备注" />
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 缺陷统计（只读） -->
      <el-divider>缺陷情况</el-divider>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="致命缺陷数">
            <el-input :model-value="formData.criticalQuantity" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="严重缺陷数">
            <el-input :model-value="formData.majorQuantity" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="轻微缺陷数">
            <el-input :model-value="formData.minorQuantity" disabled />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="致命缺陷率">
            <el-input :model-value="formData.criticalRate + '%'" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="严重缺陷率">
            <el-input :model-value="formData.majorRate + '%'" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="轻微缺陷率">
            <el-input :model-value="formData.minorRate + '%'" disabled />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <!-- 子表标签页（编辑模式下显示） -->
    <template v-if="formData.id">
      <el-divider />
      <el-tabs v-model="activeTab">
        <el-tab-pane label="检验项" name="line">
          <OqcLineList :oqc-id="formData.id" :form-type="formType" />
        </el-tab-pane>
        <el-tab-pane label="检测结果" name="result">
          <QcIndicatorResultList
            :qc-id="formData.id!"
            :qc-type="MesQcTypeEnum.OQC"
            :readonly="isDetail || formData.status !== MesQcStatusEnum.DRAFT"
          />
        </el-tab-pane>
      </el-tabs>
    </template>

    <template #footer>
      <el-button @click="submitForm" type="primary" :disabled="formLoading" v-if="!isDetail">
        保 存
      </el-button>
      <el-button
        @click="handleFinish"
        v-hasPermi="['mes:qc-oqc:finish']"
        type="success"
        :disabled="formLoading"
        v-if="formType === 'update' && formData.status === MesQcStatusEnum.DRAFT"
      >
        完 成
      </el-button>
      <el-button @click="dialogVisible = false">关 闭</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import { QcOqcApi, QcOqcVO } from '@/api/mes/qc/oqc'
import { AutoCodeRecordApi } from '@/api/mes/md/autocode/record'
import MdClientSelect from '@/views/mes/md/client/components/MdClientSelect.vue'
import MdItemSelect from '@/views/mes/md/item/components/MdItemSelect.vue'
import UserSelectV2 from '@/views/system/user/components/UserSelectV2.vue'
import OqcLineList from './OqcLineList.vue'
import QcIndicatorResultList from '@/views/mes/qc/indicatorresult/components/QcIndicatorResultList.vue'
import { MesQcTypeEnum, MesQcStatusEnum, MesAutoCodeRuleCode } from '@/views/mes/utils/constants'
import { quantityRule, quantityUnits } from '@/utils/mesQuantity'

const props = withDefaults(defineProps<{ terminal?: boolean }>(), { terminal: false })

defineOptions({ name: 'OqcForm' })

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗

const dialogVisible = ref(false) // 弹窗的是否展示
const dialogTitle = computed(() => {
  const titles = {
    create: '添加出货检验单',
    update: '修改出货检验单',
    detail: '查看出货检验单信息'
  }
  return titles[formType.value] || formType.value
}) // 弹窗标题，根据 formType 自动显示
const formLoading = ref(false) // 表单的加载中：1）修改时的数据加载；2）提交的按钮禁用
const sourceError = ref('')
let openVersion = 0
const formType = ref('') // 表单的类型：create - 新增；update - 修改
const activeTab = ref('line') // 当前激活的标签页
const isFromPendingTask = computed(() => formData.value.sourceDocId != null) // 是否来自待检任务（有预填的来源单据信息）

const formData = ref({
  id: undefined as number | undefined,
  code: undefined,
  name: undefined,
  status: undefined as number | undefined,
  sourceDocType: undefined,
  sourceDocId: undefined,
  sourceLineId: undefined,
  sourceDocCode: undefined as string | undefined,
  clientId: undefined as number | undefined,
  batchCode: undefined as string | undefined,
  itemId: undefined as number | undefined,
  outQuantity: '',
  checkQuantity: '',
  qualifiedQuantity: '',
  unqualifiedQuantity: '',
  checkResult: undefined,
  outDate: undefined,
  inspectDate: undefined,
  inspectorUserId: undefined,
  remark: undefined,
  // 缺陷统计（只读）
  criticalRate: 0,
  majorRate: 0,
  minorRate: 0,
  criticalQuantity: 0,
  majorQuantity: 0,
  minorQuantity: 0
})
const formRules = reactive({
  code: [{ required: true, message: '检验单编号不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '检验单名称不能为空', trigger: 'blur' }],
  clientId: [{ required: true, message: '客户不能为空', trigger: 'change' }],
  itemId: [{ required: true, message: '产品物料不能为空', trigger: 'change' }],
  outQuantity: [quantityRule(true)],
  checkQuantity: [quantityRule(true)],
  qualifiedQuantity: [quantityRule(false)],
  unqualifiedQuantity: [quantityRule(false)],
  inspectorUserId: [{ required: true, message: '检测人员不能为空', trigger: 'change' }],
  outDate: [{ required: true, message: '出货日期不能为空', trigger: 'change' }],
  inspectDate: [{ required: true, message: '检测日期不能为空', trigger: 'change' }]
})
const formRef = ref() // 表单 Ref
const originalFormData = ref<string>('') // 原始表单数据快照，用于脏检查
const isDetail = computed(() => formType.value === 'detail') // 表单是否为详情模式（只读）

/** 生成检验单编号 */
const generateCode = async () => {
  const version = openVersion
  const code = await AutoCodeRecordApi.generateAutoCode(MesAutoCodeRuleCode.QC_OQC_CODE)
  if (version === openVersion) formData.value.code = code
}

/** 打开弹窗 */
const open = async (type: string, id?: number, data?: QcOqcVO) => {
  const version = ++openVersion
  dialogVisible.value = true
  formType.value = type
  activeTab.value = 'line'
  resetForm()
  sourceError.value = ''
  formLoading.value = true
  try {
    if (id) {
      const saved = await QcOqcApi.getOqc(id)
      if (version !== openVersion) return
      formData.value = saved
    } else if (data) {
      Object.assign(formData.value, data)
      if (Number(data.sourceDocType) === 118 && data.sourceLineId != null) {
        // Pending-task rows retain their old numeric API. Fetch the exact current sales quantity
        // and required batch before creating its inspection on either desktop or mobile.
        formData.value.outQuantity = ''
        formData.value.checkQuantity = ''
        const source = await QcOqcApi.getSourceContext(data.sourceDocId, data.sourceLineId)
        if (version !== openVersion) return
        if (source.sourceDocId !== data.sourceDocId || source.sourceLineId !== data.sourceLineId)
          throw new Error('source changed')
        formData.value.sourceDocCode = source.sourceDocCode
        formData.value.clientId = source.clientId
        formData.value.itemId = source.itemId
        formData.value.batchCode = source.batchCode ?? undefined
        formData.value.outQuantity = source.outQuantity
      }
    }
    normalizeQuantities()
    if (props.terminal && type === 'create') await generateCode()
    if (version === openVersion) originalFormData.value = JSON.stringify(formData.value)
  } catch {
    if (version === openVersion)
      sourceError.value = '无法核对当前检验单或原销售行，请重新打开后重试'
  } finally {
    if (version === openVersion) formLoading.value = false
  }
}
defineExpose({ open }) // 提供 open 方法，用于打开弹窗

const normalizeQuantities = () => {
  for (const key of [
    'outQuantity',
    'checkQuantity',
    'qualifiedQuantity',
    'unqualifiedQuantity'
  ] as const) {
    formData.value[key] = formData.value[key] == null ? '' : String(formData.value[key])
  }
}
const validQuantities = () => {
  const out = quantityUnits(formData.value.outQuantity)
  const check = quantityUnits(formData.value.checkQuantity)
  const good = quantityUnits(formData.value.qualifiedQuantity)
  const bad = quantityUnits(formData.value.unqualifiedQuantity)
  if (
    out === undefined ||
    check === undefined ||
    good === undefined ||
    bad === undefined ||
    out <= 0n ||
    check <= 0n ||
    check > out ||
    good + bad !== check
  ) {
    message.warning(
      '数量最多支持 14 位整数和 6 位小数；检测数量须大于零且不超过发货量，合格与不合格合计须等于检测量'
    )
    return false
  }
  return true
}

/** 提交表单 */
const emit = defineEmits(['success']) // 定义 success 事件，用于操作成功后的回调
const submitForm = async () => {
  if (formLoading.value || sourceError.value) return
  // 校验表单
  if (!formRef) return
  const valid = await formRef.value.validate()
  if (!valid || !validQuantities()) return
  // 提交请求
  formLoading.value = true
  try {
    const data = { ...formData.value } as unknown as QcOqcVO
    if (formType.value === 'create') {
      const createdId = await QcOqcApi.createOqc(data)
      if (props.terminal) {
        formData.value = await QcOqcApi.getOqc(createdId)
        normalizeQuantities()
        formType.value = 'update'
        activeTab.value = 'line'
        originalFormData.value = JSON.stringify(formData.value)
        message.success('已建立检验单，请继续填写检测值和缺陷后完成')
        emit('success')
        return
      }
      message.success(t('common.createSuccess'))
    } else {
      await QcOqcApi.updateOqc(data)
      message.success(t('common.updateSuccess'))
    }
    dialogVisible.value = false
    // 发送操作成功的事件
    emit('success')
  } finally {
    formLoading.value = false
  }
}

/** 完成操作：表单修改过则先保存，再完成 */
const handleFinish = async () => {
  if (formLoading.value || sourceError.value) return
  if (!formRef) return
  const valid = await formRef.value.validate()
  if (!valid || !validQuantities()) return
  try {
    await message.confirm('是否完成出货检验单编制？【完成后将不能更改】')
    formLoading.value = true
    if (JSON.stringify(formData.value) !== originalFormData.value) {
      const data = { ...formData.value } as unknown as QcOqcVO
      await QcOqcApi.updateOqc(data)
    }
    await QcOqcApi.finishOqc(formData.value.id!)
    message.success('完成成功')
    dialogVisible.value = false
    emit('success')
  } catch {
  } finally {
    formLoading.value = false
  }
}

/** 重置表单 */
const resetForm = () => {
  formData.value = {
    id: undefined,
    code: undefined,
    name: undefined,
    status: undefined,
    sourceDocType: undefined,
    sourceDocId: undefined,
    sourceLineId: undefined,
    sourceDocCode: undefined,
    clientId: undefined,
    batchCode: undefined,
    itemId: undefined,
    outQuantity: '',
    checkQuantity: '',
    qualifiedQuantity: '',
    unqualifiedQuantity: '',
    checkResult: undefined,
    outDate: undefined,
    inspectDate: undefined,
    inspectorUserId: undefined,
    remark: undefined,
    criticalRate: 0,
    majorRate: 0,
    minorRate: 0,
    criticalQuantity: 0,
    majorQuantity: 0,
    minorQuantity: 0
  }
  formRef.value?.resetFields()
}

/** 数量互填：outQuantity 和 checkQuantity 互相填充 */
watch(
  () => formData.value.outQuantity,
  (val) => {
    if (formType.value === 'create' && val !== '' && formData.value.checkQuantity === '') {
      formData.value.checkQuantity = val
    }
  }
)
watch(
  () => formData.value.checkQuantity,
  (val) => {
    if (formType.value === 'create' && val !== '' && formData.value.outQuantity === '') {
      formData.value.outQuantity = val
    }
  }
)
</script>
