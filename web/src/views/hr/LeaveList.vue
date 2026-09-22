<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { LeaveRecord } from '@/mock/types'
import { useUserStore } from '@/stores/user'
import { useLeaveStore } from '@/stores/leave'
import { useRosterStore } from '@/stores/roster'

const user = useUserStore()
const leaveStore = useLeaveStore()
const rosterStore = useRosterStore()

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

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  applicant: '',
  type: '事假' as LeaveRecord['type'],
  range: [] as string[],
  reason: ''
})
const rules: FormRules = {
  applicant: [{ required: true, message: '请选择申请人', trigger: 'change' }],
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
  form.applicant = ''
  form.type = '事假'
  form.range = []
  form.reason = ''
  dialogVisible.value = true
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate((valid) => {
    if (!valid) return
    const [start, end] = form.range
    const member = rosterStore.list.find((m) => m.name === form.applicant)
    leaveStore.add({
      id: Date.now(),
      applicant: form.applicant,
      project: member?.project || user.project,
      type: form.type,
      start,
      end,
      days: daysBetween(start, end),
      reason: form.reason,
      status: '待审批',
      appliedAt: `${today()} ${new Date().toTimeString().slice(0, 5)}`
    })
    dialogVisible.value = false
    ElMessage.success('请假申请已提交，等待审批')
  })
}

function approve(row: LeaveRecord) {
  leaveStore.approve(row.id)
  ElMessage.success(`已批准 ${row.applicant} 的${row.type}申请`)
}

function reject(row: LeaveRecord) {
  leaveStore.reject(row.id)
  ElMessage.warning(`已驳回 ${row.applicant} 的${row.type}申请`)
}

function onRemove(row: LeaveRecord) {
  leaveStore.remove(row.id)
  ElMessage.info('已删除该请假记录')
}
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
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.status === '待审批'">
              <el-button type="primary" size="small" link @click="approve(row)">批准</el-button>
              <el-button type="danger" size="small" link @click="reject(row)">驳回</el-button>
            </template>
            <el-button type="info" size="small" link @click="onRemove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建请假申请" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="申请人" prop="applicant">
          <el-select v-model="form.applicant" placeholder="选择花名册人员" filterable clearable style="width: 100%">
            <el-option v-for="m in rosterStore.list" :key="m.id" :label="`${m.name}（${m.project} · ${m.position}）`" :value="m.name" />
          </el-select>
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
