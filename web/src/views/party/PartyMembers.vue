<script setup lang="ts">
import { computed, reactive } from 'vue'
import { partyMembers, PROJECTS } from '@/mock/data'
import type { PartyMember } from '@/mock/types'

const filters = reactive({ keyword: '', project: '', status: '' })

const filtered = computed<PartyMember[]>(() => {
  const kw = filters.keyword.trim().toLowerCase()
  return partyMembers.filter((m) => {
    const matchKw = !kw || m.name.toLowerCase().includes(kw) || m.org.toLowerCase().includes(kw)
    const matchProject = !filters.project || m.project === filters.project
    const matchStatus = !filters.status || m.status === filters.status
    return matchKw && matchProject && matchStatus
  })
})

const stats = computed(() => ({
  total: partyMembers.length,
  formal: partyMembers.filter((m) => m.status === '正式党员').length,
  probationary: partyMembers.filter((m) => m.status === '预备党员').length
}))

function onReset() {
  filters.keyword = ''
  filters.project = ''
  filters.status = ''
}
</script>

<template>
  <div>
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="24" :sm="8">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">党员总数</span>
          <div class="stat-value">{{ stats.total }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">正式党员</span>
          <div class="stat-value">{{ stats.formal }} 人</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">预备党员</span>
          <div class="stat-value">{{ stats.probationary }} 人</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="filters.keyword" placeholder="搜索姓名 / 党组织" clearable style="width: 200px" />
        <el-select v-model="filters.project" placeholder="全部项目部" clearable style="width: 160px">
          <el-option v-for="p in PROJECTS" :key="p" :label="p" :value="p" />
        </el-select>
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 120px">
          <el-option label="正式党员" value="正式党员" />
          <el-option label="预备党员" value="预备党员" />
        </el-select>
        <el-button @click="onReset">重置</el-button>
      </div>
      <el-table :data="filtered" border stripe size="small">
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="gender" label="性别" width="70" />
        <el-table-column prop="project" label="项目部" width="140" />
        <el-table-column prop="org" label="党组织" width="180" />
        <el-table-column prop="partyPost" label="党内职务" width="110" />
        <el-table-column prop="joinDate" label="入党日期" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === '正式党员' ? 'danger' : 'success'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
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
