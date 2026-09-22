<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  approveTask,
  getInstance,
  listMyInstances,
  listMyTodos,
  rejectTask,
  transmitTask,
  type InstanceSummaryVo,
  type ProcessInstanceVo,
  type TaskVo
} from '@/api/workflow'
import TaskCard from '@/components/workflow/TaskCard.vue'
import GlassModal from '@/components/glass/GlassModal.vue'
import GlassButton from '@/components/glass/GlassButton.vue'
import GlassTimeline from '@/components/glass/GlassTimeline.vue'

const activeTab = ref('todos')
const todos = ref<TaskVo[]>([])
const instances = ref<InstanceSummaryVo[]>([])
const loading = ref(false)
const keyword = ref('')
const bizFilter = ref('')

const BIZ_NAMES: Record<string, string> = {
  leave: '请假申请',
  payroll: '工资发放',
  reception: '公务接待',
  seal: '公章使用',
  notice: '通知公告'
}

const INSTANCE_STATUS: Record<string, { label: string; type: 'primary' | 'success' | 'danger' | 'warning' }> = {
  RUNNING: { label: '进行中', type: 'primary' },
  COMPLETED: { label: '已完成', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  TERMINATED: { label: '已终止', type: 'warning' }
}

const filteredTodos = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return todos.value.filter((t) => {
    const matchKw = !kw || t.title.toLowerCase().includes(kw) || t.initiatorName.toLowerCase().includes(kw)
    const matchBiz = !bizFilter.value || t.bizType === bizFilter.value
    return matchKw && matchBiz
  })
})

async function load() {
  loading.value = true
  try {
    const [todoList, instanceList] = await Promise.all([listMyTodos(), listMyInstances()])
    todos.value = todoList
    instances.value = instanceList
  } catch (e) {
    ElMessage.error('加载待办失败')
  } finally {
    loading.value = false
  }
}

function formatTime(t: string | null) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

// 审批弹窗
const approveVisible = ref(false)
const approving = ref(false)
const detail = ref<ProcessInstanceVo | null>(null)
const comment = ref('')
const currentTask = ref<TaskVo | null>(null)

async function openApprove(task: TaskVo) {
  currentTask.value = task
  comment.value = ''
  try {
    detail.value = await getInstance(task.instanceId)
    approveVisible.value = true
  } catch (e) {
    ElMessage.error('加载流程详情失败')
  }
}

function gotoTransmit() {
  approveVisible.value = false
  transmitVisible.value = true
}

async function doApprove() {
  if (!currentTask.value) return
  approving.value = true
  try {
    await approveTask(currentTask.value.taskId, comment.value)
    ElMessage.success('已通过')
    approveVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '操作失败')
  } finally {
    approving.value = false
  }
}

async function doReject() {
  if (!currentTask.value) return
  approving.value = true
  try {
    await rejectTask(currentTask.value.taskId, comment.value)
    ElMessage.success('已驳回')
    approveVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '操作失败')
  } finally {
    approving.value = false
  }
}

// 转办弹窗
const transmitVisible = ref(false)
const transmitTo = ref('')

async function openTransmit(task: TaskVo) {
  currentTask.value = task
  transmitTo.value = ''
  comment.value = ''
  transmitVisible.value = true
}

