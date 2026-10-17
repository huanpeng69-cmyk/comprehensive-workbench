<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useSalaryStore } from '@/stores/salary'
import { useRosterStore } from '@/stores/roster'
import type { SalaryRecord, SalaryMonthData, GradeStandard, PerfRecord } from '@/mock/types'
import { SALARY_FIELD_LABELS, DUE_FORMULA, NET_FORMULA, BASE_FORMULA_BY_FIELD, GRADE_STD_FIELD_LABELS } from '@/mock/salaryOptions'
import { resolveHandling } from '@/utils/salaryBasePay'
import { leaveDaysByKind } from '@/stores/leave'
import { parseSalaryXlsx } from '@/utils/salaryExcel'
import { salaryVal, isFormula, money, insuranceTotal } from '@/utils/salary'
import { gradeStandards, ensureSeed, persistStandards, getStandard, addStandard, removeStandard, gradeList } from '@/utils/salaryGradeStandard'

const store = useSalaryStore()
const rosterStore = useRosterStore()
const activeMonth = ref('')

const current = computed<SalaryMonthData | null>(() => store.months[activeMonth.value] || null)

/* ---------- 薪档标准：回填映射 + 首次种子 ---------- */
const rosterGrades = computed(() => {
  const m: Record<string, string> = {}
  for (const r of rosterStore.list) if (r.salaryGrade) m[r.name] = r.salaryGrade
  return m
})

function seedGradeStandard() {
  const grades = [...new Set(rosterStore.list.map((m) => m.salaryGrade).filter(Boolean))] as string[]
  const samples: Record<string, Partial<GradeStandard>> = {}
  const latest = store.monthList[0]
  const recs = (latest && store.months[latest]?.records) || []
  const byName: Record<string, SalaryRecord> = {}
  for (const r of recs) byName[r.name] = r
  for (const m of rosterStore.list) {
    if (!m.salaryGrade) continue
    const rec = byName[m.name]
    if (rec) {
      samples[m.salaryGrade] = {
        postSalaryBase: (rec.postSalary as number) || 0,
        titleAllowanceBase: (rec.titleAllowance as number) || 0,
        seniorityBase: (rec.seniority as number) || 0,
        siteBase: (rec.siteAllowance as number) || 0,
        netStandard: (rec.netTotal as number) || 0
      }
    }
  }
  ensureSeed({ grades, samples })
}
seedGradeStandard()

watch(
  () => store.monthList,
  (list) => {
    if (!activeMonth.value || !list.includes(activeMonth.value)) activeMonth.value = list[0] || ''
  },
  { immediate: true }
)

/* ---------- 手工新建月份（无数据时也能起步，不必依赖 Excel 导入） ---------- */
/**
 * 「新建记录时可用的月份」。
 *
 * <p>没有任何月份数据时，允许用户手工指定一个月（如 2026-09）直接新增记录；
 * 后端 {@code createRecord} 会在该月份不存在时自动创建月表，因此无需先建月份。
 * 一旦已有月份数据，就只用已有月份，避免手误建出多余的空月份。
 */
const pendingMonth = ref<string>('')

/** 当前实际生效的月份：已有月份优先，其次手工指定的待建月份 */
const effectiveMonth = computed(() => activeMonth.value || pendingMonth.value)

/** 是否处于「有月份数据」的正常态 */
const hasMonths = computed(() => store.monthList.length > 0)

/** 空状态下是否已具备新增条件（选好待建月份） */
const canAddInEmpty = computed(() => !hasMonths.value && !!pendingMonth.value)

function defaultMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 空状态下的「新增工资记录」入口：未选月份时先按当月填充 */
function onAddInEmpty() {
  if (!pendingMonth.value) pendingMonth.value = defaultMonth()
  openAdd()
}

/** 月份选择器变化：已有月份时写 activeMonth，否则写待建月份 */
function onMonthPicked(v: string) {
  if (hasMonths.value) activeMonth.value = v
  else pendingMonth.value = v
}

