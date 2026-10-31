<template>
  <ContentWrap v-if="route.query.noticeId !== undefined">
    <el-alert
      :title="
        noticeContextError ||
        (noticeContext ? `发货通知：${noticeContext.code}` : '正在加载发货通知…')
      "
      :description="
        noticeContextError
          ? '请返回发货通知重新选择，或查看全部销售出库单。'
          : '先继续办理已有出库单；需要分批发货时再新增。新建后保存单头、选择通知物料并填写数量。提交后如需出货检验，先完成检验，再拣货、填写运单、执行出库。'
      "
      :type="noticeContextError ? 'error' : 'info'"
      :closable="false"
      show-icon
    />
    <el-button class="mt-10px" @click="clearNoticeContext">查看全部销售出库单</el-button>
  </ContentWrap>
  <ContentWrap>
    <el-form
      class="-mb-15px"
      :model="queryParams"
      ref="queryFormRef"
      :inline="true"
      label-width="100px"
    >
      <el-form-item label="出库单编号" prop="code">
        <el-input
          v-model="queryParams.code"
          placeholder="请输入出库单编号"
          clearable
          @keyup.enter="handleQuery"
          class="!w-240px"
        />
      </el-form-item>
      <el-form-item label="出库单名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入出库单名称"
          clearable
          @keyup.enter="handleQuery"
          class="!w-240px"
        />
      </el-form-item>
      <el-form-item label="销售订单编号" prop="salesOrderCode">
        <el-input
          v-model="queryParams.salesOrderCode"
          placeholder="请输入销售订单编号"
          clearable
          @keyup.enter="handleQuery"
          class="!w-240px"
        />
      </el-form-item>
      <el-form-item label="客户" prop="clientId">
        <MdClientSelect v-model="queryParams.clientId" class="!w-240px" />
      </el-form-item>
      <el-form-item label="出库日期" prop="shipmentDate">
        <el-date-picker
          v-model="queryParams.shipmentDate"
          value-format="YYYY-MM-DD HH:mm:ss"
          type="daterange"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :default-time="[new Date('1 00:00:00'), new Date('1 23:59:59')]"
          class="!w-240px"
        />
      </el-form-item>
      <el-form-item label="单据状态" prop="status">
        <el-select
          v-model="queryParams.status"
          placeholder="请选择单据状态"
          clearable
          class="!w-240px"
        >
          <el-option
            v-for="dict in getIntDictOptions(DICT_TYPE.MES_WM_PRODUCT_SALES_STATUS)"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
        <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
        <el-button
          type="primary"
          plain
          @click="openForm('create')"
          :disabled="
            noticeContextLoading ||
            !!noticeContextError ||
            (!!noticeContext && noticeContext.status !== MesWmSalesNoticeStatusEnum.APPROVED)
          "
          v-hasPermi="['mes:wm-product-sales:create']"
        >
          <Icon icon="ep:plus" class="mr-5px" /> 新增
        </el-button>
        <el-button
          type="success"
          plain
          @click="handleExport"
          :loading="exportLoading"
          v-hasPermi="['mes:wm-product-sales:export']"
        >
          <Icon icon="ep:download" class="mr-5px" /> 导出
        </el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap>
    <el-table v-loading="loading" :data="list" :stripe="true" :show-overflow-tooltip="true">
      <el-table-column label="出库单编号" align="center" prop="code" min-width="160">
        <template #default="scope">
          <el-button link type="primary" @click="openForm('detail', scope.row.id)">
            {{ scope.row.code }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="出库单名称" align="center" prop="name" min-width="150" />
      <el-table-column label="发货通知单号" align="center" prop="noticeCode" min-width="160" />
      <el-table-column label="销售订单编号" align="center" prop="salesOrderCode" min-width="160" />
      <el-table-column label="客户编码" align="center" prop="clientCode" min-width="120" />
      <el-table-column label="客户名称" align="center" prop="clientName" min-width="120" />
      <el-table-column label="收货人" align="center" prop="contactName" min-width="100" />
      <el-table-column label="联系方式" align="center" prop="contactTelephone" min-width="120" />
      <el-table-column label="收货地址" align="center" prop="contactAddress" min-width="180" />
      <el-table-column label="承运商" align="center" prop="carrier" min-width="120" />
      <el-table-column label="运输单号" align="center" prop="shippingNumber" min-width="160" />
      <el-table-column
        label="出库日期"
        align="center"
        prop="salesDate"
        :formatter="dateFormatter2"
        width="180px"
      />
      <el-table-column label="单据状态" align="center" prop="status" min-width="100">
        <template #default="scope">
          <dict-tag :type="DICT_TYPE.MES_WM_PRODUCT_SALES_STATUS" :value="scope.row.status" />
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="340" fixed="right">
        <template #default="scope">
          <el-button
            link
            type="primary"
            v-hasPermi="['mes:cost:query']"
            @click="openCostCenter(router, { salesId: scope.row.id })"
            >价格 / 毛利</el-button
          >
          <!-- 草稿：编辑、提交、删除 -->
          <el-button
            link
            type="primary"
            @click="openForm('update', scope.row.id)"
            v-hasPermi="['mes:wm-product-sales:update']"
            v-if="scope.row.status === MesWmProductSalesStatusEnum.PREPARE"
          >
            编辑
          </el-button>
          <el-button
            link
            type="danger"
            @click="handleDelete(scope.row.id)"
            v-hasPermi="['mes:wm-product-sales:delete']"
            v-if="scope.row.status === MesWmProductSalesStatusEnum.PREPARE"
          >
            删除
          </el-button>
          <!-- 待拣货：拣货、取消 -->
          <el-button
            link
            type="success"
            @click="openForm('stock', scope.row.id)"
            v-hasPermi="['mes:wm-product-sales:stock']"
            v-if="scope.row.status === MesWmProductSalesStatusEnum.APPROVING"
          >
            拣货
          </el-button>
          <!-- 待填写运单：填写运单、取消 -->
          <el-button
            link
            type="warning"
            @click="openForm('shipping', scope.row.id)"
            v-hasPermi="['mes:wm-product-sales:shipping']"
            v-if="scope.row.status === MesWmProductSalesStatusEnum.SHIPPING"
          >
            填写运单
          </el-button>
          <!-- 待出库：执行出库、取消 -->
          <el-button
            link
            type="success"
            @click="openForm('finish', scope.row.id)"
            v-hasPermi="['mes:wm-product-sales:finish']"
            v-if="scope.row.status === MesWmProductSalesStatusEnum.APPROVED"
          >
            执行出库
          </el-button>
          <el-button
            link
            type="danger"
            @click="handleCancel(scope.row.id)"
            v-hasPermi="['mes:wm-product-sales:cancel']"
            v-if="
              [
                MesWmProductSalesStatusEnum.CONFIRMED,
                MesWmProductSalesStatusEnum.APPROVING,
                MesWmProductSalesStatusEnum.SHIPPING,
                MesWmProductSalesStatusEnum.APPROVED
              ].includes(scope.row.status)
            "
          >
            取消
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </ContentWrap>

  <ProductSalesForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import { dateFormatter2 } from '@/utils/formatTime'
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import download from '@/utils/download'
import { WmProductSalesApi, WmProductSalesVO } from '@/api/mes/wm/productsales'
import { WmSalesNoticeApi, WmSalesNoticeVO } from '@/api/mes/wm/salesnotice'
import { openCostCenter } from '@/views/mes/cost/navigation'
import MdClientSelect from '@/views/mes/md/client/components/MdClientSelect.vue'
import ProductSalesForm from './ProductSalesForm.vue'
import {
  MesWmProductSalesStatusEnum,
  MesWmSalesNoticeStatusEnum
} from '@/views/mes/utils/constants'

defineOptions({ name: 'MesWmProductSales' })

const message = useMessage() // 消息弹窗
const router = useRouter()
const route = useRoute()
const { t } = useI18n() // 国际化

const loading = ref(true) // 列表的加载中
const list = ref<WmProductSalesVO[]>([]) // 列表的数据
const total = ref(0) // 列表的总页数
const exportLoading = ref(false) // 导出的加载中
const noticeContext = ref<WmSalesNoticeVO>()
const noticeContextLoading = ref(false)
const noticeContextError = ref('')
let contextVersion = 0
let listVersion = 0
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  code: undefined,
  name: undefined,
  salesOrderCode: undefined,
  clientId: undefined,
  noticeId: undefined as number | undefined,
  shipmentDate: undefined,
  status: undefined
})
const queryFormRef = ref() // 搜索的表单
const formRef = ref() // 表单弹窗

