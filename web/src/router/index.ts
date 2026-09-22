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
        { path: 'union/welfare', name: 'union-welfare', component: () => import('@/views/union/WelfareSign.vue'), meta: { title: '节日福利签收' } },
        { path: 'kb/regulations', name: 'kb-regulations', component: () => import('@/views/kb/Regulations.vue'), meta: { title: '制度文件库' } }
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
