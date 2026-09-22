<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { sealUsages } from '@/mock/data'
import type { SealUsage } from '@/mock/types'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const list = ref<SealUsage[]>(sealUsages.map((s) => ({ ...s })))

const SEAL_NAMES = ['项目部公章', '财务专用章', '合同专用章', '党支部印章']

const sealStats = computed(() =>
  SEAL_NAMES.map((name) => ({
    name,
    count: list.value.filter((s) => s.sealName === name).length,
    sheets: list.value.filter((s) => s.sealName === name).reduce((sum, s) => sum + s.count, 0)
  }))
)

const statusTag: Record<SealUsage['status'], string> = {
  待审批: 'warning',
  已登记: 'success'
}

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  sealName: '项目部公章' as SealUsage['sealName'],
  count: 1,
  purpose: '',
  department: ''
})
const rules: FormRules = {
  sealName: [{ required: true, message: '请选择用章名称', trigger: 'change' }],
  count: [{ required: true, message: '请填写个数', trigger: 'blur' }],
  purpose: [{ required: true, message: '请填写用途', trigger: 'blur' }],
  department: [{ required: true, message: '请选择部门', trigger: 'change' }]
}

const DEPARTMENTS = ['综合管理部', '工程管理部', '合同管理部', '安全环保部', '质检部', '设备物资部', '财务管理部']

function openDialog() {
  form.sealName = '项目部公章'
  form.count = 1
  form.purpose = ''
  form.department = ''
  dialogVisible.value = true
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate((valid) => {
    if (!valid) return
    list.value.unshift({
      id: Date.now(),
      sealName: form.sealName,
      count: form.count,
      purpose: form.purpose,
      department: form.department,
      operator: user.name,
      date: today(),
      approvedBy: '',
      status: '待审批'
    })
    dialogVisible.value = false
    ElMessage.success('用章申请已提交，等待主管领导审批')
  })
}

function approveSeal(row: SealUsage) {
  row.status = '已登记'
  row.approvedBy = user.name
  ElMessage.success(`已登记 ${row.sealName} 用章记录（${row.count} 枚）`)
}
</script>

<template>
  <div>
    <el-row :gutter="12" class="stat-row">
      <el-col v-for="s in sealStats" :key="s.name" :xs="24" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">{{ s.name }}</span>
          <div class="stat-value">{{ s.count }} 次 / {{ s.sheets }} 枚</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openDialog">新申请用章</el-button>
      </div>
      <el-table :data="list" border stripe size="small">
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="sealName" label="用章名称" width="120" />
        <el-table-column prop="count" label="枚数" width="70" />
        <el-table-column prop="purpose" label="用途" min-width="180" show-overflow-tooltip />
        <el-table-column prop="department" label="用章部门" width="120" />
        <el-table-column prop="operator" label="经手人" width="90" />
        <el-table-column prop="approvedBy" label="主管领导" width="100" />
        <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }: { row: SealUsage }">
            <el-tag :type="statusTag[row.status]" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.status === '待审批'"
              type="primary"
              size="small"
              link
              @click="approveSeal(row)"
            >
              审批并登记
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新申请用章" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="用章名称" prop="sealName">
          <el-select v-model="form.sealName">
            <el-option v-for="s in SEAL_NAMES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="枚数" prop="count">
          <el-input-number v-model="form.count" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="用章部门" prop="department">
          <el-select v-model="form.department">
            <el-option v-for="d in DEPARTMENTS" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="用途" prop="purpose">
          <el-input v-model="form.purpose" type="textarea" :rows="2" placeholder="请说明用章用途" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-row {
  margin-bottom: 12px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-label {
  font-size: 13px;
  color: var(--text-tertiary);
}

.stat-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
</style>
