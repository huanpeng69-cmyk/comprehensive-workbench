<script setup lang="ts">
import type { TaskVo } from '@/api/workflow'

const props = withDefaults(
  defineProps<{
    task: TaskVo
    showActions?: boolean
  }>(),
  { showActions: true }
)

const emit = defineEmits<{
  (e: 'approve', task: TaskVo): void
  (e: 'transmit', task: TaskVo): void
  (e: 'view', task: TaskVo): void
}>()

const BIZ_NAMES: Record<string, string> = {
  leave: '请假申请',
  payroll: '工资发放',
  reception: '公务接待',
  seal: '公章使用',
  notice: '通知公告'
}

function formatTime(t: string | null) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <div class="task-card glass glass-level-3 glass-hover">
    <div class="task-top">
      <div class="task-title-line">
        <span class="task-biz">{{ BIZ_NAMES[props.task.bizType] || props.task.bizType }}</span>
        <span class="task-title" :title="props.task.title">{{ props.task.title }}</span>
      </div>
      <span class="task-status">待处理</span>
    </div>

    <div class="task-node">{{ props.task.nodeName }}</div>

    <div class="task-meta">
      <span>{{ props.task.initiatorName }} 发起</span>
      <span class="dot">·</span>
      <span>{{ formatTime(props.task.createdAt) }}</span>
    </div>

    <div v-if="showActions" class="task-actions">
      <button class="tlink" @click="emit('view', props.task)">查看详情</button>
      <div class="task-actions-right">
        <button class="tbtn tbtn-ghost" @click="emit('transmit', props.task)">转办</button>
        <button class="tbtn tbtn-primary" @click="emit('approve', props.task)">审批</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.task-card {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.task-title-line {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.task-biz {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 7px;
  background: rgba(217, 164, 65, 0.16);
  border: 1px solid rgba(201, 135, 50, 0.32);
  color: var(--text-gold);
}
.task-title {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-status {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 999px;
  background: rgba(217, 164, 65, 0.14);
  border: 1px solid rgba(201, 135, 50, 0.36);
  color: var(--text-gold);
}
.task-status::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--sunrise-amber);
  margin-right: 5px;
}

.task-node {
  font-size: 13px;
  color: var(--text-secondary);
}
.task-meta {
  font-size: 12px;
  color: var(--text-tertiary);
  display: flex;
  gap: 6px;
  align-items: center;
}
.task-meta .dot { opacity: 0.5; }

.task-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px solid rgba(120, 100, 70, 0.12);
}
.tlink {
  background: none;
  border: none;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 4px 2px;
  transition: color var(--dur-fast) var(--ease-out);
}
.tlink:hover { color: var(--text-gold); }
.task-actions-right {
  display: flex;
  gap: 8px;
}
.tbtn {
  height: 30px;
  padding: 0 14px;
  border-radius: 9px;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all var(--dur-fast) var(--ease-out);
}
.tbtn-ghost {
  background: rgba(255, 253, 248, 0.4);
  border-color: rgba(120, 100, 70, 0.24);
  color: var(--text-secondary);
}
.tbtn-ghost:hover {
  color: var(--text-gold);
  border-color: rgba(201, 135, 50, 0.42);
  background: rgba(255, 253, 248, 0.6);
}
.tbtn-primary {
  background: linear-gradient(160deg, var(--sunrise-gold), var(--sunrise-amber));
  color: var(--warm-white);
  border-color: rgba(169, 111, 38, 0.5);
  box-shadow: 0 4px 12px -6px rgba(201, 135, 50, 0.7);
}
.tbtn-primary:hover {
  background: linear-gradient(160deg, #E4B555, #D2943A);
  transform: translateY(-1px);
}

@media (max-width: 520px) {
  .task-actions { flex-direction: column; align-items: flex-start; gap: 8px; }
}
</style>
