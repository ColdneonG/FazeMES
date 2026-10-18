<template>
  <div class="customer-portal">
    <header class="portal-header">
      <a class="portal-brand" href="/customer-portal"
        ><img src="/fazemes-logo.svg" alt="FazeMES 标志" width="36" height="36" /><strong
          >FazeMES</strong
        ><em>客户服务</em></a
      >
      <div v-if="account" class="portal-user"
        ><span>{{ account.clientName }} · {{ account.displayName }}</span
        ><el-button text @click="logout">退出登录</el-button></div
      >
      <a v-else href="/login" class="staff-entry">员工登录</a>
    </header>
    <main v-if="!account" class="login-stage">
      <section class="login-intro"
        ><span class="intro-kicker">CUSTOMER WORKSPACE</span
        ><h1>从产品选购<br />到生产进度，随时可见。</h1
        ><p>提交订购需求，查看供应方受理反馈与生产订单进度。</p
        ><div class="intro-features"
          ><span>产品目录</span><span>在线申请</span><span>订单进度</span></div
        ></section
      >
      <section class="login-card">
        <h2>登录客户门户</h2><p>使用供应方为您开通的客户账号。</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-form label-position="top" @submit.prevent="login">
          <el-form-item label="租户编号"
            ><el-input-number
              v-model="loginForm.tenantId"
              :min="1"
              :max="9007199254740991"
              :controls="false"
              placeholder="由供应方提供"
          /></el-form-item>
          <el-form-item label="客户账号"
            ><el-input
              v-model="loginForm.username"
              autocomplete="username"
              maxlength="64"
              placeholder="请输入客户账号"
          /></el-form-item>
          <el-form-item label="密码"
            ><el-input
              v-model="loginForm.password"
              type="password"
              show-password
              autocomplete="current-password"
              maxlength="128"
              placeholder="请输入密码"
          /></el-form-item>
          <el-button native-type="submit" type="primary" :loading="busy" class="login-button"
            >进入客户工作台</el-button
          >
        </el-form>
      </section>
    </main>
    <div v-else class="portal-shell">
      <aside class="portal-nav"
        ><p>客户工作台</p
        ><button :class="{ active: tab === 'catalog' }" @click="tab = 'catalog'"
          ><Icon icon="ep:goods" />产品目录<span>{{ products.length }}</span></button
        ><button :class="{ active: tab === 'requests' }" @click="tab = 'requests'"
          ><Icon icon="ep:document" />我的订购申请<span>{{ requests.length }}</span></button
        ><button :class="{ active: tab === 'orders' }" @click="tab = 'orders'"
          ><Icon icon="ep:trend-charts" />生产订单进度<span>{{ orders.length }}</span></button
        ><div class="nav-note">{{ account.clientName }}<br />仅显示本客户业务记录</div></aside
      >
      <main class="portal-main" v-loading="busy">
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          show-icon
          class="portal-error"
        />
        <div class="workspace-heading"
          ><div
            ><h1>{{ tabTitles[tab] }}</h1
            ><p>{{
              tab === 'catalog'
                ? '选择产品并提交订购申请，交期与商务条件由供应方确认。'
                : tab === 'requests'
                  ? '查看最近200条申请及供应方的受理反馈。'
                  : '查看最近200张已确认、完成或取消的生产工单。'
            }}</p></div
          ><div class="workspace-actions"
            ><el-button @click="reload">刷新</el-button
            ><el-button v-if="tab === 'catalog'" type="primary" @click="checkoutOpen = true"
              >购物车 · {{ cart.length }}</el-button
            ></div
          ></div
        >
        <template v-if="tab === 'catalog'">
          <el-input
            v-model="search"
            clearable
            placeholder="搜索产品名称、编码、规格"
            class="product-search"
            ><template #prefix><Icon icon="ep:search" /></template
          ></el-input>
          <el-empty
            v-if="!filteredProducts.length"
            description="暂无可订购产品，请联系供应方发布产品目录"
          />
          <div v-else class="product-grid"
            ><article
              v-for="product in filteredProducts"
              :key="product.productId"
              class="product-card"
              ><div class="product-placeholder"
                ><Icon icon="ep:box" /><span>{{ product.productCode }}</span></div
              ><div class="product-content"
                ><h3>{{ product.productName }}</h3
                ><p class="specification">{{ product.specification || '规格请咨询供应方' }}</p
                ><p class="product-description">{{
                  product.description || '欢迎提交订购需求，供应方将与您确认详细信息。'
                }}</p
                ><p v-if="product.referenceSalePrice != null" class="reference-price"
                  >参考售价 {{ money(product.referenceSalePrice) }} /
                  {{ product.unitName || '单位' }}（不含税）<small>成交以销售确认价为准</small></p
                ><div class="product-footer"
                  ><span
                    >{{ product.unitName || '按产品计量' }} ·
                    {{ product.referenceSalePrice == null ? '询价' : '商务条件待确认' }}</span
                  ><el-button type="primary" plain @click="addProduct(product)"
                    >加入购物车</el-button
                  ></div
                ></div
              ></article
            ></div
          >
        </template>
        <template v-else-if="tab === 'requests'">
          <el-empty v-if="!requests.length" description="暂无订购申请" />
          <div class="request-list"
            ><article v-for="item in requests" :key="item.id" class="request-card"
              ><div class="request-heading"
                ><strong>{{ item.code }}</strong
                ><el-tag
                  :type="
                    item.status === 'REJECTED'
                      ? 'danger'
                      : item.status === 'ACCEPTED'
                        ? 'success'
                        : 'warning'
                  "
                  >{{ requestStatus[item.status] }}</el-tag
                ></div
              ><p class="order-dates"
                >期望交期 {{ item.deliveryDate }} · 提交于 {{ formatTime(item.createTime) }}</p
              ><div v-for="line in item.items" :key="line.productId" class="request-line"
                ><span
                  >{{ line.productName
                  }}<small>{{ line.productCode }} · {{ line.specification }}</small></span
                ><strong>{{ line.quantity }} {{ line.unitName }}</strong></div
              ><p v-if="item.remark" class="customer-note">我的备注：{{ item.remark }}</p
              ><div v-if="item.reviewNote" class="review-feedback"
                ><strong>供应方反馈</strong><p>{{ item.reviewNote }}</p
                ><small>{{ formatTime(item.reviewTime) }}</small></div
              ></article
            ></div
          >
        </template>
        <template v-else>
          <el-empty v-if="!orders.length" description="暂无已确认生产订单" />
          <div class="orders-grid"
            ><article v-for="order in orders" :key="order.id" class="production-order"
              ><div class="request-heading"
                ><strong>{{ order.code }}</strong
                ><el-tag
                  :type="order.status === 3 ? 'info' : order.status === 2 ? 'success' : 'primary'"
                  >{{ orderStatus(order) }}</el-tag
                ></div
              ><h3>{{ order.productName || order.name }}</h3
              ><p class="specification">{{ order.specification }}</p
              ><el-progress
                :percentage="progress(order)"
                :status="order.status === 2 ? 'success' : undefined"
              /><div class="production-quantity"
                ><span>已生产 {{ order.quantityProduced || 0 }}</span
                ><strong>订单数量 {{ order.quantity }}</strong></div
              ><p class="order-dates"
                >需求日期：{{ formatTime(order.requestDate) }}<br v-if="order.finishDate" /><span
                  v-if="order.finishDate"
                  >完成时间：{{ formatTime(order.finishDate) }}</span
                ></p
              ></article
            ></div
          >
        </template>
      </main>
    </div>
    <el-drawer
      v-model="checkoutOpen"
      title="确认订购申请"
      size="min(520px, 100%)"
      :close-on-click-modal="!submitting"
    >
      <el-empty v-if="!cart.length" description="购物车为空，请先选择产品" />
      <div v-else class="checkout-content"
        ><div v-for="line in cart" :key="line.productId" class="cart-line"
          ><div
            ><strong>{{ line.productName }}</strong
            ><small>{{ line.productCode }} · {{ line.unitName }}</small></div
          ><el-input-number
            v-model="line.quantity"
            :min="0.000001"
            :max="1000000"
            :precision="6"
            :step="1"
          /><el-button text type="danger" @click="removeProduct(line.productId)"
            >移除</el-button
          ></div
        ><el-form label-position="top" class="checkout-form"
          ><el-form-item label="期望交期"
            ><el-date-picker
              v-model="deliveryDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择日期"
              :disabled-date="disabledDate" /></el-form-item
          ><el-form-item label="订购备注"
            ><el-input
              v-model="remark"
              type="textarea"
              :rows="3"
              maxlength="1000"
              show-word-limit /></el-form-item></el-form
        ><el-alert
          title="申请提交后由供应方审核受理，生产订单在确认后显示于订单进度。"
          type="info"
          :closable="false"
      /></div>
      <template #footer
        ><el-button @click="checkoutOpen = false">继续选购</el-button
        ><el-button
          type="primary"
          :disabled="!cart.length || !deliveryDate"
          :loading="submitting"
          @click="submit"
          >提交申请</el-button
        ></template
      >
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { money } from '@/views/mes/cost/format'
import { generateUUID } from '@/utils'
import { ElMessage } from 'element-plus'
import {
  CustomerPortalApi,
  clearPortalSession,
  readPortalSession,
  type PortalIdentity,
  type PortalOrder,
  type PortalOrderRequest,
  type PortalProduct,
  type PortalRequestLine
} from '@/api/mes/portal'

