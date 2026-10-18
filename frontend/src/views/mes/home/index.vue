<template>
  <div></div>
</template>

<script setup lang="ts">
defineOptions({ name: 'MesHome' })

// 保留历史菜单组件的兼容性，首页统一使用工作台。
const router = useRouter()
onMounted(() => router.replace('/index'))
</script>
