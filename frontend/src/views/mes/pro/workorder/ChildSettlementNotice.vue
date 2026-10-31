<template>
  <el-alert
    v-if="error || children.length"
    :type="warning ? 'warning' : 'success'"
    :closable="false"
    show-icon
    class="mb-15px"
  >
    <template #title>{{
      error || (warning ? '自制半成品有未结算的子工单' : '子工单均已结算')
    }}</template>
    <template v-if="error">
      <el-button link @click="load">重新查询</el-button>
    </template>
    <template v-else>
      <p v-if="warning"
        >父工单耗用自制半成品前，请先完成对应子工单的检验、入库与完工，逐工序记录实际工时和制造费用，再结算并锁定。</p
      >
      <el-button
        v-for="child in children"
        :key="child.id"
        link
        type="primary"
        @click="openCost(child.id)"
      >
        {{ child.code }} · {{ child.settled ? '已结算' : '查看未结算成本' }}
      </el-button>
    </template>
  </el-alert>
</template>
<script setup lang="ts">
import { CostApi, type ChildSettlement } from '@/api/mes/cost'
import { openCostCenter } from '@/views/mes/cost/navigation'

const props = defineProps<{ workorderId: number }>()
const emit = defineEmits<{ navigate: [] }>()
const children = ref<ChildSettlement[]>([])
const error = ref('')
const warning = computed(
  () => Boolean(error.value) || children.value.some((child) => !child.settled)
)
const router = useRouter()
const load = async () => {
  try {
    children.value = await CostApi.getChildSettlements(props.workorderId)
    error.value = ''
  } catch {
    error.value = '子工单结算状态未能读取。'
  }
}
const openCost = async (workorderId: number) => {
  if (!(await openCostCenter(router, { workorderId }))) emit('navigate')
}
onMounted(load)
</script>
