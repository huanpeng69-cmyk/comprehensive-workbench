<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { useRouter } from 'vue-router'
import { notices, payrollBatches } from '@/mock/data'
import {
  listMyInstances,
  listMyTodos,
  type InstanceSummaryVo,
  type TaskVo
} from '@/api/workflow'
import { Bell, Money, Reading, Tickets } from '@element-plus/icons-vue'
import MetricCard from '@/components/dashboard/MetricCard.vue'
import TaskCard from '@/components/workflow/TaskCard.vue'

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

const todoList = ref<TaskVo[]>([])
const myInstances = ref<InstanceSummaryVo[]>([])

const router = useRouter()

const pendingPayrollCount = computed(() =>
  payrollBatches.filter((b) => b.status !== '已发布').length
)
const readRate = computed(() => {
  if (notices.length === 0) return 0
  const read = notices.reduce((sum, n) => sum + n.readCount, 0)
  const total = notices.reduce((sum, n) => sum + n.totalCount, 0)
  return total === 0 ? 0 : Math.round((read / total) * 100)
})
const runningCount = computed(() =>
  myInstances.value.filter((i) => i.status === 'RUNNING').length
)

const stats = computed(() => [
  { label: '待办事项', value: todoList.value.length, icon: Bell, accent: true },
  { label: '在途流程', value: runningCount.value, icon: Tickets },
  { label: '待审批工资批次', value: pendingPayrollCount.value, icon: Money },
  { label: '公告阅读率', value: readRate.value + '%', icon: Reading }
])

async function loadTodos() {
  try {
    const [todos, instances] = await Promise.all([listMyTodos(), listMyInstances()])
    todoList.value = todos
    myInstances.value = instances
  } catch {
    // 未登录或接口异常时保持空列表，不阻塞首页渲染
  }
}

function goApprove() {
  router.push('/workflow/todos')
}

onMounted(() => {
  loadTodos()
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)
  const latestPeriod = payrollBatches[0].period
  const rows = payrollBatches.filter((b) => b.period === latestPeriod)
  chart.setOption({
    title: {
      text: '各项目部 ' + latestPeriod + ' 工资应发合计（元）',
      left: 'center',
      textStyle: { fontSize: 13, fontWeight: 600, color: 'rgba(255,255,255,0.92)' }
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(40,34,24,0.72)',
      borderColor: 'rgba(217,164,65,0.45)',
      textStyle: { color: '#FFFDF8' }
    },
    grid: { top: 48, left: 52, right: 24, bottom: 32 },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.project.replace('项目部', '')),
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.32)' } },
      axisLabel: { color: 'rgba(255,255,255,0.72)' }
    },
    yAxis: {
      type: 'value',
      name: '元',
      nameTextStyle: { color: 'rgba(255,255,255,0.56)' },
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.24)' } },
      axisLabel: { color: 'rgba(255,255,255,0.72)' },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.14)' } }
    },
    series: [
      {
        name: '应发合计',
        type: 'bar',
        data: rows.map((r) => r.total),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#E9C07A' },
            { offset: 1, color: '#C98732' }
          ]),
          borderRadius: [6, 6, 0, 0]
        },
        label: { show: true, position: 'top', formatter: '{c}', color: 'rgba(255,255,255,0.88)' }
      }
    ]
  })
  window.addEventListener('resize', resizeChart)
})

function resizeChart() {
  chart?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="home">
    <div class="metric-row">
      <MetricCard
        v-for="s in stats"
        :key="s.label"
        :label="s.label"
        :value="s.value"
        :icon="s.icon"
        :accent="s.accent"
      />
    </div>

    <div class="cols">
      <div class="glass glass-level-2 panel chart-panel">
        <div ref="chartRef" class="chart"></div>
      </div>

      <div class="glass glass-level-2 panel notice-panel">
        <div class="panel-head">
          <span class="panel-title">最新公告</span>
        </div>
        <div v-for="n in notices.slice(0, 4)" :key="n.id" class="notice-item">
          <el-tag v-if="n.urgent" type="danger" size="small" effect="plain">紧急</el-tag>
          <span class="notice-title" :title="n.title">{{ n.title }}</span>
          <span class="notice-date">{{ n.publishedAt.slice(0, 10) }}</span>
        </div>
      </div>
    </div>

    <div class="glass glass-level-2 panel todo-panel">
      <div class="panel-head todo-head">
        <div>
          <span class="panel-title">我的待办</span>
          <span class="panel-sub">需要你处理的工作，都在这里。</span>
        </div>
        <button class="see-all" @click="goApprove">查看全部 →</button>
      </div>
      <div v-if="todoList.length" class="todo-grid">
        <TaskCard
          v-for="t in todoList.slice(0, 4)"
          :key="t.taskId"
          :task="t"
          @view="goApprove"
          @approve="goApprove"
          @transmit="goApprove"
        />
      </div>
      <div v-else class="glass-empty">
        <div class="glass-empty-title">没有待办</div>
        <div class="glass-empty-desc">当前没有需要你处理的工作。</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.cols {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: 16px;
}

.panel {
  padding: 18px 20px;
}

.panel-title {
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.panel-sub {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-tertiary);
}

.chart {
  height: 280px;
}

.todo-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}

.see-all {
  background: none;
  border: none;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-gold);
  cursor: pointer;
  padding: 4px 2px;
  transition: opacity var(--dur-fast) var(--ease-out);
}
.see-all:hover {
  opacity: 0.75;
}

.notice-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.14);
}

.notice-item:last-child {
  border-bottom: none;
}

.notice-title {
  flex: 1;
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice-date {
  font-size: 12px;
  color: var(--text-tertiary);
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .metric-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 900px) {
  .cols {
    grid-template-columns: 1fr;
  }
  .todo-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 520px) {
  .metric-row {
    grid-template-columns: 1fr;
  }
}
</style>