defineOptions({ name: 'CustomerPortal' })
type Tab = 'catalog' | 'requests' | 'orders'
const account = ref<PortalIdentity | undefined>(readPortalSession()?.account)
const loginForm = reactive<{ tenantId?: number; username: string; password: string }>({
  tenantId: undefined,
  username: '',
  password: ''
})
const busy = ref(false)
const submitting = ref(false)
const error = ref('')
const tab = ref<Tab>('catalog')
const tabTitles = { catalog: '产品目录', requests: '我的订购申请', orders: '生产订单进度' }
const requestStatus = { PENDING: '待受理', ACCEPTED: '已受理', REJECTED: '未受理' }
const products = ref<PortalProduct[]>([])
const requests = ref<PortalOrderRequest[]>([])
const orders = ref<PortalOrder[]>([])
const search = ref('')
const cart = ref<PortalRequestLine[]>([])
const checkoutOpen = ref(false)
const deliveryDate = ref('')
const remark = ref('')
const requestKey = ref(generateUUID())
const filteredProducts = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return products.value.filter((p) =>
    `${p.productCode} ${p.productName} ${p.specification || ''}`.toLowerCase().includes(keyword)
  )
})
watch(
  [cart, deliveryDate, remark],
  () => {
    requestKey.value = generateUUID()
  },
  { deep: true }
)
const formatTime = (value?: string) => (value ? value.replace('T', ' ').slice(0, 19) : '—')
const progress = (order: PortalOrder) =>
  Number(order.quantity) > 0
    ? Math.min(
        100,
        Math.max(
          0,
          Math.round((Number(order.quantityProduced || 0) / Number(order.quantity)) * 100)
        )
      )
    : 0
