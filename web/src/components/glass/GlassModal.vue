<script setup lang="ts">
/**
 * 大型液态玻璃弹窗：页面变暗、背景山景保留、Level 4 玻璃。
 * v-model 控制显隐，ESC / 点击遮罩可关闭。
 */
import { onBeforeUnmount, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    subtitle?: string
    width?: string
    closeOnOverlay?: boolean
  }>(),
  { width: '640px', closeOnOverlay: true }
)
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

function close() {
  emit('update:modelValue', false)
}

function onOverlay() {
  if (props.closeOnOverlay) close()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.modelValue) close()
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', onKeydown)
    } else {
      document.body.style.overflow = ''
      window.removeEventListener('keydown', onKeydown)
    }
  }
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="glass-modal">
      <div v-if="modelValue" class="gmodal-overlay" @click.self="onOverlay">
        <div
          class="glass glass-level-4 gmodal"
          :style="{ width, borderRadius: 'var(--glass-radius-lg)' }"
          role="dialog"
          aria-modal="true"
        >
          <header v-if="title || $slots.header" class="gmodal-header">
            <slot name="header">
              <div>
                <h2 class="gmodal-title">{{ title }}</h2>
                <p v-if="subtitle" class="gmodal-subtitle">{{ subtitle }}</p>
              </div>
            </slot>
            <button class="gmodal-close" aria-label="关闭" @click="close">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M3 3L13 13M13 3L3 13" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
            </button>
          </header>
          <div class="gmodal-body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="gmodal-footer">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.gmodal-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(46, 38, 24, 0.30);
  backdrop-filter: blur(14px) saturate(1.35);
  -webkit-backdrop-filter: blur(14px) saturate(1.35);
}

.gmodal {
  position: relative;
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.gmodal-header {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 24px 26px 14px;
  border-bottom: 1px solid rgba(120, 100, 70, 0.14);
}
.gmodal-title {
  margin: 0;
  font-size: 19px;
  font-weight: 650;
  color: var(--text-primary);
  letter-spacing: 0;
}
.gmodal-subtitle {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-tertiary);
}
.gmodal-close {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(120, 100, 70, 0.2);
  border-radius: 9px;
  background: rgba(255, 253, 248, 0.4);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.gmodal-close:hover {
  background: rgba(180, 100, 90, 0.16);
  color: #9E4F47;
  border-color: rgba(180, 100, 90, 0.4);
}

.gmodal-body {
  position: relative;
  z-index: 2;
  padding: 18px 26px;
  overflow-y: auto;
  flex: 1;
}

.gmodal-footer {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 26px 20px;
  border-top: 1px solid rgba(120, 100, 70, 0.14);
}

/* 出场动画：淡入 + 轻微上浮 + blur 收敛 */
.glass-modal-enter-active,
.glass-modal-leave-active {
  transition: opacity var(--dur-slow) var(--ease-out);
}
.glass-modal-enter-active .gmodal {
  transition: transform var(--dur-slow) var(--ease-out), opacity var(--dur-slow) var(--ease-out);
}
.glass-modal-leave-active .gmodal {
  transition: transform var(--dur-base) var(--ease-out), opacity var(--dur-base) var(--ease-out);
}
.glass-modal-enter-from,
.glass-modal-leave-to {
  opacity: 0;
}
.glass-modal-enter-from .gmodal {
  transform: translateY(14px) scale(0.985);
  opacity: 0;
}
.glass-modal-leave-to .gmodal {
  transform: translateY(8px) scale(0.99);
  opacity: 0;
}

@media (max-width: 768px) {
  .gmodal-overlay { padding: 12px; }
  .gmodal { width: 100% !important; }
  .gmodal-header,
  .gmodal-body,
  .gmodal-footer { padding-left: 18px; padding-right: 18px; }
}
</style>
