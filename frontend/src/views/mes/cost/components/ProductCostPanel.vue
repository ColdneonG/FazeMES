<template>
  <el-form inline @submit.prevent>
    <el-form-item label="产品 / 物料"
      ><MdItemSelect v-model="selectedId" class="!w-280px" @change="onItemChange"
    /></el-form-item>
    <el-form-item
      ><el-button type="primary" :loading="loading" @click="load">查询成本</el-button></el-form-item
    >
    <el-form-item
      ><el-button :loading="finding" @click="findBroky">Faze-UP Broky</el-button></el-form-item
    >
  </el-form>
  <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb-16px" />
  <el-empty
    v-if="!product && !loading"
    description="选择产品查看多级 BOM、采购物料需求与标准成本"
  />
  <div v-loading="loading">
    <template v-if="product">
      <div class="cost-toolbar">
        <h2
          >{{ product.itemName }}
          <el-tag :type="product.status === 'COMPLETE' ? 'success' : 'warning'">{{
            product.status === 'COMPLETE' ? '成本完整' : '成本未完整'
          }}</el-tag></h2
        >
        <div>
          <el-button @click="profileDialog?.open(product.itemId, product.itemName)"
            >价格与费用版本</el-button
          >
          <el-button @click="exportCostSnapshot(`${product.itemCode}-标准成本`, product)"
            >导出成本快照</el-button
          >
        </div>
      </div>
      <el-descriptions :column="3" border class="mb-16px">
        <el-descriptions-item label="物料编码">{{ product.itemCode }}</el-descriptions-item>
        <el-descriptions-item label="直接 BOM 子项"
          >{{ product.children?.length ?? 0 }} 种</el-descriptions-item
        >
        <el-descriptions-item label="展开采购物料"
          >{{ product.leaves?.length ?? 0 }} 种</el-descriptions-item
        >
        <el-descriptions-item label="标准销售单价">{{
          money(product.salePrice)
        }}</el-descriptions-item>
        <el-descriptions-item label="标准材料成本">{{
          money(product.materialCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="标准制造成本">{{
          money(product.totalCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="标准人工成本（含各层）">{{
          money(product.laborCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="标准制造费用（含各层）">{{
          money(product.overheadCost)
        }}</el-descriptions-item>
        <el-descriptions-item label="核算口径">1 个产品计量单位 / CNY 不含税</el-descriptions-item>
      </el-descriptions>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="mb-16px"
        title="半成品按各自单单位成本逐层计入上层。采购叶子汇总用于需求分析；父工单实际领料使用本层 BOM，避免重复扣料。标准价格不代表实际采购价或成交价。"
      />
      <el-alert
        v-for="issue in product.issues"
        :key="issue"
        :title="issue"
        type="warning"
        :closable="false"
        show-icon
        class="mb-8px"
      />
      <el-tabs v-model="detailTab">
        <el-tab-pane label="多级 BOM 与成本" name="tree">
          <el-table
            :data="treeRows"
            row-key="nodeKey"
            default-expand-all
            border
            :tree-props="{ children: 'children' }"
          >
            <el-table-column label="产品 / 物料" prop="itemName" min-width="260" />
            <el-table-column label="编码" prop="itemCode" min-width="150" />
            <el-table-column label="本层标准用量" min-width="130"
              ><template #default="{ row }"
                >{{ costNumber(row.quantity ?? 1) }} {{ row.unitMeasureName }}</template
              ></el-table-column
            >
            <el-table-column label="采购单价" min-width="110"
              ><template #default="{ row }">{{
                row.children?.length ? '—' : money(row.purchasePrice)
              }}</template></el-table-column
            >
            <el-table-column label="单单位材料成本" min-width="140"
              ><template #default="{ row }">{{
                money(row.materialCost)
              }}</template></el-table-column
            >
            <el-table-column label="单单位总成本" min-width="140"
              ><template #default="{ row }">{{ money(row.totalCost) }}</template></el-table-column
            >
            <el-table-column label="状态" width="100"
              ><template #default="{ row }"
                ><el-tag :type="row.status === 'COMPLETE' ? 'success' : 'warning'">{{
                  row.status === 'COMPLETE' ? '完整' : '未完整'
                }}</el-tag></template
              ></el-table-column
            >
            <el-table-column
              v-if="checkPermi(['mes:cost:update'])"
              label="定价"
              width="90"
              fixed="right"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  @click="profileDialog?.open(row.itemId, row.itemName)"
                  >维护</el-button
                ></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`采购叶子汇总（${product.leaves?.length ?? 0}）`" name="leaves">
          <el-table :data="product.leaves" border>
            <el-table-column prop="itemCode" label="物料编码" min-width="150" />
            <el-table-column prop="itemName" label="物料名称" min-width="200" />
            <el-table-column label="每产品需求" min-width="130"
              ><template #default="{ row }"
                >{{ costNumber(row.quantity) }} {{ row.unitMeasureName }}</template
              ></el-table-column
            >
            <el-table-column label="参考采购单价" min-width="130"
              ><template #default="{ row }">{{
                money(row.purchasePrice)
              }}</template></el-table-column
            >
            <el-table-column label="标准材料金额" min-width="130"
              ><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column
            >
            <el-table-column v-if="checkPermi(['mes:cost:update'])" label="操作" width="100"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  @click="profileDialog?.open(row.itemId, row.itemName)"
                  >维护价格</el-button
                ></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`缺价物料（${missingPrices.length}）`" name="missing">
          <el-table
            :data="missingPrices"
            empty-text="采购物料参考价格已齐全；费用完整性请查看上方核算提示"
            border
          >
            <el-table-column prop="itemCode" label="编码" min-width="150" />
            <el-table-column prop="itemName" label="物料" min-width="200" />
            <el-table-column label="缺失项目"
              ><template #default>参考采购单价</template></el-table-column
            >
            <el-table-column v-if="checkPermi(['mes:cost:update'])" label="操作" width="100"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  @click="profileDialog?.open(row.itemId, row.itemName)"
                  >补录</el-button
                ></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
  <CostProfileDialog ref="profileDialog" @saved="load" />
</template>
<script setup lang="ts">
import { CostApi, type ProductCost } from '@/api/mes/cost'
import { MdItemApi, type MdItemVO } from '@/api/mes/md/item'
import { checkPermi } from '@/utils/permission'
import MdItemSelect from '@/views/mes/md/item/components/MdItemSelect.vue'
import CostProfileDialog from './CostProfileDialog.vue'
import { costNumber, money, exportCostSnapshot } from '../format'

const props = defineProps<{ itemId?: number }>()
const selectedId = ref<number>()
const product = ref<ProductCost>()
const loading = ref(false)
const finding = ref(false)
const error = ref('')
const detailTab = ref('tree')
const profileDialog = ref<InstanceType<typeof CostProfileDialog>>()
let loadVersion = 0
type TreeCost = Omit<ProductCost, 'children'> & { nodeKey: string; children: TreeCost[] }
const asTree = (node: ProductCost, path: string): TreeCost => ({
  ...node,
  nodeKey: path,
  children: (node.children ?? []).map((child, index) => asTree(child, `${path}.${index}`))
})
const treeRows = computed(() => (product.value ? [asTree(product.value, 'root')] : []))
const missingPrices = computed(() =>
  (product.value?.leaves ?? []).filter((leaf) => leaf.purchasePrice == null)
)
const load = async () => {
  const version = ++loadVersion
  const id = selectedId.value
  product.value = undefined
  error.value = ''
  loading.value = false
  if (!id) return
  loading.value = true
  try {
    const result = await CostApi.getProduct(id)
    if (version === loadVersion) product.value = result
  } catch {
    if (version === loadVersion) error.value = '产品成本加载失败，请刷新重试。'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
const onItemChange = () => load()
const findBroky = async () => {
  finding.value = true
  try {
    const result = await MdItemApi.getItemPage({ pageNo: 1, pageSize: 100, name: 'Faze-UP Broky' })
    const exact = (result.list as MdItemVO[]).find((item) => item.name === 'Faze-UP Broky')
    if (!exact) {
      error.value = '尚未找到 Faze-UP Broky 产品，请先应用产品主数据迁移。'
      return
    }
    selectedId.value = exact.id
    await load()
  } finally {
    finding.value = false
  }
}
watch(
  () => props.itemId,
  (id) => {
    selectedId.value = id
    load()
  },
  { immediate: true }
)
</script>
