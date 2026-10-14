<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import {
  listUsers, createUser, updateUser, deleteUser, resetPassword, listRoles,
  type UserItem, type RoleOption
} from '@/api/users'
import { listOrgUnits, type OrgUnit } from '@/api/org'

const users = ref<UserItem[]>([])
const roles = ref<RoleOption[]>([])
const projects = ref<OrgUnit[]>([])

const filters = reactive({ keyword: '', projectId: undefined as number | undefined, roleCode: '' })

const loading = ref(false)

async function loadUsers() {
  loading.value = true
  try {
    users.value = await listUsers({
      keyword: filters.keyword || undefined,
      projectId: filters.projectId,
      roleCode: filters.roleCode || undefined
    })
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '加载成员失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    ;[roles.value, projects.value] = await Promise.all([listRoles(), listOrgUnits('PROJECT')])
  } catch {
    roles.value = []
    projects.value = []
  }
  await loadUsers()
})

const stats = computed(() => ({
  total: users.value.length,
  enabled: users.value.filter(u => u.enabled).length
}))

function roleTagType(code: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  if (code === 'SYSTEM_ADMIN') return 'danger'
  if (code === 'HR_ADMIN' || code === 'FINANCE') return 'warning'
  if (code === 'PROJECT_MANAGER') return 'success'
  return 'info'
}

/* ---------- 新增 / 编辑 ---------- */
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const formRef = ref<FormInstance>()
const form = reactive({
  id: 0,
  username: '',
  name: '',
  phone: '',
  employmentType: '正式职工',
  projectId: undefined as number | undefined,
  roleCodes: [] as string[],
  enabled: true,
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入登录用户名', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

function blankForm() {
  Object.assign(form, {
    id: 0, username: '', name: '', phone: '', employmentType: '正式职工',
    projectId: undefined, roleCodes: [], enabled: true, password: ''
  })
}

function openAdd() {
  blankForm()
  dialogMode.value = 'add'
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}
function openEdit(row: UserItem) {
  Object.assign(form, {
    id: row.id, username: row.username, name: row.name, phone: row.phone || '',
    employmentType: row.employmentType || '正式职工', projectId: row.projectId ?? undefined,
    roleCodes: [...row.roleCodes], enabled: row.enabled, password: ''
  })
  dialogMode.value = 'edit'
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}
function onSave() {
  formRef.value?.validate((valid: boolean) => {
    if (!valid) return
    const payload = {
      username: form.username,
      name: form.name,
      phone: form.phone || undefined,
      employmentType: form.employmentType || undefined,
      projectId: form.projectId,
      roleCodes: form.roleCodes,
      enabled: form.enabled,
      password: form.password || undefined
    }
    const req = dialogMode.value === 'add'
      ? createUser(payload)
      : updateUser(form.id, payload)
    req.then(() => {
      dialogVisible.value = false
      ElMessage.success(dialogMode.value === 'add' ? '已新增成员' : '已保存修改')
      loadUsers()
    }).catch((err: unknown) => {
      ElMessage.error(err instanceof Error ? err.message : '保存失败')
    })
  })
}

/* ---------- 删除 ---------- */
function onDelete(row: UserItem) {
  ElMessageBox.confirm(`确认删除成员「${row.name}（${row.username}）」？此操作不可恢复。`, '删除确认', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
  }).then(() => deleteUser(row.id))
    .then(() => { ElMessage.success('已删除'); loadUsers() })
    .catch((err: unknown) => { if (err instanceof Error) ElMessage.error(err.message) })
}

/* ---------- 重置密码 ---------- */
const pwdVisible = ref(false)
const pwdForm = reactive({ id: 0, name: '', password: '' })
const pwdRef = ref<FormInstance>()
const pwdRules = { password: [{ required: true, message: '请输入新密码', trigger: 'blur' }] }

function openReset(row: UserItem) {
  Object.assign(pwdForm, { id: row.id, name: row.name, password: '' })
  pwdVisible.value = true
  pwdRef.value?.clearValidate?.()
}
function onResetPwd() {
  pwdRef.value?.validate((valid: boolean) => {
    if (!valid) return
    resetPassword(pwdForm.id, pwdForm.password)
      .then(() => { pwdVisible.value = false; ElMessage.success('密码已重置'); })
      .catch((err: unknown) => { ElMessage.error(err instanceof Error ? err.message : '重置失败') })
  })
}
</script>

<template>
  <div>
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">成员总数</span>
          <div class="stat-value">{{ stats.total }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">启用中</span>
          <div class="stat-value">{{ stats.enabled }} 人</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openAdd">新增成员</el-button>
        <div class="toolbar-spacer" />
        <el-input v-model="filters.keyword" placeholder="搜索用户名 / 姓名" clearable style="width: 200px" @keyup.enter="loadUsers" />
        <el-select v-model="filters.projectId" placeholder="全部项目部" clearable style="width: 150px" @change="loadUsers">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select v-model="filters.roleCode" placeholder="全部角色" clearable style="width: 150px" @change="loadUsers">
          <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
        </el-select>
        <el-button @click="loadUsers">查询</el-button>
      </div>

      <el-table :data="users" border stripe size="small" v-loading="loading" style="width: 100%">
        <el-table-column prop="username" label="登录名" width="140" fixed="left" />
        <el-table-column prop="name" label="姓名" width="120" fixed="left" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="employmentType" label="用工形式" width="110" />
        <el-table-column prop="projectName" label="所属项目部" width="140" />
        <el-table-column label="角色" min-width="220">
          <template #default="{ row }">
            <el-tag v-for="c in row.roleCodes" :key="c" :type="roleTagType(c)" size="small" class="role-tag">
              {{ (roles.find(r => r.code === c)?.name) || c }}
            </el-tag>
            <span v-if="!row.roleCodes.length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" size="small" @click="openReset(row)">重置密码</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? '新增成员' : '编辑成员'"
      width="640px"
      destroy-on-close
    >
      <el-form :model="form" :rules="rules" ref="formRef" label-width="96px">
        <el-form-item label="登录用户名" prop="username">
          <el-input v-model="form.username" :disabled="dialogMode === 'edit'" placeholder="用于登录系统的用户名" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="选填" />
        </el-form-item>
        <el-form-item label="用工形式">
          <el-input v-model="form.employmentType" placeholder="如：正式职工 / 临时工" />
        </el-form-item>
        <el-form-item label="所属项目部">
          <el-select v-model="form.projectId" placeholder="选择项目部" clearable style="width: 100%">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleCodes" multiple placeholder="可多选" style="width: 100%">
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item :label="dialogMode === 'add' ? '初始密码' : '重置密码'">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="dialogMode === 'add' ? '留空则使用默认密码 User@2026' : '留空则不修改密码'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="420px" destroy-on-close>
      <p class="pwd-hint">为「{{ pwdForm.name }}」设置新密码：</p>
      <el-form :model="pwdForm" :rules="pwdRules" ref="pwdRef" label-width="80px">
        <el-form-item label="新密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="请输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" @click="onResetPwd">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-row { margin-bottom: 12px; }
.stat-card { display: flex; flex-direction: column; gap: 4px; }
.stat-label { font-size: 13px; color: var(--text-tertiary); }
.stat-value { font-size: 22px; font-weight: 700; color: var(--text-primary); }

.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }
.toolbar-spacer { flex: 1; }

.role-tag { margin: 2px 4px 2px 0; }
.muted { color: var(--text-tertiary); }
.pwd-hint { margin: 0 0 12px; color: var(--text-secondary); font-size: 13px; }
</style>
