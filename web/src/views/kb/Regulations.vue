<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { regulations } from '@/mock/data'
import type { Regulation } from '@/mock/types'
import { getRegulationContent } from '@/api/kb'

const filters = reactive({ category: '', keyword: '' })

const categories = computed(() => [...new Set(regulations.map((r) => r.category))])

const filtered = computed(() => {
  const kw = filters.keyword.trim().toLowerCase()
  return regulations.filter(
    (r) => (!filters.category || r.category === filters.category) && (!kw || r.title.toLowerCase().includes(kw) || r.docNo.toLowerCase().includes(kw))
  )
})

const previewVisible = ref(false)
const previewTitle = ref('')
const previewText = ref('')
const previewLoading = ref(false)

async function openPreview(row: Regulation) {
  previewTitle.value = `${row.docNo} ${row.title}`
  previewVisible.value = true
  previewLoading.value = true
  previewText.value = ''
  try {
    previewText.value = await getRegulationContent(row.docNo)
  } catch {
    ElMessage.warning('正文暂未入库，可前往「制度文件库」目录查阅原件')
    previewText.value = '正文未入库。'
  } finally {
    previewLoading.value = false
  }
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-input v-model="filters.keyword" placeholder="搜索文号 / 标题" clearable style="width: 220px" />
      <el-select v-model="filters.category" placeholder="全部类别" clearable style="width: 140px">
        <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
      </el-select>
      <span class="tip">检索仅召回「是否最新 = 是」的现行有效制度，制度修订时旧版本保留并标记为已替代。</span>
    </div>
    <el-table :data="filtered" border stripe size="small">
      <el-table-column prop="docNo" label="文号" width="160" />
      <el-table-column label="标题" min-width="320">
        <template #default="{ row }">
          <a class="title-link" @click="openPreview(row)">{{ row.title }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="100" />
      <el-table-column prop="issuer" label="发布单位" width="220" />
      <el-table-column prop="date" label="发布日期" width="110" />
      <el-table-column label="现行状态" width="100">
        <template #default="{ row }">
          <el-tag type="success" size="small">{{ row.latest ? '现行有效' : '已替代' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="previewVisible" :title="previewTitle" width="760px" top="6vh">
      <div v-loading="previewLoading" class="preview-body">
        <pre class="preview-text">{{ previewText }}</pre>
      </div>
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

.preview-body {
  max-height: 62vh;
  overflow-y: auto;
  background-color: transparent;
  border-radius: 4px;
  padding: 4px 12px;
}

.preview-text {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-primary);
  margin: 0;
}
</style>
