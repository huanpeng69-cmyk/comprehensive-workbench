<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import GlassButton from '@/components/glass/GlassButton.vue'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()
const user = useUserStore()

const formRef = ref<FormInstance>()
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await user.login(form.username, form.password)
      ElMessage.success(`欢迎回来，${user.name}`)
      router.push('/home')
    } catch (e) {
      ElMessage.error((e as Error).message || '登录失败')
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="login-stage">
    <div class="login-side">
      <div class="login-hero">
        <div class="hero-mark">✦</div>
        <h1 class="hero-title">日照金山<br />企业工作空间</h1>
        <p class="hero-desc">人力 · 办公室 · 党建 · 工会，一站式智能协作工作台。</p>
      </div>
    </div>

    <div class="login-panel">
      <div class="glass glass-level-4 login-card">
        <div class="login-head">
          <span class="login-title">欢迎回来</span>
          <span class="login-sub">登录你的工作台</span>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" size="large" @keyup.enter="onSubmit" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入密码"
              size="large"
              @keyup.enter="onSubmit"
            />
          </el-form-item>
          <GlassButton variant="primary" size="lg" block :loading="loading" @click="onSubmit">
            登录工作台
          </GlassButton>
        </el-form>

        <div class="login-tip">首次登录请使用管理员分配的账号，登录后请尽快修改密码</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-stage {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  align-items: stretch;
}

/* 左侧：山景氛围区（背景全局已有，这里叠加叙事文案） */
.login-side {
  position: relative;
  display: flex;
  align-items: flex-end;
  padding: 64px;
}
.login-hero {
  position: relative;
  z-index: 2;
  max-width: 420px;
}
.hero-mark {
  font-size: 30px;
  color: var(--sunrise-amber);
  text-shadow: 0 0 24px rgba(201, 135, 50, 0.5);
  margin-bottom: 18px;
}
.hero-title {
  margin: 0;
  font-size: 42px;
  line-height: 1.25;
  font-weight: 700;
  letter-spacing: 1px;
  color: rgba(255, 255, 255, 0.98);
  text-shadow: 0 2px 14px rgba(38, 30, 18, 0.45);
}
.hero-desc {
  margin: 16px 0 0;
  font-size: 14.5px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.78);
}

/* 右侧：玻璃登录卡 */
.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}
.login-card {
  width: 100%;
  max-width: 400px;
  padding: 34px 32px 28px;
}
.login-head {
  text-align: center;
  margin-bottom: 24px;
}
.login-title {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 5px;
}
.login-sub {
  font-size: 13px;
  color: var(--text-tertiary);
}
.login-tip {
  margin-top: 18px;
  font-size: 11.5px;
  color: var(--text-tertiary);
  text-align: center;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .login-stage {
    grid-template-columns: 1fr;
  }
  .login-side {
    padding: 40px 32px 8px;
    align-items: flex-start;
  }
  .hero-title { font-size: 30px; }
  .login-panel { padding: 12px 24px 40px; }
}
@media (max-width: 520px) {
  .hero-title { font-size: 24px; }
  .hero-desc { font-size: 13px; }
  .login-card { padding: 26px 20px 22px; }
}
</style>
