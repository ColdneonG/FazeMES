<template>
  <el-card shadow="never" class="mb-16px">
    <template #header>
      <div class="shortcut-header">
        <span>岗位常用入口</span>
        <span class="shortcut-hint">根据当前账号的业务权限提供</span>
      </div>
    </template>
    <div class="shortcuts">
      <el-button
        v-for="item in items"
        :key="item.path"
        class="shortcut"
        @click="emit('navigate', item.path)"
      >
        <Icon :icon="item.icon" :size="22" />
        <span>{{ item.name }}</span>
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
defineOptions({ name: 'HomeShortcuts' })

defineProps<{ items: { name: string; path: string; icon: string }[] }>()
const emit = defineEmits<{ navigate: [path: string] }>()
</script>

<style scoped lang="scss">
.shortcut-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.shortcut-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.shortcuts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.shortcut {
  height: 64px;
  margin: 0;
  font-size: 14px;

  :deep(> span) {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}
</style>
