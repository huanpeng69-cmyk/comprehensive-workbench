<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { listOrgUnits, type OrgUnit } from '@/api/org'
import { ArrowDown, Fold, Expand, Bell, Sunrise, MagicStick, SwitchButton } from '@element-plus/icons-vue'

interface MenuItem {
  title: string
  index: string
  icon?: string
  children?: MenuItem[]
}

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const projectOptions = ref<OrgUnit[]>([])

async function loadProjects() {
  try {
    projectOptions.value = await listOrgUnits('PROJECT')
  } catch {
    // 组织机构接口异常时保持空下拉，不阻塞整体布局
    projectOptions.value = []
  }
}
onMounted(loadProjects)

const isAdmin = computed(() => user.roles.includes('SYSTEM_ADMIN'))

const menus = computed<MenuItem[]>(() => {
  const items: MenuItem[] = [
    { title: '工作台首页', index: '/home', icon: 'HomeFilled' },
    { title: '待办中心', index: '/workflow/todos', icon: 'Bell' },
    {
      title: '人力管理', index: 'hr', icon: 'User',
      children: [
        { title: '人员花名册', index: '/hr/staff' },
        { title: '工资制作', index: '/hr/payroll' },
        { title: '请假申请', index: '/hr/leave' }
      ]
    },
    {
      title: '办公室管理', index: 'office', icon: 'OfficeBuilding',
      children: [
        { title: '通知公告', index: '/office/notice' },
        { title: '公章使用登记', index: '/office/seal' },
        { title: '公务接待', index: '/office/reception' }
      ]
    },
    {
      title: '党建管理', index: 'party', icon: 'Flag',
      children: [
        { title: '党员名册', index: '/party/members' },
        { title: '党费季度明细', index: '/party/fees' }
      ]
    },
    {
      title: '工会管理', index: 'union', icon: 'Briefcase',
      children: [
        { title: '慰问金审批', index: '/union/condolence' },
      ]
    },
    { title: '企业知识库', index: '/kb/assistant', icon: 'Collection' },
    { title: 'AI 智能体', index: '/ai/writing', icon: 'MagicStick' }
    // 「我的记忆」已并入「系统管理 → 治理中心」（ADR-M012），不再单列入口。
  ]
  if (isAdmin.value) {
    items.push({
      title: '系统管理', index: 'system', icon: 'Setting',
      children: [
        { title: '成员管理', index: '/system/members' },
        { title: '治理中心', index: '/system/audit' }
      ]
    })
  }
  return items
})

const pageTitle = computed(() => (route.meta.title as string) || '')

function isActive(index: string) {
  return route.path === index
}

const collapsed = ref(false)
const mobileOpen = ref(false)
function toggleCollapse() {
  collapsed.value = !collapsed.value
}

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const today = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
})

function onLogout() {
  user.logout()
  router.push('/login')
}
</script>

