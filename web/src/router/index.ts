import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/login/Login.vue') },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      redirect: '/home',
     children: [
        { path: 'workflow/todos', name: 'workflow-todos', component: () => import('@/views/workflow/TodoList.vue'), meta: { title: '待办中心' } },
        { path: 'home', name: 'home', component: () => import('@/views/home/Home.vue'), meta: { title: '工作台首页' } },
        { path: 'hr/staff', name: 'hr-staff', component: () => import('@/views/hr/StaffList.vue'), meta: { title: '人员花名册' } },
        { path: 'hr/payroll', name: 'hr-payroll', component: () => import('@/views/hr/PayrollApproval.vue'), meta: { title: '工资制作' } },
        { path: 'hr/leave', name: 'hr-leave', component: () => import('@/views/hr/LeaveList.vue'), meta: { title: '请假申请' } },
        { path: 'office/notice', name: 'office-notice', component: () => import('@/views/office/NoticeList.vue'), meta: { title: '通知公告' } },
        { path: 'office/seal', name: 'office-seal', component: () => import('@/views/office/SealUsage.vue'), meta: { title: '公章使用登记' } },
        { path: 'office/reception', name: 'office-reception', component: () => import('@/views/office/ReceptionList.vue'), meta: { title: '公务接待' } },
        { path: 'party/fees', name: 'party-fees', component: () => import('@/views/party/PartyFees.vue'), meta: { title: '党费季度明细' } },
        { path: 'party/members', name: 'party-members', component: () => import('@/views/party/PartyMembers.vue'), meta: { title: '党员名册' } },
        { path: 'union/condolence', name: 'union-condolence', component: () => import('@/views/union/CondolenceList.vue'), meta: { title: '慰问金审批' } },
        { path: 'kb/assistant', name: 'kb-assistant', component: () => import('@/views/kb/Assistant.vue'), meta: { title: '企业知识库' } },
        // 「我的记忆」已并入治理中心（ADR-M012）：旧路径重定向，避免历史书签 404。
        { path: 'ai/memory', name: 'ai-memory', redirect: '/system/audit' },
        { path: 'ai/writing', name: 'ai-writing', component: () => import('@/views/ai/WritingStudio.vue'), meta: { title: 'AI 智能体' } },
        { path: 'system/members', name: 'system-members', component: () => import('@/views/system/MemberManage.vue'), meta: { title: '成员管理' } },
        // 治理中心：审计日志仅 SYSTEM_ADMIN（后端 @PreAuthorize 把关），
        // 「我的记忆」Tab 所有人可见但只显示自己的（ADR-M012）。
        { path: 'system/audit', name: 'system-audit', component: () => import('@/views/system/AuditCenter.vue'), meta: { title: '治理中心' } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const user = useUserStore()
  if (to.path !== '/login' && !user.token) {
    return '/login'
  }
})

export default router