/** 供模板使用的月份下拉选项 */
const monthOptions = computed(() =>
  hasMonths.value ? store.monthList : pendingMonth.value ? [pendingMonth.value] : []
)

/** 当前选中的月份值（用于 el-select 双向绑定） */
const pickedMonth = computed({
  get: () => (hasMonths.value ? activeMonth.value : pendingMonth.value),
  set: (v: string) => onMonthPicked(v)
})

/* ---------- 上传 ---------- */
const fileInput = ref<HTMLInputElement | null>(null)
async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const data = await parseSalaryXlsx(file, rosterGrades.value)
    await ElMessageBox.confirm(
      `识别到 ${data.month} 工资表：${data.records.length} 名人员明细、${data.perf.length} 条绩效记录，将覆盖该月数据，是否继续？`,
      '导入确认',
      { type: 'warning', confirmButtonText: '覆盖导入', cancelButtonText: '取消' }
    )
    await store.importMonth(data)
    activeMonth.value = data.month
    seedGradeStandard()
    ElMessage.success(`已导入 ${data.month}：${data.records.length} 人工资、${data.perf.length} 条绩效`)
  } catch (err) {
    if (err && (err as { message?: string }).message) ElMessage.error((err as { message: string }).message)
  } finally {
    input.value = ''
  }
}

function onRemoveMonth() {
  if (!activeMonth.value) return
  ElMessageBox.confirm(`将删除 ${activeMonth.value} 的全部工资制作数据，是否继续？`, '删除确认', { type: 'warning' })
    .then(() => store.removeMonth(activeMonth.value))
    .then(() => ElMessage.success('已删除'))
    .catch((err: unknown) => {
      if (err instanceof Error) ElMessage.error(err.message)
    })
}

/* ---------- 分组标签页 ---------- */
const departments = computed(() => [...new Set((current.value?.records || []).map((r) => r.department))])
const activeTab = ref('全部')
const filtered = computed(() => {
  const list = current.value?.records || []
  if (activeTab.value === '全部' || activeTab.value === '绩效表') return list
  return list.filter((r) => r.department === activeTab.value)
})

/* ---------- 统计（全部按公式求值口径） ---------- */
const stats = computed(() => {
  const list = current.value?.records || []
  let due = 0
  let net = 0
  let diffCount = 0
  for (const r of list) {
    const d = salaryVal(r, 'dueTotal')
    const n = salaryVal(r, 'netTotal')
    due += d
    net += n
    if (r.importedDue != null && Math.abs(d - r.importedDue) > 0.01) diffCount++
    else if (r.importedNet != null && Math.abs(n - r.importedNet) > 0.01) diffCount++
  }
  return { count: list.length, due, net, diffCount }
})

/* ---------- 表格取值 ---------- */
function val(row: SalaryRecord, key: keyof SalaryRecord) {
  return salaryVal(row, key)
}
function diffProps(row: SalaryRecord) {
  const dDue = row.importedDue != null && Math.abs(salaryVal(row, 'dueTotal') - row.importedDue) > 0.01
  const dNet = row.importedNet != null && Math.abs(salaryVal(row, 'netTotal') - row.importedNet) > 0.01
  return dDue || dNet
}

/* ---------- 编辑 ---------- */
const editVisible = ref(false)
const editing = reactive<Record<string, any>>({})
const editingOrigin = ref<SalaryRecord | null>(null)
const dialogMode = ref<'edit' | 'add'>('edit')

function blankSalary(): Record<string, any> {
  return {
    id: '', month: '', department: '', seq: 0, name: '', salaryGrade: '',
    postDays: '0', postSalary: '0', titleAllowance: '0', otDays: '0', overtime: '0',
    bonus: '0', makeUp: '0', seniority: '0', siteAllowance: '0', dueTotal: '',
    pension: '0', unemployment: '0', medical: '0', housingFund: '0', annuity: '0',
    largeMedical: '0', otherDeduct: '0', leaveDeduct: '0', childEdu: '0', continuingEdu: '0',
    housingLoan: '0', housingRent: '0', elderlyCare: '0', infantCare: '0',
    tax: '0', taxAdjust: '0', deduct: '0', netTotal: '', monthPerf: '0', unionFee: '0',
    sign: '', importedDue: undefined, importedNet: undefined
  }
}

