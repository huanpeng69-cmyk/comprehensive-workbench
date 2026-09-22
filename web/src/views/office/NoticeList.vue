<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notices } from '@/mock/data'
import type { Notice } from '@/mock/types'

const list = ref<Notice[]>(notices.map((n) => ({ ...n })))
const filters = reactive({ category: '', keyword: '' })

const categories = computed(() => [...new Set(notices.map((n) => n.category))])

const filtered = computed(() => {
  const kw = filters.keyword.trim().toLowerCase()
  return list.value.filter(
    (n) => (!filters.category || n.category === filters.category) && (!kw || n.title.toLowerCase().includes(kw))
  )
})

const drawerVisible = ref(false)
const current = ref<Notice | null>(null)

function openDetail(row: Notice) {
  current.value = row
  drawerVisible.value = true
}

function markRead(row: Notice) {
  if (row.readCount < row.totalCount) {
    row.readCount += 1
    ElMessage.success('已登记阅读回执')
  } else {
    ElMessage.info('阅读回执已满员')
  }
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-input v-model="filters.keyword" placeholder="搜索公告标题" clearable style="width: 220px" />
      <el-select v-model="filters.category" placeholder="全部类别" clearable style="width: 140px">
        <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
      </el-select>
    </div>
    <el-table :data="filtered" border stripe size="small" @row-click="openDetail">
      <el-table-column label="标题" min-width="340">
        <template #default="{ row }">
          <el-tag v-if="row.urgent" type="danger" size="small" effect="plain">紧急</el-tag>
          <a class="notice-title" @click.stop="openDetail(row)">{{ row.title }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="100" />
      <el-table-column prop="scope" label="发布范围" width="160" />
      <el-table-column prop="publishedAt" label="发布时间" width="140" />
      <el-table-column label="阅读情况" width="180">
        <template #default="{ row }">
          <el-progress
            :percentage="Math.round((row.readCount / row.totalCount) * 100)"
            :format="() => `${row.readCount}/${row.totalCount}`"
          />
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="公告详情" size="480px">
      <template v-if="current">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="标题">{{ current.title }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ current.category }}</el-descriptions-item>
          <el-descriptions-item label="发布范围">{{ current.scope }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ current.publishedAt }}</el-descriptions-item>
          <el-descriptions-item label="紧急程度">
            <el-tag v-if="current.urgent" type="danger" size="small">紧急</el-tag>
            <span v-else>普通</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="notice-body">
          正文内容此处为示例占位。正式环境中，公告正文由富文本编辑器发布，支持附件与阅读回执留痕。
        </div>
        <div class="drawer-footer">
          <el-button type="primary" @click="markRead(current)">确认已读</el-button>
        </div>
      </template>
    </el-drawer>
  </el-card>
</template>

<style scoped>
.notice-title {
  color: var(--text-primary);
  cursor: pointer;
  margin-left: 6px;
}

.notice-title:hover {
  text-decoration: underline;
}

.notice-body {
  margin-top: 16px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.8;
  padding: 12px;
  background-color: transparent;
  border-radius: 4px;
}

.drawer-footer {
  margin-top: 20px;
}
</style>
