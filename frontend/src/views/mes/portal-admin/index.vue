<template>
  <div class="portal-admin">
    <ContentWrap>
      <div class="portal-toolbar"
        ><div><h2>客户自助门户</h2><p>绑定客户账号、发布产品目录，处理客户订购申请。</p></div
        ><div
          ><el-button @click="openPortal">打开客户门户</el-button
          ><el-button :loading="loading" @click="load">刷新</el-button></div
        ></div
      >
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
        class="mb-15px"
      />
      <el-tabs v-model="tab">
        <el-tab-pane label="客户账号" name="accounts">
          <div class="section-actions"
            ><span>显示最近500个账号；客户只能查看绑定客户的数据。</span
            ><el-button v-if="canManage" type="primary" @click="editAccount()"
              >开通客户账号</el-button
            ></div
          >
          <el-table :data="accounts" v-loading="loading" stripe
            ><el-table-column prop="username" label="客户账号" min-width="150" /><el-table-column
              prop="displayName"
              label="显示名称"
              min-width="130"
            /><el-table-column
              prop="clientName"
              label="绑定客户"
              min-width="200"
              show-overflow-tooltip
            /><el-table-column label="状态" width="90"
              ><template #default="{ row }"
                ><el-tag :type="row.enabled ? 'success' : 'info'">{{
                  row.enabled ? '启用' : '停用'
                }}</el-tag></template
              ></el-table-column
            ><el-table-column label="到期时间" min-width="170"
              ><template #default="{ row }">{{
                formatTime(row.expireTime)
              }}</template></el-table-column
            ><el-table-column label="登录保护" min-width="170"
              ><template #default="{ row }">{{
                row.lockedUntil && new Date(row.lockedUntil).getTime() > Date.now()
                  ? `锁定至 ${formatTime(row.lockedUntil)}`
                  : '正常'
              }}</template></el-table-column
            ><el-table-column v-if="canManage" label="操作" width="95" fixed="right"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="editAccount(row)">维护</el-button></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane label="可订购目录" name="catalog">
          <div class="section-actions"
            ><span>仅发布的产品对客户可见，产品停用后自动隐藏。</span
            ><el-button v-if="canManage" type="primary" @click="editCatalog()"
              >发布产品</el-button
            ></div
          >
          <el-table :data="catalog" v-loading="loading" stripe
            ><el-table-column prop="productCode" label="产品编码" min-width="150" /><el-table-column
              prop="productName"
              label="产品名称"
              min-width="180"
              show-overflow-tooltip
            /><el-table-column
              prop="specification"
              label="规格"
              min-width="180"
              show-overflow-tooltip
            /><el-table-column
              prop="description"
              label="客户可见说明"
              min-width="260"
              show-overflow-tooltip
            /><el-table-column label="发布状态" width="100"
              ><template #default="{ row }"
                ><el-tag :type="row.enabled ? 'success' : 'info'">{{
                  row.enabled ? '已发布' : '已下架'
                }}</el-tag></template
              ></el-table-column
            ><el-table-column v-if="canManage" label="操作" width="90" fixed="right"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="editCatalog(row)">维护</el-button></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane label="订购申请" name="requests">
          <div class="section-actions"
            ><span>显示最近300条申请。受理后请按业务流程确认生产工单。</span
            ><el-tag type="warning"
              >待受理 {{ requests.filter((r) => r.status === 'PENDING').length }}</el-tag
            ></div
          >
          <el-table :data="requests" v-loading="loading" stripe
            ><el-table-column type="expand"
              ><template #default="{ row }"
                ><div class="request-detail"
                  ><el-table :data="row.items" size="small"
                    ><el-table-column prop="productCode" label="产品编码" /><el-table-column
                      prop="productName"
                      label="产品名称" /><el-table-column
                      prop="specification"
                      label="规格" /><el-table-column
                      prop="quantity"
                      label="数量"
                      width="140" /><el-table-column
                      prop="unitName"
                      label="单位"
                      width="100" /></el-table
                  ><p>客户备注：{{ row.remark || '无' }}</p
                  ><p v-if="row.reviewNote">客户可见反馈：{{ row.reviewNote }}</p></div
                ></template
              ></el-table-column
            ><el-table-column prop="code" label="申请单号" min-width="220" /><el-table-column
              prop="clientName"
              label="客户"
              min-width="180"
              show-overflow-tooltip
            /><el-table-column
              prop="deliveryDate"
              label="期望交期"
              min-width="120"
            /><el-table-column label="状态" width="100"
              ><template #default="{ row }"
                ><el-tag
                  :type="
                    row.status === 'REJECTED'
                      ? 'danger'
                      : row.status === 'ACCEPTED'
                        ? 'success'
                        : 'warning'
                  "
                  >{{ requestStatuses[row.status] }}</el-tag
                ></template
              ></el-table-column
            ><el-table-column label="提交时间" min-width="170"
              ><template #default="{ row }">{{
                formatTime(row.createTime)
              }}</template></el-table-column
            ><el-table-column v-if="canManage" label="操作" width="100" fixed="right"
              ><template #default="{ row }"
                ><el-button
                  v-if="row.status === 'PENDING'"
                  link
                  type="primary"
                  @click="openReview(row)"
                  >处理申请</el-button
                ><span v-else>已处理</span></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
      </el-tabs>
    </ContentWrap>
    <el-dialog
      v-model="accountDialog"
      :title="accountForm.id ? '维护客户账号' : '开通客户账号'"
      width="min(560px, 94vw)"
      :close-on-click-modal="!saving"
    >
      <el-form label-width="100px" :disabled="saving"
        ><el-form-item label="绑定客户" required
          ><el-select
            v-model="accountForm.clientId"
            filterable
            placeholder="选择真实客户"
            class="w-full"
            ><el-option
              v-for="client in clients"
              :key="client.id"
              :value="client.id"
              :label="`${client.code} · ${client.name}`" /></el-select></el-form-item
        ><el-form-item label="客户账号" required
          ><el-input
            v-model="accountForm.username"
            maxlength="64"
            placeholder="3至64位字母、数字、点、下划线、@、横线" /></el-form-item
        ><el-form-item label="显示名称" required
          ><el-input v-model="accountForm.displayName" maxlength="100" /></el-form-item
        ><el-form-item :label="accountForm.id ? '重置密码' : '初始密码'" :required="!accountForm.id"
          ><el-input
            v-model="accountForm.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="
              accountForm.id ? '留空保留当前密码；至少10字符' : '至少10字符，不超过72个UTF-8字节'
            "
            maxlength="72" /></el-form-item
        ><el-form-item label="启用状态"><el-switch v-model="accountForm.enabled" /></el-form-item
        ><el-form-item label="到期时间"
          ><el-date-picker
            v-model="accountForm.expireTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空表示账号无单独到期限制"
            class="!w-full"
            clearable /></el-form-item></el-form
      ><el-alert
        v-if="accountForm.id"
        title="保存会撤销该账号所有客户会话并解除登录锁定，客户需要重新登录。"
        type="info"
        :closable="false"
      />
      <template #footer
        ><el-button @click="accountDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveAccount"
          >保存账号</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="catalogDialog"
      title="维护可订购产品"
      width="min(560px, 94vw)"
      :close-on-click-modal="!saving"
      ><el-form label-width="100px" :disabled="saving"
        ><el-form-item label="产品" required
          ><el-select
            v-model="catalogForm.productId"
            filterable
            class="w-full"
            placeholder="选择要公开的产品"
            ><el-option
              v-for="product in products"
              :key="product.id"
              :value="product.id"
              :label="`${product.code} · ${product.name}`" /></el-select></el-form-item
        ><el-form-item label="客户可见说明"
          ><el-input
            v-model="catalogForm.description"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit /></el-form-item
        ><el-form-item label="对客户发布"
          ><el-switch v-model="catalogForm.enabled" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="catalogDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveCatalog"
          >保存目录</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="reviewDialog"
      title="处理订购申请"
      width="min(560px, 94vw)"
      :close-on-click-modal="!saving"
      ><p>{{ reviewTarget?.code }} · {{ reviewTarget?.clientName }}</p
      ><el-form label-position="top" :disabled="saving"
        ><el-form-item label="处理结果"
          ><el-radio-group v-model="reviewForm.status"
            ><el-radio value="ACCEPTED">受理</el-radio
            ><el-radio value="REJECTED">拒绝</el-radio></el-radio-group
          ></el-form-item
        ><el-form-item label="客户可见反馈" required
          ><el-input
            v-model="reviewForm.reviewNote"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="填写交期确认、后续联系安排或拒绝原因，客户将直接看到此内容" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="reviewDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="review"
          >提交处理结果</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>