const orderStatus = (order: PortalOrder) =>
  order.status === 2
    ? '已完成'
    : order.status === 3
      ? '已取消'
      : Number(order.quantityProduced) > 0
        ? '生产中'
        : '已确认'
const disabledDate = (date: Date) => date.getTime() < new Date().setHours(0, 0, 0, 0)
function showError(reason: unknown) {
  error.value = reason instanceof Error ? reason.message : '请求失败，请稍后重试'
  if (!readPortalSession()) {
    account.value = undefined
    cart.value = []
    checkoutOpen.value = false
  }
}
async function reload() {
  busy.value = true
  error.value = ''
  try {
    account.value = await CustomerPortalApi.me()
    const data = await Promise.all([
      CustomerPortalApi.catalog(),
      CustomerPortalApi.requests(),
      CustomerPortalApi.orders()
    ])
    products.value = data[0]
    requests.value = data[1]
    orders.value = data[2]
  } catch (reason) {
    showError(reason)
  } finally {
    busy.value = false
  }
}
async function login() {
  error.value = ''
  if (!loginForm.tenantId || !loginForm.username.trim() || !loginForm.password) {
    error.value = '请填写租户编号、账号和密码'
    return
  }
  busy.value = true
  try {
    const session = await CustomerPortalApi.login({
      tenantId: loginForm.tenantId,
      username: loginForm.username,
      password: loginForm.password
    })
    account.value = session.account
    loginForm.password = ''
    await reload()
  } catch (reason) {
    showError(reason)
  } finally {
    busy.value = false
  }
}
async function logout() {
  try {
    await CustomerPortalApi.logout()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '退出会话失败'
  } finally {
    clearPortalSession()
    account.value = undefined
    cart.value = []
    products.value = []
    orders.value = []
    requests.value = []
  }
}
function addProduct(product: PortalProduct) {
  const line = cart.value.find((item) => item.productId === product.productId)
  if (line) line.quantity = Math.min(1000000, Number(line.quantity || 0) + 1)
  else cart.value.push({ ...product, quantity: 1 })
  checkoutOpen.value = true
}
function removeProduct(id: number) {
  cart.value = cart.value.filter((line) => line.productId !== id)
}
async function submit() {
  if (!deliveryDate.value || !cart.value.length || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const result = await CustomerPortalApi.submit({
      requestKey: requestKey.value,
      deliveryDate: deliveryDate.value,
      remark: remark.value,
      items: cart.value.map((line) => ({ productId: line.productId, quantity: line.quantity }))
    })
    ElMessage.success(`申请 ${result.code} 已提交`)
    cart.value = []
    remark.value = ''
    checkoutOpen.value = false
    tab.value = 'requests'
    await reload()
  } catch (reason) {
    showError(reason)
    ElMessage.error(error.value)
  } finally {
    submitting.value = false
  }
}
onMounted(() => {
  if (account.value) void reload()
})
</script>

