<template>
  <el-image src="/shop-member.webp" />
</template>
<script setup lang="ts">
import { UserWalletProperty } from './config'

/** 用户资产 */
defineOptions({ name: 'UserWallet' })
// 定义属性
defineProps<{ property: UserWalletProperty }>()
</script>

<style scoped lang="scss"></style>
