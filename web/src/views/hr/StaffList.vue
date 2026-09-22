<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useRosterStore } from '@/stores/roster'
import type { RosterMember } from '@/mock/types'
import {
  ROSTER_FIELDS, ROSTER_GROUPS, EMPLOYMENT_TAG, CATEGORY_TAG, GENDER_TAG,
  CATEGORY_OPTIONS, PROJECT_OPTIONS, EMPLOYMENT_OPTIONS, EDUCATION_OPTIONS, STATUS_OPTIONS, deriveCategory
} from '@/mock/rosterOptions'
import { parseRosterXlsx } from '@/utils/rosterExcel'
import { useSalaryStore } from '@/stores/salary'
import { rosterMonthlyPay, salaryVal, isFormula, money } from '@/utils/salary'

const store = useRosterStore()
const salaryStore = useSalaryStore()

const tableFields = ROSTER_FIELDS.filter((f) => f.key !== 'name')

const TABS = ['全部', ...CATEGORY_OPTIONS]
const activeTab = ref('全部')

const filters = reactive({
  keyword: '',
  project: '',
  employmentType: '',
  education: '',
  status: ''
})

function fieldsByGroup(group: string) {
  return ROSTER_FIELDS.filter((f) => f.group === group)
}

function statusTag(v: string) {
  return v === '正常在岗' ? 'success' : v === '离职' ? 'danger' : v === '待岗' ? 'warning' : 'info'
}

function cellOf(field: (typeof ROSTER_FIELDS)[number], row: any) {
  const v = (row as Record<string, unknown>)[field.key]
  if (field.key === 'employmentType') return { kind: 'tag', tag: EMPLOYMENT_TAG[v as string] || '' }
  if (field.key === 'gender') return { kind: 'tag', tag: GENDER_TAG[v as string] || '' }
  if (field.key === 'status') return { kind: 'tag', tag: statusTag(v as string) }
  if (field.type === 'enum') return { kind: 'tag', tag: '' }
  return { kind: 'text' }
}

const filtered = computed<RosterMember[]>(() => {
  const kw = filters.keyword.trim().toLowerCase()
  return store.list.filter((r) => {
    if (activeTab.value !== '全部' && r.category !== activeTab.value) return false
    if (filters.project && r.project !== filters.project) return false
    if (filters.employmentType && r.employmentType !== filters.employmentType) return false
    if (filters.education && r.education !== filters.education) return false
    if (filters.status && r.status !== filters.status) return false
    if (kw && !(r.name.toLowerCase().includes(kw) || r.idCard.toLowerCase().includes(kw))) return false
    return true
  })
})

const stats = computed(() => {
  const all = store.list
  return {
    total: all.length,
    formal: all.filter((r) => r.category === '正式工').length,
    temp: all.filter((r) => r.category === '临时工').length,
    logistic: all.filter((r) => r.category === '后勤辅助').length
  }
})

function onReset() {
  filters.keyword = ''
  filters.project = ''
  filters.employmentType = ''
  filters.education = ''
  filters.status = ''
}

/* ---------- 上传 ---------- */
const fileInput = ref<HTMLInputElement | null>(null)
async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const members = await parseRosterXlsx(file)
    await ElMessageBox.confirm(
      `将导入 ${members.length} 条人员记录并覆盖当前列表，是否继续？`,
      '导入确认',
      { type: 'warning', confirmButtonText: '覆盖导入', cancelButtonText: '取消' }
    )
    store.importReplace(members)
    ElMessage.success(`已导入 ${members.length} 条记录`)
  } catch (err) {
    if (err && (err as { message?: string }).message) ElMessage.error((err as { message: string }).message)
  } finally {
    input.value = ''
  }
}

function onResetData() {
  ElMessageBox.confirm('将清除本地修改，恢复到初始种子数据，是否继续？', '重置确认', {
    type: 'warning'
  })
    .then(() => {
      store.resetToSeed()
      ElMessage.success('已恢复初始数据')
    })
    .catch(() => {})
}

