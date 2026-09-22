<script setup lang="ts">
/**
 * 日照金山环境层：实景照片打底，叠暖白空气层与暗角。
 * 山景是环境光，不是网页主体，罩层刻意压低存在感、保证文字可读。
 */
</script>

<template>
  <div class="sunrise-env" aria-hidden="true">
    <img class="sunrise-photo" src="@/assets/sunrise-mountain.png" alt="" draggable="false" />

    <!-- 空气层：整屏淡暖白罩，压住山景细节、保证文字可读 -->
    <div class="env-wash"></div>
    <!-- 边缘暗角，聚拢视线 -->
    <div class="env-vignette"></div>
  </div>
</template>

<style scoped>
.sunrise-env {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background: #e6d9c0;
}

.sunrise-photo {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center 62%;
  transform: scale(1.04);
  filter: saturate(0.92) brightness(1.02);
}

.env-wash {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(255, 253, 248, 0.14) 0%,
    rgba(255, 253, 248, 0.24) 52%,
    rgba(255, 250, 238, 0.36) 100%
  );
}

.env-vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 50% 42%, transparent 54%, rgba(52, 40, 24, 0.16) 100%);
}

@media (max-width: 768px) {
  .env-wash {
    background: linear-gradient(
      180deg,
      rgba(255, 253, 248, 0.2) 0%,
      rgba(255, 253, 248, 0.3) 52%,
      rgba(255, 250, 238, 0.42) 100%
    );
  }
}
</style>