function openAdd() {
  dialogMode.value = 'add'
  Object.assign(editing, blankSalary())
  // 空状态下手工新建时，把待建月份写进记录，保存后自动成为该月的第一条数据
  editing.month = effectiveMonth.value
  editingOrigin.value = null
  editVisible.value = true
}

const GROUPS: { title: string; fields: (keyof SalaryRecord)[] }[] = [
  {
    title: '工资部分',
    fields: ['postDays', 'postSalary', 'titleAllowance', 'otDays', 'overtime', 'bonus', 'makeUp', 'seniority', 'siteAllowance', 'dueTotal']
  },
  { title: '专项扣除（五险一金）', fields: ['pension', 'unemployment', 'medical', 'housingFund', 'annuity', 'largeMedical'] },
  { title: '个税与其他', fields: ['tax', 'taxAdjust', 'deduct', 'netTotal'] },
  { title: '其他扣除与专项附加', fields: ['otherDeduct', 'leaveDeduct', 'childEdu', 'continuingEdu', 'housingLoan', 'housingRent', 'elderlyCare', 'infantCare'] },
  { title: '附列', fields: ['monthPerf', 'unionFee'] }
]

function openEdit(row: SalaryRecord) {
  dialogMode.value = 'edit'
  editingOrigin.value = row
  Object.assign(editing, JSON.parse(JSON.stringify(row)))
  editVisible.value = true
}

/** 编辑中的实时计算值 */
const liveDue = computed(() => salaryVal(editing as unknown as SalaryRecord, 'dueTotal'))
const liveNet = computed(() => salaryVal(editing as unknown as SalaryRecord, 'netTotal'))
const liveInsurance = computed(() => insuranceTotal(editing as unknown as SalaryRecord))
const liveDeductAll = computed(
  () =>
    liveInsurance.value +
    salaryVal(editing as unknown as SalaryRecord, 'tax') +
    salaryVal(editing as unknown as SalaryRecord, 'taxAdjust') +
    salaryVal(editing as unknown as SalaryRecord, 'deduct') +
    salaryVal(editing as unknown as SalaryRecord, 'otherDeduct') +
    // 其他假别扣款（病假等人工填写项）：已在岗位工资中扣除，此处仅用于「扣款合计」展示与核对
    salaryVal(editing as unknown as SalaryRecord, 'leaveDeduct')
)

/**
 * 当前编辑人员本期「不对应固定扣款规则、需人工填写」的假别提示。
 * 年假（全额发放）与事假（自动全额扣）不进这里，避免用户误以为还要额外填。
 */
const manualLeaveHint = computed(() => {
  const name = String(editing.name ?? '').trim()
  const month = String(editing.month || activeMonth.value || '').trim()
  if (!name || !month) return ''
  const parts: string[] = []
  for (const d of leaveDaysByKind(name, month)) {
    if (resolveHandling(d.kind) === 'MANUAL') {
      parts.push(`${d.kind} ${d.days} 天`)
    }
  }
  return parts.join('、')
})

function onSave() {
  const month = effectiveMonth.value
  if (!month) {
    ElMessage.warning('请先选择或填写工资月份')
    return
  }
  if (dialogMode.value === 'add') {
    if (!String(editing.name ?? '').trim()) {
      ElMessage.warning('请填写姓名')
      return
    }
    store.addRecord(month, editing as unknown as SalaryRecord)
      .then(async () => {
        editVisible.value = false
        ElMessage.success('已新增工资记录')
        // 空状态手工新增后，该月份才在库里出现：重新拉取并切到该月
        pendingMonth.value = ''
        await store.load().catch(() => {})
        if (store.monthList.includes(month)) activeMonth.value = month
      })
      .catch((err: unknown) => { ElMessage.error(err instanceof Error ? err.message : '新增失败') })
    return
  }
  if (!editingOrigin.value) return
  const next = { ...(editingOrigin.value), ...(editing as unknown as SalaryRecord) } as SalaryRecord
  // 空值归一为 0（签字除外）
  const bag = next as unknown as Record<string, unknown>
  for (const k of Object.keys(bag)) {
    if (k === 'sign') continue
    const v = bag[k]
    if (v === '' || v == null) bag[k] = 0
  }
  store
    .updateRecord(activeMonth.value, next)
    .then(() => {
      editVisible.value = false
      ElMessage.success('已保存并按公式重算')
    })
    .catch((err: unknown) => {
      ElMessage.error(err instanceof Error ? err.message : '保存失败')
    })
}

