import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { SalaryMonthData, SalaryRecord, PerfRecord } from '@/mock/types'

const STORAGE_KEY = 'salary-months-v1'

function load(): Record<string, SalaryMonthData> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const obj = JSON.parse(raw)
      if (obj && typeof obj === 'object') return obj
    }
  } catch {
    /* 解析失败回退空 */
  }
  return {}
}

export const useSalaryStore = defineStore('salary', () => {
  const months = ref<Record<string, SalaryMonthData>>(load())

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(months.value))
    } catch {
      /* 配额异常静默失败 */
    }
  }

  const monthList = computed(() => Object.keys(months.value).sort().reverse())

  function importMonth(data: SalaryMonthData) {
    months.value = { ...months.value, [data.month]: data }
    persist()
  }

  function removeMonth(m: string) {
    const next = { ...months.value }
    delete next[m]
    months.value = next
    persist()
  }

  function updateRecord(m: string, rec: SalaryRecord) {
    const d = months.value[m]
    if (!d) return
    const i = d.records.findIndex((r) => r.id === rec.id)
    if (i >= 0) d.records[i] = rec
    months.value = { ...months.value }
    persist()
  }

  function updatePerf(m: string, rec: PerfRecord) {
    const d = months.value[m]
    if (!d) return
    const i = d.perf.findIndex((r) => r.id === rec.id)
    if (i >= 0) d.perf[i] = rec
    months.value = { ...months.value }
    persist()
  }

  return { months, monthList, importMonth, removeMonth, updateRecord, updatePerf }
})
