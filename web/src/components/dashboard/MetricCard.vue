<script setup lang="ts">
/**
 * KPI 指标卡：数字必须有视觉层级。
 */
import type { Component } from 'vue'

defineProps<{
  label: string
  value: string | number
  desc?: string
  icon?: string | Component
  accent?: boolean
}>()
</script>

<template>
  <div class="metric glass glass-level-2 glass-hover" :class="{ 'metric-accent': accent }">
    <div class="metric-top">
      <div class="metric-icon" v-if="icon">
        <el-icon :size="17"><component :is="icon" /></el-icon>
      </div>
      <span class="metric-label">{{ label }}</span>
    </div>
    <div class="metric-value">{{ value }}</div>
    <div v-if="desc" class="metric-desc">{{ desc }}</div>
  </div>
</template>

<style scoped>
.metric {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  height: 100%;
  box-sizing: border-box;
}
.metric-top {
  display: flex;
  align-items: center;
  gap: 9px;
}
.metric-icon {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.9);
  color: var(--sunrise-amber);
}
.metric-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-secondary);
}
.metric-value {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.5px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.metric-accent .metric-value {
  background: linear-gradient(150deg, var(--sunrise-amber), var(--sunrise-amber-deep));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.metric-desc {
  font-size: 11.5px;
  color: var(--text-tertiary);
}
</style>