<style scoped lang="scss">
.customer-portal {
  min-height: 100vh;
  color: #1b293d;
  background: #f3f6fb;
}

.portal-header {
  display: flex;
  min-height: 74px;
  padding: 0 5vw;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #e5eaf2;
}

.portal-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #1c2c43;
  text-decoration: none;

  strong {
    font-size: 23px;
  }

  img {
    width: 36px;
    height: 36px;
    object-fit: contain;
  }

  em {
    margin-left: 8px;
    font-size: 13px;
    font-style: normal;
    color: #68788e;
  }
}

.portal-user {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
}

.staff-entry {
  font-size: 13px;
  color: #52667e;
}

.login-stage {
  display: grid;
  max-width: 1080px;
  min-height: calc(100vh - 75px);
  padding: 48px 24px;
  margin: auto;
  grid-template-columns: 1.2fr 1fr;
  align-items: center;
  gap: 65px;
}

.intro-kicker {
  font-size: 12px;
  letter-spacing: 3px;
  color: #0b8f78;
}

.login-intro h1 {
  margin: 20px 0;
  font-size: clamp(30px, 3vw, 44px);
  line-height: 1.5;
  letter-spacing: -1px;
}

.login-intro p {
  line-height: 1.9;
  color: #6b7c91;
}

.intro-features {
  display: flex;
  margin-top: 32px;
  gap: 12px;

  span {
    padding: 8px 14px;
    font-size: 12px;
    color: #128971;
    background: #e3f4ee;
    border-radius: 6px;
  }
}

.login-card {
  padding: 36px;
  background: #fff;
  border: 1px solid #e6ebf3;
  border-radius: 16px;
  box-shadow: 0 15px 50px #233a6010;

  h2 {
    margin: 0 0 8px;
    font-size: 23px;
  }

  > p {
    margin-bottom: 26px;
    font-size: 13px;
    color: #7a8798;
  }

  .el-input-number {
    width: 100%;
  }

  .el-alert {
    margin-bottom: 16px;
  }
}

.login-button {
  width: 100%;
  margin-top: 8px;
}

.portal-shell {
  display: grid;
  max-width: 1680px;
  min-height: calc(100vh - 75px);
  margin: auto;
  grid-template-columns: 220px minmax(0, 1fr);
}

.portal-nav {
  display: flex;
  padding: 28px 16px;
  background: #fff;
  border-right: 1px solid #e5eaf2;
  flex-direction: column;
  gap: 7px;

  > p {
    padding-left: 15px;
    margin-bottom: 15px;
    font-size: 12px;
    color: #98a3b1;
  }

  button {
    display: flex;
    padding: 14px 12px;
    color: #52627a;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 8px;
    align-items: center;
    gap: 10px;

    span {
      margin-left: auto;
      font-size: 11px;
    }
  }

  button.active {
    font-weight: 600;
    color: #07856c;
    background: #e8f5f0;
  }
}

.nav-note {
  padding: 20px 12px 0;
  margin-top: auto;
  font-size: 11px;
  line-height: 1.8;
  color: #8794a4;
}

.portal-main {
  min-width: 0;
  padding: 32px;
}

.portal-error {
  margin-bottom: 18px;
}