<template>
  <div class="wb-layout" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <!-- 浮动玻璃侧边栏 -->
    <aside class="wb-sidebar glass glass-level-1">
      <div class="sidebar-inner">
        <div class="brand">
          <div class="brand-mark">
            <el-icon :size="18"><Sunrise /></el-icon>
          </div>
          <div v-show="!collapsed" class="brand-text">
            <span class="brand-title">工作台</span>
            <span class="brand-sub">Enterprise AI Workspace</span>
          </div>
        </div>

        <nav class="nav">
          <template v-for="menu in menus" :key="menu.index">
            <div v-if="menu.children" v-show="!collapsed" class="nav-group-label">{{ menu.title }}</div>
            <template v-if="menu.children">
              <button
                v-for="child in menu.children"
                :key="child.index"
                class="nav-item"
                :class="{ active: isActive(child.index) }"
                @click="router.push(child.index); mobileOpen = false"
              >
                <el-icon :size="17"><component :is="menu.icon" /></el-icon>
                <span v-show="!collapsed" class="nav-label">{{ child.title }}</span>
              </button>
            </template>
            <button
              v-else
              class="nav-item"
              :class="{ active: isActive(menu.index) }"
              @click="router.push(menu.index); mobileOpen = false"
            >
              <el-icon :size="17"><component :is="menu.icon" /></el-icon>
              <span v-show="!collapsed" class="nav-label">{{ menu.title }}</span>
            </button>
          </template>
        </nav>

        <div class="sidebar-foot">
          <div v-show="!collapsed" class="ai-entry" @click="router.push('/ai/writing'); mobileOpen = false" style="cursor: pointer">
            <el-icon :size="15"><MagicStick /></el-icon>
            <span>AI 智能体</span>
          </div>
          <button class="user-card" @click="onLogout">
            <div class="user-avatar">{{ user.name.charAt(0) }}</div>
            <div v-show="!collapsed" class="user-meta">
              <div class="user-name">{{ user.name }}</div>
              <div class="user-role">{{ user.role }}</div>
            </div>
            <el-icon v-show="!collapsed" class="user-quit"><SwitchButton /></el-icon>
          </button>
        </div>
      </div>
    </aside>

    <div class="mobile-mask" @click="mobileOpen = false"></div>

    <!-- 主区域 -->
    <div class="wb-main">
      <header class="wb-header glass glass-level-2">
        <button class="hamburger" @click="mobileOpen = true">
          <el-icon :size="19"><Expand /></el-icon>
        </button>
        <button class="collapse-btn" @click="toggleCollapse">
          <el-icon :size="18"><Fold /></el-icon>
        </button>
        <div class="header-greeting">
          <span class="greeting-main">{{ greeting }}，{{ user.name }}</span>
          <span class="greeting-date">今天是 {{ today }}，这里是你的工作空间。</span>
        </div>
        <div class="header-actions">
          <el-select v-model="user.project" size="small" style="width: 140px" placeholder="选择项目部">
            <el-option v-for="u in projectOptions" :key="u.id" :label="u.name" :value="u.name" />
          </el-select>
          <el-badge is-dot class="bell-badge">
            <el-icon :size="19" class="bell-icon"><Bell /></el-icon>
          </el-badge>
          <el-dropdown @command="onLogout">
            <div class="header-user">
              <el-avatar :size="30" class="user-avatar-sm">{{ user.name.charAt(0) }}</el-avatar>
              <el-icon class="dropdown-caret"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="wb-content">
        <div class="page-head">
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.wb-layout {
  display: flex;
  min-height: 100vh;
  padding: 20px;
  gap: 20px;
}

.wb-sidebar {
  position: sticky;
  top: 20px;
  align-self: flex-start;
  flex: 0 0 232px;
  height: calc(100vh - 40px);
  border-radius: var(--glass-radius-lg);
  transition: flex-basis var(--dur-slow) var(--ease-out);
  z-index: 60;
}
.wb-layout.collapsed .wb-sidebar {
  flex-basis: 72px;
}

.sidebar-inner {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 18px 14px;
  box-sizing: border-box;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 4px 6px 18px;
}
.brand-mark {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  color: var(--sunrise-amber);
  box-shadow: 0 6px 16px -6px rgba(60, 45, 22, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.brand-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.5px;
}
.brand-sub {
  font-size: 10.5px;
  color: var(--text-tertiary);
  letter-spacing: 0.4px;
}

.nav {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-height: 0;
}
.nav-group-label {
  margin: 14px 8px 5px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: var(--text-gold);
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 9px 11px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  /* 折痕修复（2026-11-06）：悬停位移会改变图层树，触发浏览器重建玻璃侧边栏的
     backdrop 底片，本元素的描边在重绘帧里被合成到卡片自身填充上，表现为 160ms
     内一闪的边缘亮线（实测亮度 185 → 203）。提前声明位移并提升为独立合成层后，
     悬停不再引起图层树变化；对布局、位移距离与过渡时长均无影响。 */
  will-change: transform;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out), transform var(--dur-fast) var(--ease-out);
}
.nav-item:hover {
  background: rgba(255, 253, 248, 0.44);
  color: var(--text-primary);
  transform: translateX(2px);
}
.nav-item.active {
  background: linear-gradient(135deg, rgba(233, 190, 110, 0.26), rgba(201, 135, 50, 0.10));
  border-color: rgba(217, 164, 65, 0.5);
  color: var(--text-gold);
  font-weight: 600;
  box-shadow: 0 0 0 1px rgba(217, 164, 65, 0.16), 0 5px 16px -8px rgba(201, 135, 50, 0.5);
}

