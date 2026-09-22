import { defineStore } from 'pinia'
import { reactive } from 'vue'
import type { LeaveRecord } from '@/mock/types'
import { leaveRecords as seed } from '@/mock/data'

const STORAGE_KEY = 'leave-records-v1'

/** 计入工日扣减的请假类型：事假 / 病假 / 其他（年假、调休等带薪假不扣） */
const DEDUCTIBLE_TYPES = new Set<LeaveRecord['type']>(['事假', '病假', '其他'])

function load(): LeaveRecord[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const a = JSON.parse(raw)
      if (Array.isArray(a)) return a
    }
  } catch {
    /* 解析失败回退种子 */
  }
  return seed.map((r) => ({ ...r }))
}

/** 模块级持久化状态：供公式引擎注入式读取，免 Pinia 激活依赖 */
export const leaveList = reactive<LeaveRecord[]>(load())

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(leaveList))
  } catch {
    /* 配额异常静默 */
  }
}

function daysBetween(start: string, end: string): number {
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (isNaN(s) || isNaN(e)) return 0
  return Math.round((e - s) / 86400000) + 1
}

/** 单条请假与指定月份的重叠自然日数（处理跨月请假只计当月部分） */
function overlapDays(start: string, end: string, month: string): number {
  const y = Number(month.slice(0, 4))
  const m = Number(month.slice(5, 7))
  const last = new Date(y, m, 0).getDate()
  const ms = `${month}-01`
  const me = `${month}-${String(last).padStart(2, '0')}`
  const s = start < ms ? ms : start
  const e = end > me ? me : end
  if (s > e) return 0
  return daysBetween(s, e)
}

/**
 * 当月已批准且计入工日扣减的请假天数（事假/病假/其他），按与月份重叠的自然日计。
 * 这是工资工日勾稽的核心：请假页增删/审批 → 工资岗位工日/岗位工资实时联动。
 */
export function leaveDaysFor(name: string, month: string): number {
  if (!name || !month) return 0
  let total = 0
  for (const r of leaveList) {
    if (r.applicant !== name) continue
    if (r.status !== '已批准') continue
    if (!DEDUCTIBLE_TYPES.has(r.type)) continue
    if (month < r.start.slice(0, 7) || month > r.end.slice(0, 7)) continue
    total += overlapDays(r.start, r.end, month)
  }
  return total
}

export const useLeaveStore = defineStore('leave', () => {
  function add(rec: LeaveRecord) {
    leaveList.unshift(rec)
    persist()
  }

  function update(rec: LeaveRecord) {
    const i = leaveList.findIndex((r) => r.id === rec.id)
    if (i >= 0) {
      leaveList[i] = rec
      persist()
    }
  }

  function approve(id: number) {
    const r = leaveList.find((x) => x.id === id)
    if (r) {
      r.status = '已批准'
      persist()
    }
  }

  function reject(id: number) {
    const r = leaveList.find((x) => x.id === id)
    if (r) {
      r.status = '已驳回'
      persist()
    }
  }

  function remove(id: number) {
    const i = leaveList.findIndex((r) => r.id === id)
    if (i >= 0) {
      leaveList.splice(i, 1)
      persist()
    }
  }

  function daysFor(name: string, month: string): number {
    return leaveDaysFor(name, month)
  }

  return { list: leaveList, add, update, approve, reject, remove, daysFor }
})
