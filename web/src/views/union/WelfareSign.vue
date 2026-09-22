<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { welfareItems } from '@/mock/data'
import type { WelfareItem } from '@/mock/types'

const list = ref<WelfareItem[]>(welfareItems.map((w) => ({ ...w })))
const activeCategory = ref('全部')

const categories = computed(() => ['全部', ...new Set(welfareItems.map((w) => w.category))])

const filtered = computed(() =>
  activeCategory.value === '全部' ? list.value : list.value.filter((w) => w.category === activeCategory.value)
)

const signStats = computed(() => {
  const rows = activeCategory.value === '全部' ? list.value : list.value.filter((w) => w.category === activeCategory.value)
  const signed = rows.filter((w) => w.status === '已领取').length
  return { signed, total: rows.length, rate: rows.length === 0 ? 0 : Math.round((signed / rows.length) * 100) }
})

function nowStr() {
  const d = new Date()
  const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return `${date} ${time}`
}

function sign(row: WelfareItem) {
  row.status = '已领取'
  row.signedAt = nowStr()
  ElMessage.success(`${row.recipient} 已签收 ${row.name}（${row.category}）`)
}
</script>

<template>
  <div>
    <el-card shadow="never" class="stat-card">
      <div class="sign-summary">
        <span>签收进度：<b>{{ signStats.signed }} / {{ signStats.total }}</b> 人</span>
        <el-progress :percentage="signStats.rate" style="width: 240px" />
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <div class="toolbar">
        <el-radio-group v-model="activeCategory" size="small">
          <el-radio-button v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-radio-group>
      </div>
      <el-table :data="filtered" border stripe size="small">
        <el-table-column prop="category" label="福利品类" width="150" />
        <el-table-column prop="name" label="物品名称" width="180" />
        <el-table-column prop="spec" label="规格" width="120" />
        <el-table-column prop="quantity" label="数量" width="70" />
        <el-table-column prop="recipient" label="领取人" width="100" />
        <el-table-column prop="department" label="部门" width="120" />
        <el-table-column prop="status" label="领取状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === '已领取' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="signedAt" label="签收时间" width="160" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button
              v-if="row.status === '未领取'"
              type="primary"
              size="small"
              link
              @click="sign(row)"
            >
              确认签收
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stat-card {
  margin-bottom: 12px;
}

.sign-summary {
  display: flex;
  align-items: center;
  gap: 24px;
  font-size: 14px;
  color: var(--text-primary);
}
</style>
