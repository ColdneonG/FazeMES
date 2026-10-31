<template>
  <el-image src="/shop-member.webp" />
</template>
<script setup lang="ts">
import { UserCouponProperty } from './config'

/** 用户卡券 */
defineOptions({ name: 'UserCoupon' })
// 定义属性
defineProps<{ property: UserCouponProperty }>()
</script>

<style scoped lang="scss"></style>
