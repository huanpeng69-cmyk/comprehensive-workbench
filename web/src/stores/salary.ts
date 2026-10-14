import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listSalaryMonths,
  saveSalaryMonth,
  deleteSalaryMonth,
  updateSalaryRecord,
  updatePerfRecord,
  addPerfRecord,
  removePerfRecord,
  addSalaryRecord,
  removeSalaryRecord
} from '@/api/salary'
import type { SalaryMonthData, SalaryRecord, PerfRecord } from '@/mock/types'

/**
 * 工资制作 store：数据源为后端 PostgreSQL（/api/salaries）。
 * 仅全公司数据范围角色可用（后端强制，含查询）；普通员工调用将收到错误提示。
 * 旧版 localStorage（salary-months-v1）不再读写。
 */
export const useSalaryStore = defineStore('salary', () => {
  const months = ref<Record<string, SalaryMonthData>>({})
  const loading = ref(false)

  function applyMonth(data: SalaryMonthData) {
    months.value = { ...months.value, [data.month]: data }
  }

  /** 拉取全部月份（含明细）；数据量小（月 × 数十行），一次到位 */
  async function load() {
    loading.value = true
    try {
      const list = await listSalaryMonths()
      const next: Record<string, SalaryMonthData> = {}
      for (const m of list) next[m.month] = m
      months.value = next
    } finally {
      loading.value = false
    }
  }

  const monthList = computed(() => Object.keys(months.value).sort().reverse())

  /** 导入/保存一个月（后端整月覆盖） */
  function importMonth(data: SalaryMonthData) {
    return saveSalaryMonth(data.month, { records: data.records, perf: data.perf }).then((saved) => {
      applyMonth(saved)
      return saved
    })
  }

  function removeMonth(m: string) {
    return deleteSalaryMonth(m).then(() => {
      const next = { ...months.value }
      delete next[m]
      months.value = next
    })
  }

  function updateRecord(m: string, rec: SalaryRecord) {
    return updateSalaryRecord(m, rec).then((saved) => {
      const d = months.value[m]
      if (!d) return
      const i = d.records.findIndex((r) => r.id === saved.id)
      if (i >= 0) d.records[i] = saved
      months.value = { ...months.value }
    })
  }

  function updatePerf(m: string, rec: PerfRecord) {
    return updatePerfRecord(m, rec).then((saved) => {
      const d = months.value[m]
      if (!d) return
      const i = d.perf.findIndex((r) => r.id === saved.id)
      if (i >= 0) d.perf[i] = saved
      months.value = { ...months.value }
    })
  }

  /** 新增单条工资明细（对齐花名册「新增人员」）。 */
  function addRecord(m: string, rec: SalaryRecord) {
    return addSalaryRecord(m, rec).then((saved) => {
      const d = months.value[m]
      if (!d) return saved
      d.records.push(saved)
      months.value = { ...months.value }
      return saved
    })
  }

  /** 删除单条工资明细（对齐花名册「删除此行」）。 */
  function removeRecord(m: string, id: string) {
    return removeSalaryRecord(m, id).then(() => {
      const d = months.value[m]
      if (!d) return
      d.records = d.records.filter((r) => r.id !== id)
      months.value = { ...months.value }
    })
  }

  function addPerf(m: string, rec: PerfRecord) {
    return addPerfRecord(m, rec).then((saved) => {
      const d = months.value[m]
      if (!d) return saved
      d.perf.push(saved)
      months.value = { ...months.value }
      return saved
    })
  }

  function removePerf(m: string, id: string) {
    return removePerfRecord(m, id).then(() => {
      const d = months.value[m]
      if (!d) return
      d.perf = d.perf.filter((r) => r.id !== id)
      months.value = { ...months.value }
    })
  }

  // 页面初始化拉取一次；无权限/未登录时静默失败（页面显示空）
  void load().catch(() => {})

  return { months, monthList, loading, load, importMonth, removeMonth, updateRecord, updatePerf, addRecord, removeRecord, addPerf, removePerf }
})
