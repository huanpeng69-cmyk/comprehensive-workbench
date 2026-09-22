<script setup lang="ts">
/**
 * 玻璃按钮
 *  primary  金色日出渐变（同意）
 *  danger   低饱和半透明红（驳回）
 *  ghost    透明玻璃深字（转办 / 次要）
 */
withDefaults(
  defineProps<{
    variant?: 'primary' | 'danger' | 'ghost'
    size?: 'sm' | 'md' | 'lg'
    loading?: boolean
    disabled?: boolean
    block?: boolean
  }>(),
  { variant: 'ghost', size: 'md', loading: false, disabled: false, block: false }
)
</script>

<template>
  <button
    class="gbtn"
    :class="[`gbtn-${variant}`, `gbtn-${size}`, { 'is-loading': loading, 'is-disabled': disabled, 'gbtn-block': block }]"
    :disabled="disabled || loading"
  >
    <span v-if="loading" class="gbtn-spinner" aria-hidden="true"></span>
    <slot />
  </button>
</template>

<style scoped>
.gbtn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-family: inherit;
  font-weight: 600;
  letter-spacing: 0;
  border-radius: 12px;
  cursor: pointer;
  overflow: hidden;
  transition: transform var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-fast) var(--ease-out),
    background var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}
.gbtn::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.45) 0%, rgba(255, 255, 255, 0.08) 42%, transparent 68%);
}
.gbtn > * { position: relative; z-index: 1; }

.gbtn-sm { height: 32px; padding: 0 14px; font-size: 12.5px; }
.gbtn-md { height: 38px; padding: 0 18px; font-size: 13.5px; }
.gbtn-lg { height: 46px; padding: 0 24px; font-size: 15px; }
.gbtn-block { width: 100%; }

/* —— 金色主操作 —— */
.gbtn-primary {
  background: linear-gradient(160deg, var(--sunrise-gold) 0%, var(--sunrise-amber) 100%);
  color: var(--warm-white);
  border: 1px solid rgba(169, 111, 38, 0.55);
  box-shadow: 0 6px 18px -8px rgba(201, 135, 50, 0.75), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}
.gbtn-primary:hover:not(.is-disabled) {
  background: linear-gradient(160deg, #E4B555 0%, #D2943A 100%);
  transform: translateY(-1px);
  box-shadow: 0 10px 24px -10px rgba(201, 135, 50, 0.85), inset 0 1px 0 rgba(255, 255, 255, 0.48);
}
.gbtn-primary:active:not(.is-disabled) {
  transform: translateY(0);
  background: linear-gradient(160deg, var(--sunrise-amber) 0%, var(--sunrise-amber-deep) 100%);
  box-shadow: 0 3px 10px -4px rgba(201, 135, 50, 0.7), inset 0 2px 4px rgba(120, 80, 20, 0.24);
}

/* —— 低饱和红 —— */
.gbtn-danger {
  background: rgba(180, 100, 90, 0.14);
  color: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(180, 100, 90, 0.4);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.gbtn-danger:hover:not(.is-disabled) {
  background: rgba(180, 100, 90, 0.24);
  border-color: rgba(180, 100, 90, 0.6);
  color: #fff;
  transform: translateY(-1px);
}
.gbtn-danger:active:not(.is-disabled) {
  transform: translateY(0);
  background: rgba(180, 100, 90, 0.3);
}

/* —— 透明玻璃 —— */
.gbtn-ghost {
  background: rgba(255, 253, 248, 0.36);
  color: var(--text-primary);
  border: 1px solid rgba(120, 100, 70, 0.24);
  backdrop-filter: blur(10px) saturate(1.4);
  -webkit-backdrop-filter: blur(10px) saturate(1.4);
}
.gbtn-ghost:hover:not(.is-disabled) {
  background: rgba(255, 253, 248, 0.58);
  border-color: rgba(201, 135, 50, 0.42);
  color: var(--text-gold);
  transform: translateY(-1px);
}
.gbtn-ghost:active:not(.is-disabled) {
  transform: translateY(0);
  background: rgba(255, 253, 248, 0.44);
}

/* —— 状态 —— */
.gbtn.is-disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}
.gbtn.is-loading { cursor: progress; }

.gbtn-spinner {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  animation: gbtn-spin 0.7s linear infinite;
}
.gbtn-ghost .gbtn-spinner,
.gbtn-danger .gbtn-spinner {
  border-color: rgba(120, 100, 70, 0.3);
  border-top-color: var(--sunrise-amber);
}
@keyframes gbtn-spin {
  to { transform: rotate(360deg); }
}
</style>
