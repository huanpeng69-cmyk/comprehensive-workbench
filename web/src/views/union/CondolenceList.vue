<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { CondolenceRecord } from '@/mock/types'
import { useUserStore } from '@/stores/user'
import { useCondolenceStore } from '@/stores/condolence'
import { getInstance, type ProcessInstanceVo } from '@/api/workflow'

const user = useUserStore()
const store = useCondolenceStore()

const CATEGORIES = ['生育慰问', '困难帮扶', '退休慰问', '生病住院', '其他']

const statusTag: Record<string, string> = {
  待审批: 'warning',
  已发放: 'success',
  已驳回: 'danger'
}

const stats = computed(() => {
  const list = store.list
  const paid = list.filter((c) => c.status === '已发放')
  return {
    total: list.length,
    pending: list.filter((c) => c.status === '待审批').length,
    paid: paid.length,
    amount: paid.reduce((s, c) => s + (c.amount || 0), 0)
  }
})

// ---- 新建申请 ----
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  category: '困难帮扶',
  reason: '',
  amount: 1000,
  amountInWords: ''
})
const rules: FormRules = {
  category: [{ required: true, message: '请选择慰问类别', trigger: 'change' }],
  reason: [{ required: true, message: '请填写情况说明', trigger: 'blur' }],
  amount: [{ required: true, message: '请填写慰问金额', trigger: 'blur' }]
}

/** 人民币金额转中文大写（标准算法，支持到分）。 */
function amountToChinese(money: number): string {
  if (!money || money <= 0) return '人民币零元整'
  const digit = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖']
  const intUnit = ['', '拾', '佰', '仟', '万', '拾', '佰', '仟', '亿', '拾', '佰', '仟', '万', '拾', '佰', '仟']
  const decUnit = ['角', '分']
  const [intPart, decPart] = Math.abs(money).toFixed(2).split('.')
  let s = ''
  if (intPart === '0') {
    s = '零'
  } else {
    let zero = false
    for (let i = 0; i < intPart.length; i++) {
      const n = +intPart[i]
      const unit = intUnit[intPart.length - 1 - i]
      if (n === 0) {
        zero = true
      } else {
        if (zero) s += '零'
        s += digit[n] + unit
        zero = false
      }
    }
  }
  s += '元'
  if (decPart === '00') {
    s += '整'
  } else {
    for (let i = 0; i < 2; i++) {
      const n = +decPart[i]
      if (n === 0) {
        if (i === 0 && decPart[1] !== '0') s += '零'
        continue
      }
      s += digit[n] + decUnit[i]
    }
  }
  return '人民币' + s
}

function openDialog() {
  form.category = '困难帮扶'
  form.reason = ''
  form.amount = 1000
  form.amountInWords = amountToChinese(1000)
  dialogVisible.value = true
}

function onAmountChange() {
  form.amountInWords = amountToChinese(form.amount)
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate((valid) => {
    if (!valid) return
    submitting.value = true
    store
      .add({
        category: form.category,
        reason: form.reason,
        amount: form.amount,
        amountInWords: form.amountInWords
      })
      .then(() => {
        dialogVisible.value = false
        ElMessage.success('慰问金申请已提交，等待项目工会审批')
      })
      .catch((err: any) => ElMessage.error(err?.message || '提交失败'))
      .finally(() => (submitting.value = false))
  })
}

// ---- 审批（行内，走真实工作流） ----
function approve(row: CondolenceRecord) {
  store
    .approve(row.id)
    .then(() => ElMessage.success(`已通过 ${row.applicant} 的${row.category}慰问金申请`))
    .catch((err: any) => ElMessage.error(err?.message || '审批失败'))
}
function reject(row: CondolenceRecord) {
  store
    .reject(row.id)
    .then(() => ElMessage.success(`已驳回 ${row.applicant} 的${row.category}慰问金申请`))
    .catch((err: any) => ElMessage.error(err?.message || '操作失败'))
}

// ---- 审批详情（拉取流程实例进度） ----
const detailVisible = ref(false)
const detailLoading = ref(false)
const current = ref<CondolenceRecord | null>(null)
const instance = ref<ProcessInstanceVo | null>(null)

