<script setup lang="ts">
import SunriseBackground from '@/components/glass/SunriseBackground.vue'
</script>

<template>
  <SunriseBackground />
  <div class="app-shell">
    <router-view />
  </div>
</template>

<style scoped>
.app-shell {
  position: relative;
  z-index: 1;
  min-height: 100vh;
}
</style>