function onRemoveSalary(row: SalaryRecord) {
  ElMessageBox.confirm('确定删除 ' + row.name + ' 的 ' + activeMonth.value + ' 工资记录吗？', '删除工资记录', { type: 'warning' })
    .then(() => store.removeRecord(activeMonth.value, row.id))
    .then(() => ElMessage.success('工资记录已删除'))
    .catch((err: unknown) => {
      if (err === 'cancel' || err === 'close') return
      ElMessage.error(err instanceof Error ? err.message : '删除失败')
    })
}

const perfDialogVisible = ref(false)
const perfOrigin = ref<PerfRecord | null>(null)
const perfEditing = reactive<Record<string, any>>({})
const PERF_FIELDS: { key: keyof PerfRecord; label: string }[] = [
  { key: 'seq', label: '序号' }, { key: 'department', label: '部门' },
  { key: 'name', label: '姓名' }, { key: 'monthScore', label: '月度总成绩' },
  { key: 'perfLevel', label: '绩效级别' }, { key: 'incentiveCoef', label: '激励系数' },
  { key: 'sepPerf', label: '当月绩效' }, { key: 'augBase', label: '上月基数' },
  { key: 'incentivePart', label: '激励部分' }, { key: 'balanceCoef', label: '平衡系数' },
  { key: 'coopCoef', label: '配合度系数' }, { key: 'finalIncentive', label: '最终激励额度' },
  { key: 'totalPerf', label: '月度绩效总数' }
]

function blankPerf(): Record<string, any> {
  return { id: '', month: activeMonth.value, seq: (current.value?.perf.length || 0) + 1, department: '', name: '',
    monthScore: '', perfLevel: '', incentiveCoef: '', sepPerf: '0', augBase: '0',
    incentivePart: '0', balanceCoef: '', coopCoef: '', finalIncentive: '0', totalPerf: '0' }
}

function openPerfAdd() {
  perfOrigin.value = null
  Object.assign(perfEditing, blankPerf())
  perfDialogVisible.value = true
}

function openPerfEdit(row: PerfRecord) {
  perfOrigin.value = row
  Object.assign(perfEditing, JSON.parse(JSON.stringify(row)))
  perfDialogVisible.value = true
}

function savePerf() {
  if (!activeMonth.value || !String(perfEditing.name || '').trim()) {
    ElMessage.warning('请填写绩效记录姓名')
    return
  }
  const isEdit = Boolean(perfOrigin.value)
  const next = { ...(perfOrigin.value || {}), ...perfEditing, name: String(perfEditing.name).trim() } as PerfRecord
  const request = isEdit
    ? store.updatePerf(activeMonth.value, next)
    : store.addPerf(activeMonth.value, next)
  request.then(() => {
    perfDialogVisible.value = false
    ElMessage.success(isEdit ? '绩效记录已保存' : '绩效记录已新增')
  }).catch((err: unknown) => ElMessage.error(err instanceof Error ? err.message : '保存失败'))
}

function onRemovePerf(row: PerfRecord) {
  ElMessageBox.confirm('确定删除 ' + row.name + ' 的绩效记录吗？', '删除绩效记录', { type: 'warning' })
    .then(() => store.removePerf(activeMonth.value, row.id))
    .then(() => ElMessage.success('绩效记录已删除'))
    .catch((err: unknown) => {
      if (err === 'cancel' || err === 'close') return
      ElMessage.error(err instanceof Error ? err.message : '删除失败')
    })
}
function onResetFormula(key: 'dueTotal' | 'netTotal') {
  editing[key] = key === 'dueTotal' ? DUE_FORMULA : NET_FORMULA
}

