<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAuditLogs, type AuditLogItem, type AuditResult } from '@/api/audit'
import { useUserStore } from '@/stores/user'
import MemoryCenter from '@/views/ai/MemoryCenter.vue'

/**
 * 治理中心（ADR-M012，方案 C）。
 *
 * <p>两个 Tab 合并在同一页，但<b>授权边界完全不同</b>，不得混为一谈：</p>
 * <ul>
 *   <li><b>审计日志</b>：仅 SYSTEM_ADMIN。前端隐藏只是体验，真正的闸门在后端
 *       {@code AuditLogController} 的类级 {@code @PreAuthorize}。</li>
 *   <li><b>我的记忆</b>：任何登录用户都能看，但只能看自己的——由后端按
 *       {@code (companyId, username)} 强隔离，前端传什么参数都越不过去。</li>
 * </ul>
 * 记忆 Tab 直接复用 {@link MemoryCenter} 组件，不复制一份逻辑：
 * 复制会立刻出现两套实现分叉。
 */
const user = useUserStore()
const isSystemAdmin = user.roles.includes('SYSTEM_ADMIN')

const tab = ref(isSystemAdmin ? 'audit' : 'memory')

/* ---------- 审计日志 ---------- */
const rows = ref<AuditLogItem[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  username: '',
  action: '',
  result: '' as AuditResult | '',
  traceId: '',
  from: '',
  to: '',
  page: 1,
  size: 20
})

const RESULT_OPTIONS: { value: AuditResult; label: string }[] = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'DENIED', label: '被拒绝' },
  { value: 'FAILURE', label: '失败' }
]

async function loadAudit() {
  loading.value = true
  try {
    const page = await listAuditLogs(query)
    rows.value = page.items
    total.value = page.total
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '加载审计日志失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  loadAudit()
}

function reset() {
  query.username = ''
  query.action = ''
  query.result = ''
  query.traceId = ''
  query.from = ''
  query.to = ''
  query.page = 1
  loadAudit()
}

onMounted(() => {
  if (isSystemAdmin) {
    loadAudit()
  }
})

/** 结果显示：历史记录 result 为 null，显示「未标注」而不是空白——空白会被误读成「没发生过」。 */
function resultText(value: AuditResult | null): string {
  if (!value) return '未标注'
  const found = RESULT_OPTIONS.find(option => option.value === value)
  return found ? found.label : value
}

function resultTagType(value: AuditResult | null): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'SUCCESS') return 'success'
  if (value === 'DENIED') return 'warning'
  if (value === 'FAILURE') return 'danger'
  return 'info'
}

/** 后端 LocalDateTime 形如 2026-11-05T10:14:14.200925，页面只显示到秒。 */
function formatTime(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

/** 一键复制链路 id：这是本页最有价值的操作，截图报障时可直接贴给后端去 grep 日志。 */
async function copyTrace(traceId: string | null) {
  if (!traceId) return
  try {
    await navigator.clipboard.writeText(traceId)
    ElMessage.success('链路 id 已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中')
  }
}

function copyRow(row: AuditLogItem) {
  copyTrace(row.traceId)
}
</script>

<template>
  <div class="audit-center">
    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane v-if="isSystemAdmin" label="审计日志" name="audit">
        <div class="hint">
          全单位操作留痕。历史记录（治理底座改造前）结果与链路 id 显示为「未标注」。
        </div>

        <div class="filters">
          <el-input v-model="query.username" placeholder="操作人" clearable style="width: 140px" />
          <el-input v-model="query.action" placeholder="动作，如 LEAVE_CREATE" clearable
              style="width: 200px" />
          <el-select v-model="query.result" placeholder="结果" clearable style="width: 120px">
            <el-option v-for="option in RESULT_OPTIONS" :key="option.value"
                :label="option.label" :value="option.value" />
          </el-select>
          <el-input v-model="query.traceId" placeholder="链路 id" clearable style="width: 200px" />
          <el-date-picker v-model="query.from" type="datetime" placeholder="起始时间"
              value-format="YYYY-MM-DDTHH:mm:ss" style="width: 190px" />
          <el-date-picker v-model="query.to" type="datetime" placeholder="截止时间"
              value-format="YYYY-MM-DDTHH:mm:ss" style="width: 190px" />
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>

        <el-table :data="rows" v-loading="loading" style="width: 100%">
          <el-table-column prop="createdAt" label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="username" label="操作人" width="110" />
          <el-table-column prop="action" label="动作" width="170" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="resultTagType(row.result)">{{ resultText(row.result) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对象" width="160">
            <template #default="{ row }">
              {{ row.targetType || '—' }}{{ row.targetId ? ` #${row.targetId}` : '' }}
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="详情" show-overflow-tooltip />
          <el-table-column label="链路 id" width="220">
            <template #default="{ row }">
              <el-button v-if="row.traceId" link type="primary" class="trace-id"
                  @click="copyRow(row)">{{ row.traceId }}</el-button>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination class="pager" background layout="prev, pager, next, total"
            :page-size="query.size" :current-page="query.page" :total="total"
            @current-change="(p: number) => { query.page = p; loadAudit() }" />
      </el-tab-pane>

      <el-tab-pane label="我的记忆" name="memory">
        <!-- 复用现有页面组件：记忆的增删改与隔离口径只有一份实现。 -->
        <MemoryCenter />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.audit-center {
  padding: 4px 0;
}
.hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: #8a8f99;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.trace-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>