<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { LeaveRecord } from '@/mock/types'
import { useUserStore } from '@/stores/user'
import { useLeaveStore } from '@/stores/leave'

const user = useUserStore()
const leaveStore = useLeaveStore()

const LEAVE_TYPES: LeaveRecord['type'][] = ['事假', '病假', '年假', '调休', '婚假', '产假', '护理假', '其他']

/** 计入工日扣减的类型（与工资工日勾稽一致：事假/病假/其他） */
const DEDUCTIBLE: LeaveRecord['type'][] = ['事假', '病假', '其他']

const statusTag: Record<LeaveRecord['status'], string> = {
  待审批: 'warning',
  已批准: 'success',
  已驳回: 'danger'
}

function daysBetween(start: string, end: string) {
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (isNaN(s) || isNaN(e)) return 0
  return Math.round((e - s) / 86400000) + 1
}

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const editingRow = ref<LeaveRecord | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({
  type: '事假' as LeaveRecord['type'],
  range: [] as string[],
  reason: ''
})
const rules: FormRules = {
  type: [{ required: true, message: '请选择请假类型', trigger: 'change' }],
  range: [{ required: true, message: '请选择请假日期', trigger: 'change' }],
  reason: [{ required: true, message: '请填写请假事由', trigger: 'blur' }]
}

const stats = computed(() => ({
  pending: leaveStore.list.filter((r) => r.status === '待审批').length,
  approved: leaveStore.list.filter((r) => r.status === '已批准').length,
  deductibleDays: leaveStore.list
    .filter((r) => r.status === '已批准' && DEDUCTIBLE.includes(r.type))
    .reduce((sum, r) => sum + r.days, 0)
}))

function openDialog() {
  dialogMode.value = 'create'
  editingId.value = null
  editingRow.value = null
  form.type = '事假'
  form.range = []
  form.reason = ''
  dialogVisible.value = true
}

/** 改：编辑待审批的请假（与后端「仅待审批可修改」一致）。 */
function openEdit(row: LeaveRecord) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  editingRow.value = row
  form.type = row.type
  form.range = [row.start, row.end]
  form.reason = row.reason
  dialogVisible.value = true
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate((valid) => {
    if (!valid) return
    const [start, end] = form.range
    const days = daysBetween(start, end)
    if (dialogMode.value === 'edit' && editingId.value != null && editingRow.value) {
      const rec: LeaveRecord = {
        ...editingRow.value,
        type: form.type,
        start,
        end,
        days,
        reason: form.reason
      }
      leaveStore
        .update(rec)
        .then(() => {
          dialogVisible.value = false
          ElMessage.success('请假申请已更新')
        })
        .catch((err: any) => ElMessage.error(err?.message || '更新失败'))
      return
    }
    leaveStore
      .add({
        type: form.type,
        start,
        end,
        days,
        reason: form.reason
      })
      .then(() => {
        dialogVisible.value = false
        ElMessage.success('请假申请已提交，等待项目部负责人审批')
      })
      .catch((err: any) => {
        ElMessage.error(err?.message || '提交失败')
      })
  })
}

function approve(row: LeaveRecord) {
  leaveStore
    .approve(row.id)
    .then(() => ElMessage.success(`已批准 ${row.applicant} 的${row.type}申请`))
    .catch((err: any) => ElMessage.error(err?.message || '审批失败'))
}

function reject(row: LeaveRecord) {
  leaveStore.reject(row.id).catch((err: any) => ElMessage.error(err?.message || '操作失败'))
}

function onRemove(row: LeaveRecord) {
  leaveStore.remove(row.id).catch((err: any) => ElMessage.error(err?.message || '删除失败'))
}

onMounted(() => {
  leaveStore.load()
})
</script>

<template>
  <div>
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="24" :sm="8">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">待审批</span>
          <div class="stat-value">{{ stats.pending }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">已批准</span>
          <div class="stat-value">{{ stats.approved }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">已批准可扣工日天数（事/病/其他）</span>
          <div class="stat-value">{{ stats.deductibleDays }} 天</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openDialog">新建请假申请</el-button>
        <span class="imported-at">已批准的「事假/病假/其他」会实时扣减工资工日（与工资制作勾稽）</span>
      </div>
      <el-table :data="leaveStore.list" border stripe size="small">
        <el-table-column prop="applicant" label="申请人" width="90" />
        <el-table-column prop="project" label="项目部" width="140" />
        <el-table-column prop="type" label="类型" width="80" />
        <el-table-column label="请假时间" width="200">
          <template #default="{ row }">
            {{ row.start }} 至 {{ row.end }}
          </template>
        </el-table-column>
        <el-table-column prop="days" label="天数" width="70" />
        <el-table-column prop="reason" label="事由" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }: { row: LeaveRecord }">
            <el-tag :type="statusTag[row.status]" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="appliedAt" label="提交时间" width="130" />
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <template v-if="row.status === '待审批'">
              <el-button type="primary" size="small" link @click="approve(row)">批准</el-button>
              <el-button type="danger" size="small" link @click="reject(row)">驳回</el-button>
              <el-button type="warning" size="small" link @click="openEdit(row)">编辑</el-button>
            </template>
            <el-button type="info" size="small" link @click="onRemove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'edit' ? '编辑请假申请' : '新建请假申请'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="申请人">
          <span>{{ user.name }}（{{ user.project }}）</span>
        </el-form-item>
        <el-form-item label="请假类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择">
            <el-option v-for="t in LEAVE_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="请假日期" prop="range">
          <el-date-picker
            v-model="form.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </el-form-item>
        <el-form-item label="事由" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="3" placeholder="请填写请假事由" />
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
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}
</style>