/* ---------- 新增 / 编辑 ---------- */
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const formRef = ref<FormInstance>()
const form = reactive<Record<string, any>>(blankMember())

function blankMember(): Record<string, any> {
  return {
    id: 0,
    seq: 0,
    name: '',
    gender: '男',
    idCard: '',
    phone: '',
    age: 0,
    joinDate: '',
    employmentType: '正式工',
    education: '高中',
    school: '',
    major: '',
    title: '',
    political: '群众',
    position: '',
    adminSeries: '',
    profSeries: '无',
    salaryMechanism: '岗位绩效制',
    salaryGrade: '',
    monthlyPay: 0,
    firstAdjust: '',
    project: '荔园项目',
    status: '正常在岗',
    certificates: '',
    contractRel: '示范建工'
  }
}

const rules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  gender: [{ required: true, message: '请选择性别', trigger: 'change' }],
  idCard: [{ required: true, message: '请输入身份证号', trigger: 'blur' }],
  employmentType: [{ required: true, message: '请选择用工形式', trigger: 'change' }]
}

function openAdd() {
  Object.assign(form, blankMember())
  dialogMode.value = 'add'
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}
function openEdit(row: RosterMember) {
  Object.assign(form, JSON.parse(JSON.stringify(row)))
  dialogMode.value = 'edit'
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}
function onSave() {
  formRef.value?.validate((valid: boolean) => {
    if (!valid) return
    const category = deriveCategory({ employmentType: form.employmentType, position: form.position })
    if (dialogMode.value === 'add') {
      store.add({ ...(form as Omit<RosterMember, 'id'>), category })
    } else {
      store.update({ ...(form as RosterMember), category })
    }
    dialogVisible.value = false
    ElMessage.success(dialogMode.value === 'add' ? '已新增人员' : '已保存修改')
  })
}

/* ---------- 删除 / 详情 ---------- */
function onDelete(row: RosterMember) {
  ElMessageBox.confirm(`确认删除「${row.name}」的记录？`, '删除确认', { type: 'warning' })
    .then(() => {
      store.remove(row.id)
      ElMessage.success('已删除')
    })
    .catch(() => {})
}

const detailVisible = ref(false)
const detailRow = ref<RosterMember | null>(null)
function openDetail(row: RosterMember) {
  detailRow.value = row
  detailVisible.value = true
}

/** 从详情抽屉直接进入编辑 */
function onEditFromDetail() {
  if (!detailRow.value) return
  openEdit(detailRow.value)
  detailVisible.value = false
}

/* ---------- 月薪公式 / 勾稽 ---------- */
const liveMonthlyPay = computed(() => rosterMonthlyPay(form as unknown as RosterMember))
function fillMonthlyByGrade() {
  if (!form.salaryGrade) {
    ElMessage.warning('请先填写「薪档」再按薪档标准填月薪')
    return
  }
  form.monthlyPay = '=[薪档标准.月度实领标准]'
  ElMessage.success('已写入公式：= [薪档标准.月度实领标准]')
}

const salaryMatch = computed(() => {
  if (!detailRow.value) return null
  const latest = salaryStore.monthList[0]
  if (!latest) return null
  return salaryStore.months[latest]?.records.find((r) => r.name === detailRow.value!.name) || null
})
const detailMonthlyPay = computed(() => (detailRow.value ? rosterMonthlyPay(detailRow.value) : 0))
const detailNet = computed(() => (salaryMatch.value ? salaryVal(salaryMatch.value, 'netTotal') : null))
const detailDiff = computed(() => {
  if (detailNet.value == null) return null
  return detailNet.value - detailMonthlyPay.value
})
</script>

