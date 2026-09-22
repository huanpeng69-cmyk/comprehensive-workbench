<script setup lang="ts">
/**
 * 玻璃卡片：level 1-4 对应侧边栏 / 仪表盘 / 内容 / 弹窗四级玻璃。
 * 高光与折射由全局 .glass::before 提供。
 */
withDefaults(
  defineProps<{
    level?: 1 | 2 | 3 | 4
    hover?: boolean
    radius?: string
    padding?: string
  }>(),
  { level: 3, hover: false }
)
</script>

<template>
  <div
    class="glass glass-card"
    :class="[`glass-level-${level}`, { 'glass-hover': hover }]"
    :style="{ borderRadius: radius, padding }"
  >
    <slot />
  </div>
</template>

<style scoped>
.glass-card {
  box-sizing: border-box;
}
</style>
