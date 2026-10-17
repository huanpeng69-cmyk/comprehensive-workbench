<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import {
  listMemories, createMemory, updateMemory, deleteMemory, recallMemories,
  MEMORY_TYPE_OPTIONS,
  type MemoryItem, type MemoryType, type MemoryRecallResult
} from '@/api/memory'

/* ---------- 列表 ---------- */
const memories = ref<MemoryItem[]>([])
const filterType = ref<MemoryType | ''>('')
const loading = ref(false)

const visibleMemories = computed(() =>
  filterType.value ? memories.value.filter(m => m.type === filterType.value) : memories.value
)

const stats = computed(() => ({
  total: memories.value.length,
  expired: memories.value.filter(m => m.expired).length,
  semantic: memories.value.filter(m => m.type === 'SEMANTIC').length,
  procedural: memories.value.filter(m => m.type === 'PROCEDURAL').length,
  episodic: memories.value.filter(m => m.type === 'EPISODIC').length
}))

async function load() {
  loading.value = true
  try {
    memories.value = await listMemories()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '加载记忆失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)

function typeTagType(type: MemoryType): '' | 'success' | 'warning' | 'info' {
  if (type === 'SEMANTIC') return 'success'
  if (type === 'PROCEDURAL') return 'warning'
  return 'info'
}

/** 后端 LocalDateTime 序列化成「2026-10-15T10:30:00」，页面只显示到分钟。 */
function formatTime(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

/* ---------- 新增 / 编辑 ---------- */
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const formRef = ref<FormInstance>()
const editing = ref<MemoryItem | null>(null)
const form = reactive({
  content: '',
  type: 'SEMANTIC' as MemoryType,
  importance: 50,
  // null 的含义随新增/编辑而变：新增 = 永不过期；编辑 = 保持原有有效期不变。
  ttlDays: null as number | null
})

const rules = {
  content: [
    { required: true, message: '请输入记忆内容', trigger: 'blur' },
    { max: 1000, message: '记忆内容不能超过 1000 个字符', trigger: 'blur' }
  ]
}

const typeDescription = computed(
  () => MEMORY_TYPE_OPTIONS.find(o => o.value === form.type)?.description || ''
)

const ttlTip = computed(() => {
  if (dialogMode.value === 'add') return '留空 = 永不过期；N = N 天后过期。'
  const current = editing.value?.expiresAt
  return `留空 = 保持当前有效期（${current ? formatTime(current) : '永不过期'}）；0 = 改为永不过期；N = 从现在起 N 天。`
})

function blankForm() {
  Object.assign(form, { content: '', type: 'SEMANTIC', importance: 50, ttlDays: null })
}

function openAdd() {
  blankForm()
  editing.value = null
  dialogMode.value = 'add'
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}

function openEdit(row: MemoryItem) {
  editing.value = row
  Object.assign(form, {
    content: row.content,
    type: row.type,
    importance: row.importance,
    ttlDays: null
  })
  dialogMode.value = 'edit'
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}

function onTtlChange(value: number | null | undefined) {
  form.ttlDays = value === null || value === undefined ? null : value
}

function onSave() {
  formRef.value?.validate((valid: boolean) => {
    if (!valid) return
    const req = dialogMode.value === 'add'
      ? createMemory({
        content: form.content.trim(),
        type: form.type,
        importance: form.importance,
        ttlDays: form.ttlDays == null ? undefined : form.ttlDays
      })
      : updateMemory(editing.value?.id as number, {
        content: form.content.trim(),
        type: form.type,
        importance: form.importance,
        // 编辑语义：null = 保持原有效期，0 = 永不过期，N = 从现在起 N 天。
        ttlDays: form.ttlDays
      })
    req.then(() => {
      dialogVisible.value = false
      ElMessage.success(dialogMode.value === 'add' ? '已保存这条记忆' : '已保存修改')
      load()
    }).catch((err: unknown) => {
      ElMessage.error(err instanceof Error ? err.message : '保存失败')
    })
  })
}

/* ---------- 删除 ---------- */
function onDelete(row: MemoryItem) {
  ElMessageBox.confirm(
    `确认删除这条${row.typeLabel}？删除后 AI 将不再记得它，且此操作不可恢复。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => deleteMemory(row.id))
    .then(() => { ElMessage.success('已删除'); load() })
    .catch((err: unknown) => { if (err instanceof Error) ElMessage.error(err.message) })
}

/* ---------- 召回预览 ---------- */
const recallForm = reactive({
  query: '',
  topK: null as number | null,
  tokenBudget: null as number | null
})
const recalling = ref(false)
const recallResult = ref<MemoryRecallResult | null>(null)

function onTopKChange(value: number | null | undefined) {
  recallForm.topK = value === null || value === undefined ? null : value
}
function onBudgetChange(value: number | null | undefined) {
  recallForm.tokenBudget = value === null || value === undefined ? null : value
}

async function onRecall() {
  recalling.value = true
  try {
    recallResult.value = await recallMemories({
      query: recallForm.query.trim() || undefined,
      topK: recallForm.topK ?? undefined,
      tokenBudget: recallForm.tokenBudget ?? undefined
    })
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '召回预览失败')
  } finally {
    recalling.value = false
  }
}
</script>

<template>
  <div>
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">记忆总数</span>
          <div class="stat-value">{{ stats.total }} 条</div>
          <span v-if="stats.expired" class="stat-sub">其中已过期 {{ stats.expired }} 条</span>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">语义记忆</span>
          <div class="stat-value">{{ stats.semantic }} 条</div>
          <span class="stat-sub">事实与偏好</span>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">程序记忆</span>
          <div class="stat-value">{{ stats.procedural }} 条</div>
          <span class="stat-sub">做法与格式要求</span>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <span class="stat-label">情景记忆</span>
          <div class="stat-value">{{ stats.episodic }} 条</div>
          <span class="stat-sub">过去的经历与结论</span>
        </el-card>
      </el-col>
    </el-row>

    <p class="page-hint">
      这里写下的记忆只属于你自己，AI 每次对话时会按当前话题挑最相关的几条拼进提示词。重要度越高、更新越近，越容易被选中。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openAdd">新增记忆</el-button>
        <div class="toolbar-spacer" />
        <el-select v-model="filterType" placeholder="全部类型" clearable style="width: 150px">
          <el-option v-for="o in MEMORY_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table :data="visibleMemories" border stripe size="small" v-loading="loading" style="width: 100%">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="typeTagType(row.type)" size="small">{{ row.typeLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="memory-content">{{ row.content }}</span>
          </template>
        </el-table-column>
        <el-table-column label="重要度" width="120" align="center">
          <template #default="{ row }">
            <span class="importance-num">{{ row.importance }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.source === 'MANUAL' ? 'info' : 'primary'" size="small">
              {{ row.sourceLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="140" align="center">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="有效期" width="160" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.expired" type="danger" size="small">已过期</el-tag>
            <span v-else>{{ row.expiresAt ? formatTime(row.expiresAt) : '永不过期' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="recall-card">
      <template #header>
        <div class="recall-header">
          <span>召回预览</span>
          <span class="muted">看看给定话题下，AI 这一轮会拿到哪几条记忆</span>
        </div>
      </template>
      <div class="toolbar">
        <el-input
          v-model="recallForm.query"
          placeholder="当前话题，例如：工伤处理流程 / 工资表格式"
          clearable
          style="width: 320px"
          @keyup.enter="onRecall"
        />
        <el-input-number
          :model-value="recallForm.topK ?? undefined"
          :min="1"
          :max="30"
          controls-position="right"
          placeholder="条数"
          @update:model-value="onTopKChange"
        />
        <el-input-number
          :model-value="recallForm.tokenBudget ?? undefined"
          :min="64"
          :max="2000"
          controls-position="right"
          placeholder="token 预算"
          @update:model-value="onBudgetChange"
        />
        <el-button type="primary" :loading="recalling" @click="onRecall">预览</el-button>
      </div>
      <p class="recall-tip">
        条数默认 8 条，token 预算默认 600；话题留空则退化为按「重要度 + 新鲜度」排序。
      </p>

      <div v-if="recallResult" class="recall-result">
        <div class="recall-tags">
          <el-tag size="small">命中 {{ recallResult.memories.length }} 条</el-tag>
          <el-tag size="small" type="info">约 {{ recallResult.estimatedTokens }} tokens</el-tag>
          <el-tag v-if="recallResult.omittedCount > 0" size="small" type="warning">
            另有 {{ recallResult.omittedCount }} 条因预算未展开
          </el-tag>
        </div>
        <pre v-if="recallResult.block" class="recall-block">{{ recallResult.block }}</pre>
        <el-empty v-else description="没有命中任何记忆" :image-size="60" />
        <ul v-if="recallResult.memories.length" class="recall-list">
          <li v-for="m in recallResult.memories" :key="m.id">
            <el-tag :type="typeTagType(m.type)" size="small">{{ m.typeLabel }}</el-tag>
            <span class="recall-importance">重要度 {{ m.importance }}</span>
            <span class="recall-content">{{ m.content }}</span>
          </li>
        </ul>
      </div>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? '新增记忆' : '编辑记忆'"
      width="640px"
      destroy-on-close
    >
      <el-form :model="form" :rules="rules" ref="formRef" label-width="96px">
        <el-form-item label="记忆内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="用一句话写清要 AI 长期记住的事，例如「工资表格一律按项目部汇总，保留两位小数」"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="o in MEMORY_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div class="form-tip">{{ typeDescription }}</div>
        </el-form-item>
        <el-form-item label="重要度">
          <div class="full-col">
            <el-slider v-model="form.importance" :min="0" :max="100" show-input :show-input-controls="false" />
            <div class="form-tip">越高越优先被召回，默认 50。</div>
          </div>
        </el-form-item>
        <el-form-item label="有效期">
          <div class="full-col">
            <el-input-number
              :model-value="form.ttlDays ?? undefined"
              :min="dialogMode === 'add' ? 1 : 0"
              :max="3650"
              controls-position="right"
              style="width: 100%"
              @update:model-value="onTtlChange"
            />
            <div class="form-tip">{{ ttlTip }}</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-row { margin-bottom: 12px; }
.stat-card { display: flex; flex-direction: column; gap: 4px; }
.stat-label { font-size: 13px; color: var(--text-tertiary); }
.stat-value { font-size: 22px; font-weight: 700; color: var(--text-primary); }
.stat-sub { font-size: 12px; color: var(--text-tertiary); }

.page-hint { margin: 0 0 12px; color: var(--text-secondary); font-size: 13px; }

.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }
.toolbar-spacer { flex: 1; }

.memory-content { color: var(--text-primary); }
.importance-num { font-variant-numeric: tabular-nums; color: var(--text-secondary); }

.recall-card { margin-top: 12px; }
.recall-header { display: flex; align-items: baseline; gap: 10px; font-weight: 600; }
.muted { font-weight: 400; font-size: 12px; color: var(--text-tertiary); }
.recall-tip { margin: 0 0 12px; font-size: 12px; color: var(--text-tertiary); }
.recall-result { display: flex; flex-direction: column; gap: 10px; }
.recall-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.recall-block {
  margin: 0;
  padding: 12px;
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--border-color, rgba(127, 127, 127, 0.3));
  border-radius: 8px;
  background: var(--fill-color, rgba(127, 127, 127, 0.06));
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.recall-list { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.recall-list li { display: flex; align-items: baseline; gap: 8px; font-size: 13px; }
.recall-importance { color: var(--text-tertiary); font-size: 12px; white-space: nowrap; }
.recall-content { color: var(--text-secondary); }

.full-col { width: 100%; }
.form-tip { margin-top: 4px; font-size: 12px; line-height: 1.5; color: var(--text-tertiary); }
</style>
