<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { TableColumnCtx } from 'element-plus'
import { partyFees } from '@/mock/data'
import type { PartyFeeRecord } from '@/mock/types'

const QUARTERS = ['2026-Q3']
const quarter = ref('2026-Q3')

const list = ref<PartyFeeRecord[]>(partyFees.map((f) => ({ ...f })))

const filtered = computed(() => list.value.filter((f) => f.quarter === quarter.value))

const summary = computed(() => {
  const rows = filtered.value
  return {
    base: rows.reduce((s, f) => s + f.base1 + f.base2 + f.base3, 0),
    fee: rows.reduce((s, f) => s + f.total, 0),
    count: rows.length
  }
})

interface SummaryParam {
  columns: TableColumnCtx<PartyFeeRecord>[]
  data: PartyFeeRecord[]
}

function getSummaries(param: SummaryParam) {
  const { columns, data } = param
  const sums: (string | number)[] = []
  columns.forEach((column, index) => {
    if (index === 0) {
      sums[index] = '合计'
      return
    }
    const key = column.property as keyof PartyFeeRecord | undefined
    if (!key || typeof data[0][key] !== 'number') {
      sums[index] = ''
      return
    }
    sums[index] = data.reduce((sum, row) => sum + (row[key] as number), 0).toFixed(2)
  })
  return sums
}

function money(v: number) {
  return v.toFixed(2)
}

function onExport() {
  ElMessage.success(`已生成 ${quarter.value} 党费收缴明细导出任务，完成后可在导出记录中下载`)
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-select v-model="quarter" style="width: 140px">
        <el-option v-for="q in QUARTERS" :key="q" :label="q + ' 党费明细'" :value="q" />
      </el-select>
      <el-button @click="onExport">导出季度明细</el-button>
      <span class="tip">党费按季度缴纳，绩效党费封顶规则：绩效工资超过 1000 元按 1000 元计算。</span>
    </div>

    <el-table :data="filtered" border stripe size="small" show-summary :summary-method="getSummaries">
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column prop="base1" label="7 月缴费基数" width="120">
        <template #default="{ row }">{{ money(row.base1) }}</template>
      </el-table-column>
      <el-table-column prop="fee1" label="7 月党费" width="100">
        <template #default="{ row }">{{ money(row.fee1) }}</template>
      </el-table-column>
      <el-table-column prop="base2" label="8 月缴费基数" width="120">
        <template #default="{ row }">{{ money(row.base2) }}</template>
      </el-table-column>
      <el-table-column prop="fee2" label="8 月党费" width="100">
        <template #default="{ row }">{{ money(row.fee2) }}</template>
      </el-table-column>
      <el-table-column prop="base3" label="9 月缴费基数" width="120">
        <template #default="{ row }">{{ money(row.base3) }}</template>
      </el-table-column>
      <el-table-column prop="fee3" label="9 月党费" width="100">
        <template #default="{ row }">{{ money(row.fee3) }}</template>
      </el-table-column>
      <el-table-column prop="total" label="季度合计" width="110">
        <template #default="{ row }">
          <b>{{ money(row.total) }}</b>
        </template>
      </el-table-column>
    </el-table>

    <div class="summary-bar">
      <span>本季度应缴人数：<b>{{ summary.count }}</b> 人</span>
      <span>缴费基数合计：<b>{{ money(summary.base) }}</b> 元</span>
      <span>党费合计：<b>{{ money(summary.fee) }}</b> 元</span>
    </div>
  </el-card>
</template>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--text-tertiary);
}

.summary-bar {
  margin-top: 14px;
  display: flex;
  gap: 40px;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