async function openDetail(row: CondolenceRecord) {
  current.value = row
  instance.value = null
  detailVisible.value = true
  if (row.workflowInstanceId) {
    detailLoading.value = true
    try {
      instance.value = await getInstance(row.workflowInstanceId)
    } catch {
      instance.value = null
    } finally {
      detailLoading.value = false
    }
  }
}

function nodeStateText(state: string) {
  return (
    { DONE: '已完成', CURRENT: '进行中', PENDING: '待处理', SKIPPED: '已跳过' }[state] || state
  )
}

function formatTime(t: string | null) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => store.load())
</script>

<template>
  <div>
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">慰问事项总数</span>
          <div class="stat-value">{{ stats.total }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">待工会审批</span>
          <div class="stat-value">{{ stats.pending }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">已发放</span>
          <div class="stat-value">{{ stats.paid }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">已发放金额</span>
          <div class="stat-value">{{ stats.amount }} 元</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openDialog">新建慰问金申请</el-button>
        <span class="imported-at">提交后走「项目工会 → 二级单位工会」两级审批，全部通过后自动记为已发放</span>
      </div>
      <el-table :data="store.list" border stripe size="small">
        <el-table-column prop="appliedAt" label="申请时间" width="140" />
        <el-table-column prop="applicant" label="申请人" width="90" />
        <el-table-column prop="project" label="项目部" width="130" />
        <el-table-column prop="category" label="类别" width="100" />
        <el-table-column prop="reason" label="情况说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="慰问金额" width="160">
          <template #default="{ row }">
            {{ row.amount }} 元（{{ row.amountInWords }}）
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="(statusTag[row.status] || 'info')" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button size="small" link @click="openDetail(row)">审批详情</el-button>
            <template v-if="row.status === '待审批'">
              <el-button type="primary" size="small" link @click="approve(row)">工会审批通过</el-button>
              <el-button type="danger" size="small" link @click="reject(row)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建慰问金申请" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="申请人">
          <span>{{ user.name }}（{{ user.project }}）</span>
        </el-form-item>
        <el-form-item label="慰问类别" prop="category">
          <el-select v-model="form.category" placeholder="请选择">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="慰问金额" prop="amount">
          <el-input-number v-model="form.amount" :min="1" :step="100" @change="onAmountChange" />
        </el-form-item>
        <el-form-item label="金额大写">
          <span class="in-words">{{ form.amountInWords || '—' }}</span>
        </el-form-item>
        <el-form-item label="情况说明" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="3" placeholder="请填写慰问事由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="慰问金审批发放表" width="600px" v-loading="detailLoading">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="申请人">{{ current.applicant }}</el-descriptions-item>
          <el-descriptions-item label="项目部">{{ current.project || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ current.category }}</el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ current.appliedAt }}</el-descriptions-item>
          <el-descriptions-item label="慰问金额" :span="2">{{ current.amount }} 元（{{ current.amountInWords }}）</el-descriptions-item>
          <el-descriptions-item label="情况说明" :span="2">{{ current.reason }}</el-descriptions-item>
          <el-descriptions-item label="项目工会意见" :span="2">{{ current.projectUnionOpinion || '待审批' }}</el-descriptions-item>
          <el-descriptions-item label="二级单位工会意见" :span="2">{{ current.companyUnionOpinion || '待审批' }}</el-descriptions-item>
          <el-descriptions-item label="状态" :span="2">
            <el-tag :type="(statusTag[current.status] || 'info')" size="small">{{ current.status }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="flow-tip">审批闭环：申请人填报 → 项目工会意见 → 二级单位工会意见 → 领款人签字 → 归档</div>

        <div v-if="instance" class="section-title">流转进度</div>
        <el-timeline v-if="instance" class="flow-timeline">
          <el-timeline-item
            v-for="n in instance.nodes"
            :key="n.seq"
            :type="n.state === 'DONE' ? 'success' : n.state === 'CURRENT' ? 'primary' : 'info'"
            :hollow="n.state !== 'DONE'"
          >
            {{ n.name }} · {{ nodeStateText(n.state) }}
          </el-timeline-item>
        </el-timeline>
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
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.imported-at {
  font-size: 12px;
  color: var(--text-tertiary);
}

.in-words {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 600;
}

.flow-tip {
  margin-top: 16px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.section-title {
  margin: 18px 0 10px;
  font-size: 13px;
  font-weight: 650;
  color: var(--text-secondary);
}

.flow-timeline {
  padding-left: 4px;
}
</style>