/* ---------- 按薪档标准重算（基数类改写为标准引用公式） ---------- */
function applyGradeStandard() {
  if (!activeMonth.value) return
  const d = store.months[activeMonth.value]
  if (!d) return
  ElMessageBox.confirm(
    '将对「有薪档标准」的人员，把 岗位工日/岗位工资/职务津贴/年功/施工津贴 改写为引用薪档标准与请假的公式：岗位工日 = 应出工日 − 事假天数；岗位工资 = 日工资标准 × 应出工日 − 事假扣款 − 其他假别扣款。年假全额发放不扣；病假等无固定规则的假别取「其他假别扣款」列的人工填写值。无薪档标准的人员保持不变。',
    '按薪档标准重算',
    { type: 'info', confirmButtonText: '重算', cancelButtonText: '取消' }
  )
    .then(() => {
      let applied = 0
      const records = d.records.map((r) => {
        if (!r.salaryGrade || !getStandard(r.salaryGrade)) return r
        const copy: SalaryRecord = { ...r }
        for (const [field, formula] of Object.entries(BASE_FORMULA_BY_FIELD)) {
          ;(copy as unknown as Record<string, unknown>)[field] = formula
        }
        applied++
        return copy
      })
      return store.importMonth({ ...d, records }).then(() => {
        ElMessage.success(`已对 ${applied} 人写入薪档标准公式，改标准即自动重算`)
      })
    })
    .catch((err: unknown) => {
      if (err instanceof Error) ElMessage.error(err.message)
    })
}

/* ---------- 薪档标准表保存 ---------- */
function saveStandard() {
  persistStandards()
    .then(() => ElMessage.success('薪档标准表已保存'))
    .catch((err: unknown) => {
      ElMessage.error(err instanceof Error ? err.message : '保存失败')
    })
}
function onAddGrade() {
  const g = (window.prompt('新增薪档名称（如 P10）：') || '').trim()
  if (g) addStandard(g)
}
function onRemoveGrade(g: string) {
  removeStandard(g)
}
</script>