.workspace-heading {
  display: flex;
  margin-bottom: 25px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;

  h1 {
    margin: 0 0 10px;
    font-size: 24px;
  }

  p {
    margin: 0;
    font-size: 12px;
    line-height: 1.7;
    color: #7a8799;
  }
}

.workspace-actions {
  display: flex;
  flex-shrink: 0;
}

.product-search {
  max-width: 450px;
  margin-bottom: 22px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 20px;
}

.product-card {
  overflow: hidden;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 11px;
}

.product-placeholder {
  display: flex;
  min-height: 135px;
  color: #7895a3;
  background: linear-gradient(130deg, #e8f2f0, #eef3f9);
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;

  .iconify {
    font-size: 38px;
  }

  span {
    font-size: 11px;
    letter-spacing: 1px;
  }
}

.product-content {
  padding: 20px;

  h3 {
    margin: 0 0 8px;
    font-size: 16px;
  }
}

.specification {
  min-height: 18px;
  font-size: 12px;
  color: #7b899b;
}

.product-description {
  min-height: 43px;
  margin: 16px 0 22px;
  font-size: 12px;
  line-height: 1.8;
  color: #7a889a;
  overflow-wrap: anywhere;
}

.product-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  > span {
    font-size: 11px;
    color: #128770;
  }
}

.reference-price {
  font-weight: 600;
  color: #128770;

  small {
    display: block;
    margin-top: 6px;
    font-size: 11px;
    font-weight: 400;
    color: #7a889a;
  }
}

.request-list {
  display: grid;
  gap: 18px;
}

.request-card,
.production-order {
  padding: 22px 25px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}

.request-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;

  > strong {
    font-size: 13px;
    overflow-wrap: anywhere;
  }
}

.order-dates {
  font-size: 11px;
  line-height: 1.9;
  color: #8a96a6;
}

.request-line {
  display: flex;
  padding: 15px 0;
  font-size: 13px;
  border-top: 1px solid #eff2f7;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  small {
    display: block;
    margin-top: 6px;
    font-size: 11px;
    color: #8b98a8;
  }

  > strong {
    flex-shrink: 0;
  }
}

.customer-note {
  font-size: 12px;
  color: #6b7b90;
  overflow-wrap: anywhere;
}

.review-feedback {
  padding: 14px;
  font-size: 12px;
  line-height: 1.6;
  background: #edf7f3;
  border-radius: 6px;

  > strong {
    color: #12816b;
  }

  p {
    margin: 8px 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  small {
    color: #839588;
  }
}

.orders-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 18px;
}

.production-order {
  h3 {
    margin-top: 25px;
    font-size: 17px;
  }

  .el-progress {
    margin-top: 25px;
  }
}

.production-quantity {
  display: flex;
  margin: 15px 0 24px;
  font-size: 11px;
  color: #6e7d91;
  justify-content: space-between;
  gap: 10px;

  strong {
    font-weight: 500;
    color: #344b62;
  }
}

.cart-line {
  display: flex;
  padding: 18px 0;
  border-bottom: 1px solid #e9eef3;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;

  > div:first-child {
    flex: 1 1 180px;
  }

  strong {
    font-size: 14px;
  }

  small {
    display: block;
    margin-top: 7px;
    font-size: 11px;
    color: #8a97a8;
  }

  .el-input-number {
    width: 160px;
  }
}

.checkout-form {
  margin-top: 28px;
}

@media (width <= 900px) {
  .portal-shell {
    grid-template-columns: 1fr;
  }

  .portal-nav {
    padding: 12px 20px;
    overflow-x: auto;
    border-bottom: 1px solid #e5eaf2;
    flex-direction: row;

    > p,
    .nav-note {
      display: none;
    }

    button {
      white-space: nowrap;
    }
  }

  .portal-main {
    padding: 24px 18px;
  }

  .login-stage {
    gap: 25px;
  }

  .login-intro h1 {
    font-size: 30px;
  }
}

@media (width <= 640px) {
  .portal-header {
    padding: 15px 18px;
    flex-wrap: wrap;
  }

  .portal-user {
    width: 100%;
    justify-content: space-between;
  }

  .login-stage {
    grid-template-columns: 1fr;
    padding: 30px 18px;
  }

  .login-intro {
    display: none;
  }

  .login-card {
    padding: 28px;
  }

  .workspace-heading {
    flex-wrap: wrap;
  }

  .product-grid,
  .orders-grid {
    grid-template-columns: 1fr;
  }
}
</style>
