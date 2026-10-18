<template>
  <Dialog v-model="visible" :title="`${itemName} · 价格与标准费用`" width="850px">
    <el-alert
      class="mb-16px"
      type="info"
      :closable="false"
      show-icon
      title="金额均为人民币、不含税。空值表示尚未核定；明确为零的费用请填写 0。保存生成新版本，不覆盖已冻结的工单成本。"
    />
    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      class="mb-16px"
    />
    <el-form
      ref="formRef"
      v-loading="loading"
      :model="form"
      label-width="150px"
      :rules="rules"
      :disabled="!loaded || !checkPermi(['mes:cost:update'])"
    >
      <el-form-item label="参考采购单价" prop="purchasePrice">
        <el-input
          v-model="form.purchasePrice"
          inputmode="decimal"
          clearable
          class="!w-220px"
          placeholder="未核定可留空"
        />
        <span class="ml-12px text-gray-500">仅采购叶子物料参与 BOM 材料核算</span>
      </el-form-item>
      <el-form-item label="标准销售单价" prop="salePrice">
        <el-input
          v-model="form.salePrice"
          inputmode="decimal"
          clearable
          class="!w-220px"
          placeholder="未核定可留空"
        />
      </el-form-item>
      <el-form-item label="本层单位人工费用" prop="laborCost">
        <el-input
          v-model="form.laborCost"
          inputmode="decimal"
          clearable
          class="!w-220px"
          placeholder="未核定可留空"
        />
      </el-form-item>
      <el-form-item label="本层单位制造费用" prop="overheadCost">
        <el-input
          v-model="form.overheadCost"
          inputmode="decimal"
          clearable
          class="!w-220px"
          placeholder="未核定可留空"
        />
      </el-form-item>
      <el-form-item label="调价说明" prop="remark">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="说明价格依据或费用定额来源"
        />
      </el-form-item>
    </el-form>
    <h4>价格和费用版本</h4>
    <el-table :data="history" max-height="240" size="small" empty-text="尚无价格版本">
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="采购价" min-width="110"
        ><template #default="{ row }">{{ money(row.purchasePrice) }}</template></el-table-column
      >
      <el-table-column label="售价" min-width="110"
        ><template #default="{ row }">{{ money(row.salePrice) }}</template></el-table-column
      >
      <el-table-column label="人工" min-width="110"
        ><template #default="{ row }">{{ money(row.laborCost) }}</template></el-table-column
      >
      <el-table-column label="制造费用" min-width="110"
        ><template #default="{ row }">{{ money(row.overheadCost) }}</template></el-table-column
      >
      <el-table-column prop="remark" label="说明" min-width="150" show-overflow-tooltip />
      <el-table-column
        prop="createTime"
        label="创建时间"
        min-width="180"
        :formatter="dateFormatter"
      />
    </el-table>
    <template #footer>
      <el-button @click="visible = false" :disabled="saving">关闭</el-button>
      <el-button
        v-hasPermi="['mes:cost:update']"
        type="primary"
        :loading="saving"
        :disabled="loading || !loaded"
        @click="save"
        >保存新版本</el-button
      >
    </template>
  </Dialog>
</template>
<script setup lang="ts">
import { CostApi, type CostProfile } from '@/api/mes/cost'
import { checkPermi } from '@/utils/permission'
import { dateFormatter } from '@/utils/formatTime'
import { money, optionalAmount, decimalRule } from '../format'

const emit = defineEmits<{ saved: [] }>()
const message = useMessage()
const visible = ref(false)
const loading = ref(false)
const loaded = ref(false)
const loadError = ref('')
const saving = ref(false)
const itemId = ref<number>()
const itemName = ref('')
const history = ref<CostProfile[]>([])
const formRef = ref()
const form = reactive<{
  purchasePrice?: string
  salePrice?: string
  laborCost?: string
  overheadCost?: string
  remark: string
}>({ remark: '' })
const rules = {
  purchasePrice: [decimalRule()],
  salePrice: [decimalRule()],
  laborCost: [decimalRule()],
  overheadCost: [decimalRule()],
  remark: [{ required: true, whitespace: true, message: '请填写调价或费用依据', trigger: 'blur' }]
}
let openVersion = 0
const open = async (id: number, name: string) => {
  const version = ++openVersion
  itemId.value = id
  itemName.value = name
  visible.value = true
  loading.value = true
  loaded.value = false
  loadError.value = ''
  history.value = []
  Object.assign(form, {
    purchasePrice: undefined,
    salePrice: undefined,
    laborCost: undefined,
    overheadCost: undefined,
    remark: ''
  })
  try {
    const [profile, versions] = await Promise.all([
      CostApi.getProfile(id),
      CostApi.getProfileHistory(id)
    ])
    if (version !== openVersion) return
    history.value = versions
    if (profile)
      Object.assign(form, {
        purchasePrice: optionalAmount(profile.purchasePrice),
        salePrice: optionalAmount(profile.salePrice),
        laborCost: optionalAmount(profile.laborCost),
        overheadCost: optionalAmount(profile.overheadCost),
        remark: ''
      })
    formRef.value?.clearValidate()
    loaded.value = true
  } catch {
    if (version === openVersion) loadError.value = '读取价格及历史失败，无法保存。请关闭后重试。'
  } finally {
    if (version === openVersion) loading.value = false
  }
}
const save = async () => {
  if (!itemId.value || saving.value || !loaded.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await CostApi.saveProfile({
      itemId: itemId.value,
      purchasePrice: form.purchasePrice || null,
      salePrice: form.salePrice || null,
      laborCost: form.laborCost || null,
      overheadCost: form.overheadCost || null,
      remark: form.remark.trim()
    })
    message.success('已保存新的价格和费用版本')
    visible.value = false
    emit('saved')
  } catch {
    // The request interceptor shows the server's rejection. Keep the form open.
  } finally {
    saving.value = false
  }
}
defineExpose({ open })
</script>