<template>
  <div>
    <el-row v-if="current" :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">{{ activeMonth }} 人员数</span>
          <div class="stat-value">{{ stats.count }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">应发合计</span>
          <div class="stat-value">{{ money(stats.due) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">实发合计</span>
          <div class="stat-value">{{ money(stats.net) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">与原表差异人数</span>
          <div class="stat-value" :class="{ 'diff-warn': stats.diffCount > 0 }">{{ stats.diffCount }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="fileInput?.click()">上传工资表</el-button>
        <el-button v-if="activeMonth && activeTab === '绩效表'" type="success" plain @click="openPerfAdd">新增绩效记录</el-button>
        <el-button v-else-if="activeMonth && activeTab !== '薪档标准'" type="success" plain @click="openAdd">新增工资记录</el-button>
        <!-- 空状态：没有月份数据时也允许手工新增，月份可用待建月份 -->
        <el-button v-else-if="!hasMonths" type="success" plain @click="onAddInEmpty">新增工资记录</el-button>
        <!-- 空状态且切到薪档标准时，仍可新增工资记录（不影响标准维护） -->
        <el-button v-else-if="!activeMonth && hasMonths" type="success" plain disabled>新增工资记录</el-button>
        <el-date-picker
          v-if="!hasMonths"
          v-model="pendingMonth"
          type="month"
          value-format="YYYY-MM"
          placeholder="选择要新建的月份"
          style="width: 160px"
        />
        <el-select
          v-else
          :model-value="pickedMonth"
          placeholder="选择月份"
          style="width: 130px"
          @update:model-value="onMonthPicked"
        >
          <el-option v-for="m in monthOptions" :key="m" :label="m" :value="m" />
        </el-select>
        <el-button v-if="activeMonth" type="danger" plain @click="onRemoveMonth">删除当月数据</el-button>
        <el-button v-if="activeMonth" type="warning" plain @click="applyGradeStandard">按薪档标准重算</el-button>
        <span v-if="current" class="imported-at">导入时间：{{ new Date(current.importedAt).toLocaleString('zh-CN') }}</span>
      </div>
      <input ref="fileInput" type="file" accept=".xls,.xlsx" style="display: none" @change="onFileChange" />

      <!-- 空状态：给出两条起步路径，并可直达薪档标准维护 -->
      <div v-if="!current && activeTab !== '薪档标准'" class="empty-guide">
        <el-empty description="还没有工资数据" />
        <div class="empty-actions">
          <el-button type="primary" plain @click="fileInput?.click()">上传当月 Excel</el-button>
          <el-button type="success" plain @click="onAddInEmpty">手工新增第一条工资记录</el-button>
          <el-button plain @click="activeTab = '薪档标准'">维护薪档标准（日工资标准等）</el-button>
        </div>
        <p class="empty-tip">
          提示：岗位工资依赖「薪档标准」里的<b>日工资标准</b>。首次使用建议先维护标准，再新增或导入工资数据。
        </p>
      </div>

      <template v-if="current || activeTab === '薪档标准'">
        <el-tabs v-model="activeTab" class="dept-tabs">
          <el-tab-pane
            v-for="t in ['全部', ...departments, '绩效表', '薪档标准']"
            :key="t"
            :label="t"
            :name="t"
          />
        </el-tabs>

        <!-- 绩效表 -->
        <div v-if="activeTab === '绩效表'" class="toolbar"><span class="imported-at">绩效记录支持单条新增、编辑和删除</span></div>
        <el-table v-if="activeTab === '绩效表'" :data="current?.perf || []" border stripe size="small" max-height="560">
          <el-table-column prop="seq" label="序号" width="60" />
          <el-table-column prop="department" label="部门" width="110" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="monthScore" label="月度总成绩" width="100" />
          <el-table-column prop="perfLevel" label="绩效级别" width="90" />
          <el-table-column prop="incentiveCoef" label="激励系数" width="90" />
          <el-table-column label="当月绩效" width="100">
            <template #default="{ row }">{{ money(salaryVal(row as any, 'sepPerf' as any)) }}</template>
          </el-table-column>
          <el-table-column label="上月基数" width="100">
            <template #default="{ row }">{{ money(salaryVal(row as any, 'augBase' as any)) }}</template>
          </el-table-column>
          <el-table-column prop="balanceCoef" label="平衡系数" width="90" />
          <el-table-column prop="coopCoef" label="配合度系数" width="100" />
          <el-table-column label="最终激励额度" width="110">
            <template #default="{ row }">{{ money(salaryVal(row as any, 'finalIncentive' as any)) }}</template>
          </el-table-column>
          <el-table-column label="月度绩效总数" width="110">
            <template #default="{ row }">{{ money(salaryVal(row as any, 'totalPerf' as any)) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" size="small" @click="openPerfEdit(row as PerfRecord)">编辑</el-button>
              <el-button link type="danger" size="small" @click="onRemovePerf(row as PerfRecord)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 薪档标准表 -->
        <div v-else-if="activeTab === '薪档标准'">
          <div class="toolbar">
            <el-button type="primary" plain @click="saveStandard">保存标准表</el-button>
            <el-button plain @click="onAddGrade">新增薪档</el-button>
            <span class="imported-at">改这里的基数 / 比例 → 工资基数类公式实时联动重算</span>
          </div>
          <el-table :data="gradeStandards" border stripe size="small" max-height="560">
            <el-table-column label="薪档" width="130" fixed="left">
              <template #default="{ row }">
                <span class="cell-name">{{ row.grade }}</span>
                <el-button link type="danger" size="small" @click="onRemoveGrade(row.grade)">删</el-button>
              </template>
            </el-table-column>
            <el-table-column
              v-for="key in Object.keys(GRADE_STD_FIELD_LABELS).filter((k) => k !== 'id' && k !== 'grade')"
              :key="key"
              :label="GRADE_STD_FIELD_LABELS[key as keyof GradeStandard]"
              min-width="120"
            >
              <template #default="{ row }">
                <el-input v-model="row[key]" size="small" placeholder="数字或 =公式" />
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 工资明细 -->
        <el-table v-else :data="filtered" border stripe size="small" max-height="560" class="salary-table">
          <el-table-column type="index" label="#" width="48" fixed="left" />
          <el-table-column label="姓名" width="90" fixed="left">
            <template #default="{ row }"><span class="cell-name">{{ (row as SalaryRecord).name }}</span></template>
          </el-table-column>
          <el-table-column prop="department" label="部门" width="120" fixed="left" />
          <el-table-column label="薪档" width="100" fixed="left">
            <template #default="{ row }">
              <span>{{ (row as SalaryRecord).salaryGrade || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="岗位工资" width="100">
            <template #default="{ row }">{{ money(val(row as SalaryRecord, 'postSalary')) }}</template>
          </el-table-column>
          <el-table-column label="职务津贴" width="90">
            <template #default="{ row }">{{ money(val(row as SalaryRecord, 'titleAllowance')) }}</template>
          </el-table-column>
          <el-table-column label="加班费" width="90">
            <template #default="{ row }">{{ money(val(row as SalaryRecord, 'overtime')) }}</template>
          </el-table-column>
          <el-table-column label="奖金" width="90">
            <template #default="{ row }">{{ money(val(row as SalaryRecord, 'bonus')) }}</template>
          </el-table-column>
          <el-table-column label="应领工资额" width="130">
            <template #default="{ row }">
              <div class="num-cell">
                <span>{{ money(val(row as SalaryRecord, 'dueTotal')) }}</span>
                <span v-if="isFormula((row as SalaryRecord).dueTotal)" class="fx">fx</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="五险一金" width="110">
            <template #default="{ row }">{{ money(insuranceTotal(row as SalaryRecord)) }}</template>
          </el-table-column>
          <el-table-column label="个税" width="90">
            <template #default="{ row }">{{ money(val(row as SalaryRecord, 'tax')) }}</template>
          </el-table-column>
          <el-table-column label="实领工资额" width="130">
            <template #default="{ row }">
              <div class="num-cell">
                <span>{{ money(val(row as SalaryRecord, 'netTotal')) }}</span>
                <span v-if="isFormula((row as SalaryRecord).netTotal)" class="fx">fx</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="核对" width="90" fixed="right">
            <template #default="{ row }">
              <el-tag v-if="diffProps(row as SalaryRecord)" type="warning" size="small">有差异</el-tag>
              <el-tag v-else type="success" size="small">一致</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" size="small" @click="openEdit(row as SalaryRecord)">编辑</el-button>
              <el-button link type="danger" size="small" @click="onRemoveSalary(row as SalaryRecord)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 编辑：支持 =公式 -->
    <el-dialog v-model="editVisible" :title="`${dialogMode === 'add' ? '新增工资' : '编辑工资'} - ${editing.name || ''}`" width="820px" destroy-on-close top="6vh">
      <div class="live-panel">
        <div class="live-item">
          <span class="live-label">应领（公式计算）</span>
          <b>{{ money(liveDue) }}</b>
          <el-tag v-if="editingOrigin?.importedDue != null && Math.abs(liveDue - editingOrigin.importedDue) > 0.01" type="warning" size="small">
            原表 {{ money(editingOrigin.importedDue) }}
          </el-tag>
        </div>
        <div class="live-item">
          <span class="live-label">扣款合计</span>
          <b>{{ money(liveDeductAll) }}</b>
        </div>
        <div class="live-item">
          <span class="live-label">实领（公式计算）</span>
          <b>{{ money(liveNet) }}</b>
          <el-tag v-if="editingOrigin?.importedNet != null && Math.abs(liveNet - editingOrigin.importedNet) > 0.01" type="warning" size="small">
            原表 {{ money(editingOrigin.importedNet) }}
          </el-tag>
        </div>
      </div>

      <el-alert v-if="dialogMode === 'add'" type="info" :closable="false" show-icon
        title="填写姓名与部门后保存，即新增一条该月工资记录（记录 id 自动为 月份-姓名）" style="margin-bottom: 10px" />
      <el-row v-if="dialogMode === 'add'" :gutter="12" style="margin-bottom: 4px">
        <el-col :span="8">
          <el-form-item label="姓名">
            <el-input v-model="editing.name" placeholder="员工姓名" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="部门">
            <el-input v-model="editing.department" placeholder="所在部门" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form label-width="120px" size="small" class="edit-form">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="薪档决定基数类公式取哪个标准；改薪档或改标准表都会实时重算"
          style="margin-bottom: 10px"
        />
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="薪档">
              <el-select
                v-model="editing.salaryGrade"
                filterable
                allow-create
                clearable
                placeholder="选择或输入薪档"
                style="width: 100%"
              >
                <el-option v-for="g in gradeList()" :key="g" :label="g" :value="g" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <template v-for="g in GROUPS" :key="g.title">
          <el-divider content-position="left">{{ g.title }}</el-divider>
          <el-row :gutter="12">
            <el-col v-for="f in g.fields" :key="f" :span="8">
              <el-form-item :label="SALARY_FIELD_LABELS[f]">
                <el-input v-model="editing[f as string]" :placeholder="f === 'sign' ? '签字' : '数字或 =公式'" clearable>
                  <template v-if="isFormula(editing[f as string])" #append>fx</template>
                </el-input>
              </el-form-item>
            </el-col>
          </el-row>
          <div v-if="g.title === '工资部分'" class="formula-tip">
            应领/实领为公式字段（<span class="fx">fx</span>），支持 <code>[字段名]</code> 引用、四则运算与 SUM()，
            <el-button link type="primary" size="small" @click="onResetFormula('dueTotal')">恢复应领默认公式</el-button>
            <el-button link type="primary" size="small" @click="onResetFormula('netTotal')">恢复实领默认公式</el-button>
          </div>
          <div v-if="g.title === '其他扣除与专项附加'" class="formula-tip">
            「其他假别扣款」用于病假等<b>不对应固定扣款规则</b>的假别：系统不自动计算，
            由你按制度填写扣款金额（年假全额发放、事假按日工资自动全额扣除，均无需在此填写）。
            <template v-if="manualLeaveHint">
              <br /><span class="manual-hint">本期该员工需人工填写的假别：{{ manualLeaveHint }}</span>
            </template>
          </div>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存并重算</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="perfDialogVisible" :title="perfOrigin ? '编辑绩效记录' : '新增绩效记录'" width="720px" destroy-on-close>
      <el-form label-width="120px" size="small">
        <el-row :gutter="12">
          <el-col v-for="f in PERF_FIELDS" :key="String(f.key)" :span="12">
            <el-form-item :label="f.label">
              <el-input v-model="perfEditing[f.key as string]" :disabled="f.key === 'seq' && !perfOrigin" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="perfDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="savePerf">保存</el-button>
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
.diff-warn {
  color: #d0a03a;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.imported-at {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-tertiary);
}
.dept-tabs {
  margin-bottom: 8px;
}
.cell-name {
  font-weight: 600;
}
.num-cell {
  display: flex;
  align-items: center;
  gap: 4px;
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
.live-panel {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 8px;
  border: 1px solid rgba(120, 100, 70, 0.18);
  border-radius: 10px;
  background: rgba(255, 253, 248, 0.4);
}
.live-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.live-label {
  color: var(--text-tertiary);
}
.formula-tip {
  margin: -6px 0 10px;
  font-size: 12px;
  color: var(--text-tertiary);
}
.manual-hint {
  color: var(--el-color-warning);
  font-weight: 600;
}

.empty-guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 0 24px;
}
.empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}
.empty-tip {
  margin: 14px 0 0;
  max-width: 620px;
  text-align: center;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-tertiary);
}
</style>