/** 查询列表 */
const getList = async () => {
  if (noticeContextLoading.value || noticeContextError.value) return
  const version = ++listVersion
  loading.value = true
  try {
    const data = await WmProductSalesApi.getProductSalesPage({ ...queryParams })
    if (version !== listVersion) return
    list.value = data.list
    total.value = data.total
  } finally {
    if (version === listVersion) loading.value = false
  }
}

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value.resetFields()
  handleQuery()
}

/** 添加/修改操作 */
const openForm = (type: string, id?: number) => {
  if (noticeContextLoading.value || noticeContextError.value) return
  if (
    type === 'create' &&
    noticeContext.value?.status !== undefined &&
    noticeContext.value.status !== MesWmSalesNoticeStatusEnum.APPROVED
  ) {
    message.warning('仅待出库的发货通知可以新建销售出库单')
    return
  }
  formRef.value.open(
    type,
    id,
    queryParams.noticeId ? { noticeId: queryParams.noticeId } : undefined
  )
}

const clearNoticeContext = () => {
  const query = { ...route.query }
  delete query.noticeId
  router.push({ path: route.path, query })
}

/** 通知入口只显示该通知的单据，异常参数或无权读取时不退回全部数据。 */
const loadNoticeContext = async (value: unknown) => {
  const version = ++contextVersion
  ++listVersion
  list.value = []
  total.value = 0
  noticeContext.value = undefined
  noticeContextError.value = ''
  noticeContextLoading.value = value !== undefined
  loading.value = true
  queryParams.noticeId = undefined
  queryParams.pageNo = 1
  // 切换通知时清除上一页面的搜索条件，避免已有单据被旧条件隐藏。
  Object.assign(queryParams, {
    code: undefined,
    name: undefined,
    salesOrderCode: undefined,
    clientId: undefined,
    shipmentDate: undefined,
    status: undefined
  })
  try {
    if (value !== undefined) {
      if (
        typeof value !== 'string' ||
        !/^[1-9]\d*$/.test(value) ||
        !Number.isSafeInteger(Number(value))
      ) {
        throw new Error('invalid notice id')
      }
      const noticeId = Number(value)
      const notice = await WmSalesNoticeApi.getSalesNotice(noticeId)
      if (version !== contextVersion) return
      if (!notice || notice.id !== noticeId) throw new Error('notice unavailable')
      noticeContext.value = notice
      queryParams.noticeId = noticeId
    }
  } catch {
    if (version === contextVersion) noticeContextError.value = '发货通知无效或无法读取'
  } finally {
    if (version === contextVersion) {
      noticeContextLoading.value = false
      loading.value = false
    }
  }
  if (version === contextVersion) await getList()
}

/** 取消按钮操作 */
const handleCancel = async (id: number) => {
  try {
    await message.confirm('确认取消该销售出库单？取消后不可恢复。')
    await WmProductSalesApi.cancelProductSales(id)
    message.success('取消成功')
    await getList()
  } catch {}
}

/** 删除按钮操作 */
const handleDelete = async (id: number) => {
  try {
    await message.delConfirm()
    await WmProductSalesApi.deleteProductSales(id)
    message.success(t('common.delSuccess'))
    await getList()
  } catch {}
}

/** 导出按钮操作 */
const handleExport = async () => {
  if (noticeContextLoading.value || noticeContextError.value) return
  try {
    await message.exportConfirm()
    exportLoading.value = true
    const data = await WmProductSalesApi.exportProductSales(queryParams)
    download.excel(data, '销售出库单.xls')
  } catch {
  } finally {
    exportLoading.value = false
  }
}

watch(() => route.query.noticeId, loadNoticeContext, { immediate: true })
</script>
