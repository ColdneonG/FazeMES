<template>
  <ContentWrap>
    <el-alert
      title="显示当前企业未过期的后台登录会话。登录有效不代表用户此刻正在操作；强退同时撤销该会话的刷新能力。"
      type="info"
      :closable="false"
      class="mb-16px"
    />
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="用户ID"
        ><el-input-number v-model="query.userId" :min="1" :controls="false" clearable
      /></el-form-item>
      <el-form-item label="客户端"
        ><el-input v-model="query.clientId" clearable placeholder="筛选客户端"
      /></el-form-item>
      <el-form-item
        ><el-button type="primary" @click="search">查询</el-button
        ><el-button @click="load">刷新</el-button></el-form-item
      >
    </el-form>
    <el-table :data="rows" v-loading="loading">
      <el-table-column label="会话编号" prop="id" width="100" />
      <el-table-column label="用户ID" prop="userId" width="90" />
      <el-table-column label="账号" prop="username" />
      <el-table-column label="昵称" prop="nickname" />
      <el-table-column label="客户端" prop="clientId" />
      <el-table-column
        label="登录/签发时间"
        prop="createTime"
        :formatter="dateFormatter"
        min-width="170"
      />
      <el-table-column
        label="有效期至"
        prop="expiresTime"
        :formatter="dateFormatter"
        min-width="170"
      />
      <el-table-column label="操作" width="90"
        ><template #default="{ row }"
          ><el-button
            link
            type="danger"
            v-hasPermi="['system:online-session:revoke']"
            @click="revoke(row)"
            >强退</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <Pagination
      :total="total"
      v-model:page="query.pageNo"
      v-model:limit="query.pageSize"
      @pagination="load"
    />
  </ContentWrap>
</template>
<script setup lang="ts">
import { OnlineSessionApi, type OnlineSession } from '@/api/system/online'
import { dateFormatter } from '@/utils/formatTime'
defineOptions({ name: 'SystemOnlineSession' })
const message = useMessage()
const loading = ref(false),
  total = ref(0),
  rows = ref<OnlineSession[]>([])
const query = reactive({
  pageNo: 1,
  pageSize: 10,
  userId: undefined as number | undefined,
  clientId: ''
})
const load = async () => {
  loading.value = true
  try {
    const data = await OnlineSessionApi.page(query)
    rows.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}
const search = () => {
  query.pageNo = 1
  load()
}
const revoke = async (row: OnlineSession) => {
  await message.confirm(
    `确认强退 ${row.username || row.userId} 的该登录会话？若为当前会话，需要重新登录。`
  )
  const revoked = await OnlineSessionApi.revoke(row.id)
  message.success(revoked ? '会话已强退' : '会话已失效，请查看最新列表')
  await load()
}
onMounted(load)
</script>
