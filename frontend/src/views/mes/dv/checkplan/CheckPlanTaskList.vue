<template>
  <div>
    <el-alert
      title="启用后自动生成到期任务；停用后停止新任务，已有任务仍可完成。"
      type="info"
      :closable="false"
      class="mb-10px"
    />
    <el-button @click="load" class="mb-10px">刷新任务</el-button>
    <el-table :data="tasks" v-loading="loading">
      <el-table-column label="编号" prop="id" width="80" />
      <el-table-column label="设备" prop="machineryName" />
      <el-table-column label="计划时间" prop="dueTime" width="170" :formatter="dateFormatter" />
      <el-table-column label="状态" width="90"
        ><template #default="{ row }">{{
          ['待领用', '执行中', '已完成'][row.status]
        }}</template></el-table-column
      >
      <el-table-column label="执行人ID" prop="userId" width="95" />
      <el-table-column label="维修单ID" prop="repairId" width="95" />
      <el-table-column label="操作" width="150"
        ><template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="claim(row.id)"
            >领用</el-button
          >
          <el-button link type="primary" @click="open(row.id)">{{
            row.status === 1 && row.userId === userId ? '执行' : '查看'
          }}</el-button>
        </template></el-table-column
      >
    </el-table>
    <Pagination
      :total="total"
      v-model:page="query.pageNo"
      v-model:limit="query.pageSize"
      @pagination="load"
    />
    <Dialog v-model="visible" title="设备任务项目" width="850px">
      <el-table :data="current?.lines || []">
        <el-table-column label="项目" prop="subjectName" />
        <el-table-column label="内容" prop="content" />
        <el-table-column label="标准" prop="standard" />
        <el-table-column label="结果" width="110"
          ><template #default="{ row }">
            <el-select v-model="row.result" :disabled="!canExecute" placeholder="请选择">
              <el-option label="正常" :value="1" /><el-option label="异常" :value="2" />
            </el-select> </template
        ></el-table-column>
        <el-table-column label="异常/结果描述" min-width="190"
          ><template #default="{ row }">
            <el-input
              v-model="row.description"
              type="textarea"
              :disabled="!canExecute"
              :maxlength="2000"
            /> </template
        ></el-table-column>
      </el-table>
      <template #footer>
        <el-button v-if="canExecute" type="primary" :loading="saving" @click="complete"
          >提交结果</el-button
        >
        <el-button @click="visible = false">关闭</el-button>
      </template>
    </Dialog>
  </div>
</template>
<script setup lang="ts">
import { DvTaskApi, type DvTask } from '@/api/mes/dv/task'
import { useUserStore } from '@/store/modules/user'
import { dateFormatter } from '@/utils/formatTime'
const props = defineProps<{ planId: number }>()
const userId = computed(() => useUserStore().getUser.id)
const message = useMessage()
const tasks = ref<DvTask[]>([])
const loading = ref(false),
  saving = ref(false),
  visible = ref(false)
const total = ref(0)
const current = ref<DvTask>()
const query = reactive({ pageNo: 1, pageSize: 10 })
const canExecute = computed(
  () => current.value?.status === 1 && current.value?.userId === userId.value
)
const load = async () => {
  loading.value = true
  try {
    const data = await DvTaskApi.page({ ...query, planId: props.planId })
    tasks.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}
const claim = async (id: number) => {
  await DvTaskApi.claim(id)
  message.success('领用成功')
  await load()
}
const open = async (id: number) => {
  current.value = await DvTaskApi.get(id)
  visible.value = true
}
const complete = async () => {
  const task = current.value
  if (!task?.lines?.length) return
  if (task.lines.some((l) => !l.result || (l.result === 2 && !l.description?.trim()))) {
    message.warning('请逐项填写结果，异常项目必须填写描述')
    return
  }
  saving.value = true
  try {
    await DvTaskApi.complete({
      id: task.id,
      lines: task.lines.map((l) => ({ id: l.id, result: l.result, description: l.description }))
    })
    message.success('任务已完成，异常项目已自动报修')
    visible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
watch(
  () => props.planId,
  () => {
    query.pageNo = 1
    load()
  },
  { immediate: true }
)
</script>
