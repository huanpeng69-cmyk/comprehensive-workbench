<script setup lang="ts">
import { ref } from 'vue'
import { receptions } from '@/mock/data'
import type { Reception, ReceptionLevel } from '@/mock/types'

const list = ref<Reception[]>(receptions.map((r) => ({ ...r, details: [...r.details] })))

const levelTag: Record<ReceptionLevel, string> = {
  A: 'danger',
  B: 'warning',
  C: 'info'
}

const statusTag: Record<Reception['status'], string> = {
  方案待审: 'warning',
  已接待: '',
  清单已归档: 'success'
}

const DUTIES = ['车辆安排', '大厅迎接', '电梯控制', '礼品准备', '欢迎字幕', '住宿安排', '用餐安排', '宣传报道', '会议室服务', '着装要求', '其他事项']

const detailVisible = ref(false)
const current = ref<Reception | null>(null)

function money(v: number) {
  return v.toLocaleString('zh-CN')
}

function openDetail(row: Reception) {
  current.value = row
  detailVisible.value = true
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <span class="tip">公务接待实行 A/B/C 分类审批：A 类办公室主任审签，B 类办公室副主任审签，C 类承办部门负责人审签。</span>
    </div>
    <el-table :data="list" border stripe size="small">
      <el-table-column prop="date" label="接待日期" width="120" />
      <el-table-column label="接待事由" min-width="240">
        <template #default="{ row }">
          <a class="title-link" @click="openDetail(row)">{{ row.title }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="level" label="分类" width="70" align="center">
        <template #default="{ row }: { row: Reception }">
          <el-tag :type="levelTag[row.level]" size="small" effect="dark">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="性质" width="80" />
      <el-table-column prop="guests" label="来宾" width="160" />
      <el-table-column prop="host" label="陪同领导" width="100" />
      <el-table-column prop="department" label="承办部门" width="120" />
      <el-table-column label="接待金额" width="110">
        <template #default="{ row }">
          {{ row.amount ? money(row.amount) + ' 元' : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }: { row: Reception }">
          <el-tag :type="statusTag[row.status]" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailVisible" title="公务接待三联单" width="720px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="接待事由">{{ current.title }}</el-descriptions-item>
          <el-descriptions-item label="分类 / 性质">{{ current.level }} 类 · {{ current.category }}</el-descriptions-item>
          <el-descriptions-item label="来宾情况">{{ current.guests }}（{{ current.guestCount }} 人）</el-descriptions-item>
          <el-descriptions-item label="陪同领导">{{ current.host }}</el-descriptions-item>
          <el-descriptions-item label="接待日期">{{ current.date }}</el-descriptions-item>
          <el-descriptions-item label="承办部门">{{ current.department }}</el-descriptions-item>
          <el-descriptions-item label="财务凭证号">{{ current.voucherNo || '待登记' }}</el-descriptions-item>
          <el-descriptions-item label="接待金额">{{ current.amount ? money(current.amount) + ' 元' : '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">接待清单明细</div>
        <el-table :data="current.details" border size="small">
          <el-table-column prop="time" label="时间" width="90" />
          <el-table-column prop="item" label="项目" width="140" />
          <el-table-column prop="place" label="场所" min-width="180" />
          <el-table-column label="金额（元）" width="110">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
        </el-table>
        <div v-if="current.details.length" class="amount-total">
          合计：<b>{{ money(current.details.reduce((sum, d) => sum + d.amount, 0)) }}</b> 元
        </div>

        <div class="section-title">接待方案分工（11 项责任到部门）</div>
        <div class="duty-grid">
          <el-tag v-for="d in DUTIES" :key="d" type="info" effect="plain">{{ d }}</el-tag>
        </div>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--text-tertiary);
}

.title-link {
  color: var(--text-primary);
  cursor: pointer;
}

.title-link:hover {
  text-decoration: underline;
}

.section-title {
  margin: 18px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.amount-total {
  margin-top: 10px;
  text-align: right;
  font-size: 13px;
  color: var(--text-secondary);
}

.duty-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