async function doTransmit() {
  if (!currentTask.value || !transmitTo.value.trim()) {
    ElMessage.warning('请填写转办对象')
    return
  }
  try {
    await transmitTask(currentTask.value.taskId, transmitTo.value.trim(), comment.value)
    ElMessage.success(`已转办给 ${transmitTo.value}`)
    transmitVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '转办失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="todo-page" v-loading="loading">
    <div class="page-head">
      <span class="page-title">我的待办</span>
      <span class="page-sub">需要你处理的工作，都在这里。</span>
    </div>

    <div class="filterbar glass glass-level-3">
      <el-input
        v-model="keyword"
        placeholder="搜索待办标题或发起人…"
        clearable
        class="filter-search"
      />
      <el-select v-model="bizFilter" placeholder="流程类型" clearable class="filter-biz">
        <el-option v-for="(label, key) in BIZ_NAMES" :key="key" :label="label" :value="key" />
      </el-select>
      <div class="filter-tabs">
        <button
          class="ftab"
          :class="{ active: activeTab === 'todos' }"
          @click="activeTab = 'todos'"
        >我的待办</button>
        <button
          class="ftab"
          :class="{ active: activeTab === 'mine' }"
          @click="activeTab = 'mine'"
        >我的流程</button>
      </div>
    </div>

    <div v-if="activeTab === 'todos'">
      <div v-if="filteredTodos.length" class="task-grid">
        <TaskCard
          v-for="t in filteredTodos"
          :key="t.taskId"
          :task="t"
          @approve="openApprove"
          @transmit="openTransmit"
          @view="openApprove"
        />
      </div>
      <div v-else class="glass glass-level-2 glass-empty">
        <div class="glass-empty-title">没有待办</div>
        <div class="glass-empty-desc">当前没有需要你处理的工作。</div>
      </div>
    </div>

    <div v-else>
      <div v-if="instances.length" class="instance-list">
        <div
          v-for="ins in instances"
          :key="ins.id"
          class="glass glass-level-3 glass-hover instance-card"
          :class="{
            'glass-tint-green': ins.status === 'COMPLETED',
            'glass-tint-red': ins.status === 'REJECTED'
          }"
        >
          <div class="instance-top">
            <span class="instance-biz">{{ BIZ_NAMES[ins.bizType] || ins.bizType }}</span>
            <span class="instance-title" :title="ins.title">{{ ins.title }}</span>
            <el-tag
              :type="(INSTANCE_STATUS[ins.status] || { type: 'info' }).type"
              size="small"
              effect="plain"
            >{{ INSTANCE_STATUS[ins.status]?.label || ins.status }}</el-tag>
          </div>
          <div class="instance-meta">
            <span>{{ ins.currentNodeName || '—' }}</span>
            <span class="dot">·</span>
            <span>{{ formatTime(ins.createdAt) }}</span>
          </div>
        </div>
      </div>
      <div v-else class="glass glass-level-2 glass-empty">
        <div class="glass-empty-title">暂无流程</div>
        <div class="glass-empty-desc">你发起的流程会显示在这里。</div>
      </div>
    </div>

    <!-- 审批弹窗 -->
    <GlassModal v-model="approveVisible" title="审批" width="660px" :close-on-overlay="false">
      <template v-if="detail">
        <div class="detail-meta">
          <div class="detail-row"><span class="dk">流程</span><span>{{ detail.title }}</span></div>
          <div class="detail-row"><span class="dk">发起人</span><span>{{ detail.initiatorName }}</span></div>
          <div class="detail-row"><span class="dk">项目部</span><span>{{ detail.projectName || '—' }}</span></div>
          <div class="detail-row">
            <span class="dk">状态</span>
            <el-tag :type="(INSTANCE_STATUS[detail.status] || { type: 'info' }).type" size="small" effect="plain">
              {{ INSTANCE_STATUS[detail.status]?.label || detail.status }}
            </el-tag>
          </div>
        </div>

        <div class="section-title">流转进度</div>
        <GlassTimeline :nodes="detail.nodes" />

        <div class="section-title">审批意见</div>
        <el-input v-model="comment" type="textarea" :rows="2" placeholder="填写审批意见（可选）" />
      </template>
      <template #footer>
        <GlassButton variant="ghost" @click="approveVisible = false">取消</GlassButton>
        <GlassButton variant="ghost" :loading="approving" @click="gotoTransmit">转办</GlassButton>
        <GlassButton variant="danger" :loading="approving" @click="doReject">驳回</GlassButton>
        <GlassButton variant="primary" :loading="approving" @click="doApprove">同意</GlassButton>
      </template>
    </GlassModal>

    <!-- 转办弹窗 -->
    <GlassModal v-model="transmitVisible" title="转办" width="460px" :close-on-overlay="false">
      <el-form label-position="top">
        <el-form-item label="转办给">
          <el-input v-model="transmitTo" placeholder="请输入用户名，如 limingyuan" />
        </el-form-item>
        <el-form-item label="转办说明">
          <el-input v-model="comment" type="textarea" :rows="2" placeholder="请说明转办原因（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <GlassButton variant="ghost" @click="transmitVisible = false">取消</GlassButton>
        <GlassButton variant="primary" @click="doTransmit">确认转办</GlassButton>
      </template>
    </GlassModal>
  </div>
</template>

<style scoped>
.todo-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-head {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.page-sub {
  font-size: 12.5px;
  color: var(--text-tertiary);
}

.filterbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}
.filter-search {
  width: 260px;
  flex-shrink: 0;
}
.filter-biz {
  width: 150px;
  flex-shrink: 0;
}
.filter-tabs {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
.ftab {
  height: 32px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: rgba(255, 253, 248, 0.32);
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.ftab:hover {
  color: var(--text-primary);
  background: rgba(255, 253, 248, 0.5);
}
.ftab.active {
  background: linear-gradient(135deg, rgba(233, 190, 110, 0.34), rgba(201, 135, 50, 0.16));
  border-color: rgba(217, 164, 65, 0.55);
  color: #F3D9A4;
  box-shadow: 0 0 0 1px rgba(217, 164, 65, 0.18), 0 4px 12px -8px rgba(201, 135, 50, 0.6);
}

.task-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}

.instance-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
.instance-card {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.instance-top {
  display: flex;
  align-items: center;
  gap: 9px;
}
.instance-biz {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 7px;
  background: rgba(217, 164, 65, 0.16);
  border: 1px solid rgba(201, 135, 50, 0.32);
  color: var(--text-gold);
}
.instance-title {
  flex: 1;
  min-width: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.instance-meta {
  font-size: 12px;
  color: var(--text-tertiary);
  display: flex;
  gap: 6px;
  align-items: center;
}
.instance-meta .dot {
  opacity: 0.5;
}

.detail-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 20px;
}
.detail-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-primary);
}
.dk {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-tertiary);
}

.section-title {
  margin: 18px 0 10px;
  font-size: 13px;
  font-weight: 650;
  color: var(--text-secondary);
}

@media (max-width: 900px) {
  .task-grid,
  .instance-list {
    grid-template-columns: 1fr;
  }
  .filterbar {
    flex-wrap: wrap;
  }
  .filter-tabs {
    margin-left: 0;
  }
}
@media (max-width: 520px) {
  .filter-search,
  .filter-biz {
    width: 100%;
  }
  .detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