<script setup lang="ts">
import {
  PortalAdminApi,
  type PortalAccount,
  type PortalOrderRequest,
  type PortalProduct
} from '@/api/mes/portal'
import { checkPermi } from '@/utils/permission'
defineOptions({ name: 'MesPortalAdmin' })
const message = useMessage()
const canManage = computed(() => checkPermi(['mes:portal:manage']))
const tab = ref('accounts')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const accounts = ref<PortalAccount[]>([])
const clients = ref<{ id: number; code: string; name: string }[]>([])
const products = ref<{ id: number; code: string; name: string; specification?: string }[]>([])
const catalog = ref<PortalProduct[]>([])
const requests = ref<PortalOrderRequest[]>([])
const requestStatuses: Record<string, string> = {
  PENDING: '待受理',
  ACCEPTED: '已受理',
  REJECTED: '已拒绝'
}
const accountDialog = ref(false)
const accountForm = ref<PortalAccount>({
  clientId: undefined,
  username: '',
  displayName: '',
  password: '',
  enabled: true
})
const catalogDialog = ref(false)
const catalogForm = reactive<{ productId?: number; description: string; enabled: boolean }>({
  productId: undefined,
  description: '',
  enabled: true
})
const reviewDialog = ref(false)
const reviewTarget = ref<PortalOrderRequest>()
const reviewForm = reactive({ status: 'ACCEPTED', reviewNote: '' })
const formatTime = (value?: string) => (value ? value.replace('T', ' ').slice(0, 19) : '—')
const openPortal = () => window.open('/customer-portal', '_blank', 'noopener,noreferrer')
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await Promise.all([
      PortalAdminApi.accounts(),
      PortalAdminApi.clients(),
      PortalAdminApi.products(),
      PortalAdminApi.catalog(),
      PortalAdminApi.requests()
    ])
    accounts.value = data[0]
    clients.value = data[1]
    products.value = data[2]
    catalog.value = data[3]
    requests.value = data[4]
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '客户门户数据加载失败'
  } finally {
    loading.value = false
  }
}
function editAccount(account?: PortalAccount) {
  accountForm.value = account
    ? { ...account, password: '' }
    : { clientId: undefined, username: '', displayName: '', password: '', enabled: true }
  accountDialog.value = true
}
async function saveAccount() {
  if (
    !accountForm.value.clientId ||
    !accountForm.value.username ||
    !accountForm.value.displayName
  ) {
    message.warning('请填写客户、账号和显示名称')
    return
  }
  saving.value = true
  try {
    await PortalAdminApi.saveAccount(accountForm.value)
    accountDialog.value = false
    message.success('客户账号已保存')
    await load()
  } finally {
    saving.value = false
  }
}
function editCatalog(product?: PortalProduct) {
  catalogForm.productId = product?.productId
  catalogForm.description = product?.description || ''
  catalogForm.enabled = product?.enabled ?? true
  catalogDialog.value = true
}
async function saveCatalog() {
  if (!catalogForm.productId) {
    message.warning('请选择要发布的产品')
    return
  }
  saving.value = true
  try {
    await PortalAdminApi.saveCatalog({ ...catalogForm, productId: catalogForm.productId })
    catalogDialog.value = false
    message.success('客户目录已保存')
    await load()
  } finally {
    saving.value = false
  }
}
function openReview(item: PortalOrderRequest) {
  reviewTarget.value = item
  reviewForm.status = 'ACCEPTED'
  reviewForm.reviewNote = ''
  reviewDialog.value = true
}
async function review() {
  if (!reviewTarget.value || !reviewForm.reviewNote.trim()) {
    message.warning('请填写客户可见反馈')
    return
  }
  saving.value = true
  try {
    await PortalAdminApi.review({ id: reviewTarget.value.id, ...reviewForm })
    reviewDialog.value = false
    message.success('处理结果已提交')
    await load()
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>

<style scoped lang="scss">
.portal-toolbar {
  display: flex;
  margin-bottom: 24px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;

  h2 {
    margin: 0 0 9px;
    font-size: 22px;
  }

  p {
    margin: 0;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  > div:last-child {
    display: flex;
    flex-shrink: 0;
  }
}

.section-actions {
  display: flex;
  padding: 8px 0 20px;
  align-items: center;
  justify-content: space-between;
  gap: 15px;

  > span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.request-detail {
  padding: 12px 30px;
  font-size: 12px;

  p {
    color: var(--el-text-color-regular);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
}

@media (width <= 800px) {
  .portal-toolbar,
  .section-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
