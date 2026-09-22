<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { condolences } from '@/mock/data'
import type { Condolence } from '@/mock/types'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const list = ref<Condolence[]>(condolences.map((c) => ({ ...c })))

const stats = computed(() => ({
  total: list.value.length,
  pending: list.value.filter((c) => c.status === '二级单位工会审批中').length,
  paid: list.value.filter((c) => c.status === '已发放').length,
  amount: list.value.filter((c) => c.status === '已发放').reduce((s, c) => s + c.amount, 0)
}))

function statusTag(status: string) {
  return status === '已发放' ? 'success' : 'warning'
}

const detailVisible = ref(false)
const current = ref<Condolence | null>(null)

function openDetail(row: Condolence) {
  current.value = row
  detailVisible.value = true
}

function approve(row: Condolence) {
  row.companyUnionOpinion = '同意'
  row.status = '已发放'
  ElMessage.success(`${row.applicant} 的${row.category}已发放（${row.amountInWords}）`)
}
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
          <span class="stat-label">待二级工会审批</span>
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
      <el-table :data="list" border stripe size="small">
        <el-table-column prop="appliedAt" label="申请时间" width="120" />
        <el-table-column prop="applicant" label="申请人" width="90" />
        <el-table-column prop="category" label="类别" width="100" />
        <el-table-column prop="reason" label="情况说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="慰问金额" width="140">
          <template #default="{ row }">
            {{ row.amount }} 元（{{ row.amountInWords }}）
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button size="small" link @click="openDetail(row)">审批详情</el-button>
            <el-button
              v-if="row.status === '二级单位工会审批中'"
              type="primary"
              size="small"
              link
              @click="approve(row)"
            >
              二级工会审批
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="慰问金审批发放表" width="560px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="申请单位/部门">{{ user.project }}</el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ current.appliedAt }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ current.applicant }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ current.category }}</el-descriptions-item>
          <el-descriptions-item label="慰问金额" :span="2">{{ current.amount }} 元（{{ current.amountInWords }}）</el-descriptions-item>
          <el-descriptions-item label="情况说明" :span="2">{{ current.reason }}</el-descriptions-item>
          <el-descriptions-item label="项目工会意见" :span="2">{{ current.projectUnionOpinion || '—' }}</el-descriptions-item>
          <el-descriptions-item label="二级单位工会意见" :span="2">{{ current.companyUnionOpinion || '待审批' }}</el-descriptions-item>
        </el-descriptions>
        <div class="flow-tip">
          审批闭环：申请人填报 → 项目工会意见 → 二级单位工会意见 → 申请人（领款人）签字 → 归档
        </div>
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

.flow-tip {
  margin-top: 16px;
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
