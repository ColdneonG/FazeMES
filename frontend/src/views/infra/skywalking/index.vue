<template>
  <ContentWrap :bodyStyle="{ padding: '0px' }" class="!mb-0">
    <IFrame v-if="!loading && src" v-loading="loading" :src="src" />
    <el-empty v-else-if="!loading" description="请配置示例监控服务地址" />
  </ContentWrap>
</template>
<script lang="ts" setup>
import * as ConfigApi from '@/api/infra/config'

defineOptions({ name: 'InfraSkyWalking' })

const loading = ref(true) // 是否加载中
const src = ref('')

/** 初始化 */
onMounted(async () => {
  try {
    const data = await ConfigApi.getConfigKey('url.skywalking')
    if (data && data.length > 0) {
      src.value = data
    }
  } finally {
    loading.value = false
  }
})
</script>