.sidebar-foot {
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ai-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 11px;
  border-radius: 12px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-gold);
  background: rgba(217, 164, 65, 0.10);
  border: 1px dashed rgba(201, 135, 50, 0.35);
}
.ai-soon {
  margin-left: auto;
  font-size: 10px;
  font-weight: 500;
  padding: 1px 6px;
  border-radius: 6px;
  background: rgba(217, 164, 65, 0.18);
}
.user-card {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px;
  border: 1px solid rgba(120, 100, 70, 0.16);
  border-radius: 14px;
  background: rgba(255, 253, 248, 0.34);
  cursor: pointer;
  font-family: inherit;
  transition: background var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out);
}
.user-card:hover {
  background: rgba(255, 253, 248, 0.56);
  border-color: rgba(201, 135, 50, 0.4);
}
.user-avatar {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(150deg, var(--sunrise-gold), var(--sunrise-amber));
  box-shadow: 0 4px 12px -4px rgba(201, 135, 50, 0.6);
}
.user-meta { min-width: 0; flex: 1; }
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
}
.user-role {
  font-size: 11px;
  color: var(--text-tertiary);
  white-space: nowrap;
}
.user-quit { color: var(--text-tertiary); }

.wb-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.wb-header {
  position: sticky;
  top: 20px;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 20px;
  border-radius: var(--glass-radius);
}
.hamburger,
.collapse-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(120, 100, 70, 0.2);
  border-radius: 10px;
  background: rgba(255, 253, 248, 0.4);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.hamburger:hover,
.collapse-btn:hover {
  color: var(--text-gold);
  border-color: rgba(201, 135, 50, 0.42);
  background: rgba(255, 253, 248, 0.6);
}
.hamburger { display: none; }

.header-greeting {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.greeting-main {
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
}
.greeting-date {
  font-size: 11.5px;
  color: var(--text-tertiary);
}

.header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.bell-icon {
  color: var(--text-secondary);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}
.bell-icon:hover { color: var(--text-gold); }
:deep(.bell-badge .el-badge__content) {
  background: linear-gradient(150deg, var(--sunrise-gold), var(--sunrise-amber));
  border: none;
  right: 2px;
  top: 2px;
}
.header-user {
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
}
.user-avatar-sm {
  background: linear-gradient(150deg, var(--sunrise-gold), var(--sunrise-amber)) !important;
  color: #fff !important;
  font-size: 13px;
  font-weight: 600;
}
.dropdown-caret { color: var(--text-tertiary); }

.wb-content {
  flex: 1;
  min-width: 0;
}
.page-head {
  margin-bottom: 14px;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.2px;
}

.mobile-mask {
  display: none;
  position: fixed;
  inset: 0;
  z-index: 55;
  background: rgba(46, 38, 24, 0.26);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

@media (max-width: 900px) {
  .wb-layout { padding: 12px; gap: 0; }
  .hamburger { display: flex; }
  .collapse-btn { display: none; }
  .wb-sidebar {
    position: fixed;
    top: 12px;
    left: 12px;
    height: calc(100vh - 24px);
    width: 248px;
    flex: none;
    transform: translateX(calc(-100% - 24px));
    transition: transform var(--dur-slow) var(--ease-out);
  }
  .wb-layout.mobile-open .wb-sidebar { transform: translateX(0); }
  .wb-layout.mobile-open .mobile-mask { display: block; }
  .wb-main { gap: 12px; }
  .wb-header { padding: 10px 14px; gap: 12px; top: 12px; }
  .greeting-date { display: none; }
}

@media (max-width: 520px) {
  .wb-header { flex-wrap: wrap; }
  .header-actions { margin-left: 0; width: 100%; justify-content: flex-end; }
  .greeting-main { font-size: 14px; }
}
</style>