<template>
  <div>
    <!-- 统计卡 -->
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">人员总数</span>
          <div class="stat-value">{{ stats.total }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">正式工</span>
          <div class="stat-value">{{ stats.formal }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">临时工</span>
          <div class="stat-value">{{ stats.temp }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">后勤辅助</span>
          <div class="stat-value">{{ stats.logistic }} 人</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <!-- 工具栏 -->
      <div class="toolbar">
        <el-button type="primary" @click="fileInput?.click()">上传花名册</el-button>
        <el-button type="success" @click="openAdd">新增人员</el-button>
        <el-button @click="onResetData">恢复初始数据</el-button>
        <div class="toolbar-spacer" />
        <el-input v-model="filters.keyword" placeholder="搜索姓名 / 身份证号" clearable style="width: 200px" />
        <el-select v-model="filters.project" placeholder="全部项目" clearable style="width: 140px">
          <el-option v-for="p in PROJECT_OPTIONS" :key="p" :label="p" :value="p" />
        </el-select>
        <el-select v-model="filters.employmentType" placeholder="全部用工形式" clearable style="width: 130px">
          <el-option v-for="t in EMPLOYMENT_OPTIONS" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-model="filters.education" placeholder="全部学历" clearable style="width: 120px">
          <el-option v-for="e in EDUCATION_OPTIONS" :key="e" :label="e" :value="e" />
        </el-select>
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 120px">
          <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="onReset">重置</el-button>
      </div>

      <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display: none" @change="onFileChange" />

      <!-- 分组标签页 -->
      <el-tabs v-model="activeTab" class="cat-tabs">
        <el-tab-pane v-for="t in TABS" :key="t" :label="t" :name="t" />
      </el-tabs>

      <!-- 花名册表格（完整 23 字段） -->
      <el-table :data="filtered" border stripe size="small" height="560" class="roster-table">
        <el-table-column type="index" label="序号" width="64" fixed="left" />
        <el-table-column label="姓名" width="100" fixed="left">
          <template #default="{ row }">
            <span class="cell-name">{{ (row as RosterMember).name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分组" width="92">
          <template #default="{ row }">
            <el-tag :type="CATEGORY_TAG[(row as RosterMember).category]" size="small">
              {{ (row as RosterMember).category }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-for="f in tableFields"
          :key="f.key"
          :prop="f.key"
          :label="f.label"
          :width="f.width"
          :min-width="f.type === 'textarea' ? 180 : undefined"
        >
          <template #default="{ row }">
            <el-tag
              v-if="cellOf(f, row as RosterMember).kind === 'tag'"
              :type="cellOf(f, row as RosterMember).tag"
              size="small"
            >
              {{ (row as Record<string, unknown>)[f.key] }}
            </el-tag>
            <span v-else class="cell-text" :title="String((row as Record<string, unknown>)[f.key] ?? '')">
              {{ (row as Record<string, unknown>)[f.key] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="156" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row as RosterMember)">详情</el-button>
            <el-button link type="warning" size="small" @click="openEdit(row as RosterMember)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row as RosterMember)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? '新增人员' : '编辑人员'"
      width="760px"
      destroy-on-close
    >
      <el-form :model="form" :rules="rules" ref="formRef" label-width="96px">
        <template v-for="g in ROSTER_GROUPS" :key="g">
          <el-divider content-position="left">{{ g }}</el-divider>
          <el-row :gutter="12">
            <el-col :span="8" v-for="f in fieldsByGroup(g)" :key="f.key">
              <el-form-item :label="f.label" :prop="f.key" :rules="(rules as Record<string, any>)[f.key]">
                <el-select
                  v-if="f.type === 'enum'"
                  v-model="form[f.key]"
                  :placeholder="f.label"
                  filterable
                  :allow-create="!!f.free"
                  default-first-option
                  style="width: 100%"
                >
                  <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
                </el-select>
                <el-date-picker
                  v-else-if="f.type === 'date'"
                  v-model="form[f.key]"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择日期"
                  style="width: 100%"
                />
                <el-input-number
                  v-else-if="f.type === 'number' && f.key !== 'monthlyPay'"
                  v-model="form[f.key]"
                  :min="0"
                  controls-position="right"
                  style="width: 100%"
                />
                <el-input
                  v-else-if="f.key === 'monthlyPay'"
                  v-model="form[f.key]"
                  placeholder="数字 或 =[薪档标准.月度实领标准]"
                >
                  <template #append>
                    <span v-if="isFormula(form.monthlyPay)" class="fx">fx {{ money(liveMonthlyPay) }}</span>
                    <el-button v-else link type="primary" @click="fillMonthlyByGrade">按薪档标准</el-button>
                  </template>
                </el-input>
                <el-input
                  v-else-if="f.type === 'textarea'"
                  v-model="form[f.key]"
                  type="textarea"
                  :rows="2"
                />
                <el-input v-else v-model="form[f.key]" :placeholder="f.label" />
              </el-form-item>
            </el-col>
          </el-row>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailVisible" title="人员详情" size="460px">
      <el-descriptions v-if="detailRow" :column="1" border>
        <el-descriptions-item label="分组">
          <el-tag :type="CATEGORY_TAG[detailRow.category]" size="small">{{ detailRow.category }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-for="f in ROSTER_FIELDS" :key="f.key" :label="f.label">
          {{ (detailRow as Record<string, unknown>)[f.key] || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="linkage-card" v-if="salaryMatch || isFormula((detailRow as RosterMember).monthlyPay)">
        <div class="linkage-title">薪档勾稽</div>
        <div class="linkage-row">
          <span>月薪（公式）</span><b>{{ money(detailMonthlyPay) }}</b>
          <span v-if="isFormula((detailRow as RosterMember).monthlyPay)" class="fx">fx</span>
        </div>
        <template v-if="salaryMatch">
          <div class="linkage-row">
            <span>本月工资实领</span><b>{{ money(detailNet!) }}</b>
          </div>
          <div class="linkage-row">
            <span>差异</span>
            <el-tag :type="Math.abs(detailDiff!) > 0.01 ? 'warning' : 'success'" size="small">
              {{ Math.abs(detailDiff!) > 0.01 ? money(detailDiff!) : '一致' }}
            </el-tag>
          </div>
        </template>
        <div class="linkage-hint" v-else>本月工资表暂无此人记录，上传后自动勾稽</div>
      </div>
      <template #footer>
        <el-button type="primary" @click="onEditFromDetail">编辑资料</el-button>
      </template>
    </el-drawer>
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

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.toolbar-spacer {
  flex: 1;
}

.cat-tabs {
  margin-bottom: 8px;
}

.roster-table {
  width: 100%;
}
/* 花名册横向滚动条常驻：el-table 横条默认 hover 才出现，鼠标用户发现不了 */
:deep(.el-scrollbar__bar.is-horizontal) {
  display: block !important;
  opacity: 1 !important;
  height: 10px;
  padding: 0 6px;
}
:deep(.el-scrollbar__bar.is-horizontal .el-scrollbar__thumb) {
  background: rgba(120, 100, 70, 0.45);
  border-radius: 6px;
}
.cell-text {
  display: inline-block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.cell-name {
  font-weight: 600;
}

:deep(.el-divider__text) {
  color: var(--text-gold);
  font-weight: 600;
}
.fx {
  font-size: 10px;
  font-style: italic;
  padding: 0 4px;
  border-radius: 4px;
  background: rgba(217, 164, 65, 0.22);
  color: var(--text-gold);
  font-weight: 600;
}
.linkage-card {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid rgba(120, 100, 70, 0.2);
  border-radius: 10px;
  background: rgba(255, 253, 248, 0.4);
}
.linkage-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-gold);
  margin-bottom: 8px;
}
.linkage-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 3px 0;
}
.linkage-row span:first-child {
  color: var(--text-tertiary);
  min-width: 92px;
}
.linkage-hint {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 6px;
}
</style>
