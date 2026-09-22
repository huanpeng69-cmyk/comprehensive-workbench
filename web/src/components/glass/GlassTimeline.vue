<script setup lang="ts">
/**
 * 玻璃流程时间线
 *  done    已完成 —— 暖白 + 金色细节
 *  current 当前节点 —— 金色光晕
 *  pending 未来节点 —— 低透明度
 *  skipped 条件未命中 —— 灰
 */
interface TimelineNode {
  name: string
  state: string
  assigneeName?: string
  time?: string | null
  comment?: string | null
}

defineProps<{
  nodes: TimelineNode[]
}>()

const STATE_LABEL: Record<string, string> = {
  DONE: '已完成',
  CURRENT: '进行中',
  PENDING: '待处理',
  SKIPPED: '条件未命中'
}
</script>

<template>
  <div class="gtimeline">
    <div
      v-for="(node, index) in nodes"
      :key="index"
      class="gtimeline-item"
      :class="`state-${node.state.toLowerCase()}`"
    >
      <div class="gtimeline-rail">
        <span class="gtimeline-dot"></span>
        <span v-if="index < nodes.length - 1" class="gtimeline-line"></span>
      </div>
      <div class="gtimeline-content">
        <div class="gtimeline-head">
          <span class="gtimeline-name">{{ node.name }}</span>
          <span class="gtimeline-state">{{ STATE_LABEL[node.state] || node.state }}</span>
        </div>
        <div v-if="node.assigneeName" class="gtimeline-meta">
          {{ node.assigneeName }}<template v-if="node.time"> · {{ node.time }}</template>
        </div>
        <div v-if="node.comment" class="gtimeline-comment">{{ node.comment }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gtimeline {
  display: flex;
  flex-direction: column;
}

.gtimeline-item {
  display: flex;
  gap: 14px;
}

.gtimeline-rail {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 14px;
  flex-shrink: 0;
  padding-top: 4px;
}
.gtimeline-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: rgba(255, 253, 248, 0.55);
  border: 2px solid rgba(140, 120, 90, 0.4);
  flex-shrink: 0;
  z-index: 1;
}
.gtimeline-line {
  flex: 1;
  width: 2px;
  min-height: 28px;
  background: rgba(140, 120, 90, 0.28);
  margin-top: 3px;
}

.gtimeline-content {
  padding-bottom: 20px;
  min-width: 0;
}
.gtimeline-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.gtimeline-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.gtimeline-state {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 7px;
  font-weight: 500;
  background: rgba(140, 134, 120, 0.14);
  color: #6B6557;
}
.gtimeline-meta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-tertiary);
}
.gtimeline-comment {
  margin-top: 5px;
  font-size: 12.5px;
  color: var(--text-secondary);
  background: rgba(255, 253, 248, 0.36);
  border: 1px solid rgba(120, 100, 70, 0.14);
  border-radius: 9px;
  padding: 6px 10px;
}

/* —— 已完成：暖白 + 金芯 —— */
.state-done .gtimeline-dot {
  background: var(--warm-white);
  border-color: var(--sunrise-gold);
  box-shadow: 0 0 0 3px rgba(217, 164, 65, 0.16);
}
.state-done .gtimeline-line {
  background: linear-gradient(180deg, rgba(217, 164, 65, 0.5), rgba(140, 120, 90, 0.26));
}

/* —— 当前：金色光晕（克制） —— */
.state-current .gtimeline-dot {
  width: 14px;
  height: 14px;
  background: radial-gradient(circle at 35% 35%, #FFF3CE, var(--sunrise-gold) 60%, var(--sunrise-amber));
  border-color: rgba(255, 240, 200, 0.9);
 box-shadow:
    0 0 0 4px rgba(217, 164, 65, 0.22),
    0 0 14px 2px rgba(201, 135, 50, 0.42);
}
.state-current .gtimeline-name {
  color: var(--text-gold);
}
.state-current .gtimeline-state {
  background: rgba(217, 164, 65, 0.18);
  border: 1px solid rgba(201, 135, 50, 0.4);
  color: var(--text-gold);
}
.state-current .gtimeline-content {
  padding-left: 2px;
}

/* —— 未来：低透明度 —— */
.state-pending {
  opacity: 0.55;
}
.state-pending .gtimeline-name {
  font-weight: 500;
}

/* —— 跳过 —— */
.state-skipped {
  opacity: 0.5;
}
.state-skipped .gtimeline-dot {
  background: transparent;
  border-style: dashed;
}
</style>
