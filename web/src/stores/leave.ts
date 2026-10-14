import { defineStore } from 'pinia'
import { reactive } from 'vue'
import type { LeaveRecord } from '@/mock/types'
import {
  listLeaves,
  createLeave,
  updateLeave,
  deleteLeave,
  approveLeave,
  rejectLeave,
  type CreateLeaveInput
} from '@/api/leave'

/** 计入工日扣减的请假类型：事假 / 病假 / 其他（年假、调休等带薪假不扣） */
const DEDUCTIBLE_TYPES = new Set<LeaveRecord['type']>(['事假', '病假', '其他'])

/**
 * 模块级持久化状态：供公式引擎注入式读取，免 Pinia 激活依赖。
 * 数据现以后端 PostgreSQL 为准，启动时从 /api/leaves 拉取（按当前角色数据范围过滤）。
 */
export const leaveList = reactive<LeaveRecord[]>([])

async function load(): Promise<void> {
  try {
    const data = await listLeaves()
    leaveList.splice(0, leaveList.length, ...data)
  } catch {
    /* 加载失败静默，页面显示空列表 */
  }
}

// 启动即拉取一次（salary.ts 在导入本模块时会顺带触发）
void load()

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
 * 读取内存 leaveList（已后端化），salary.ts 直接复用，无需改动。
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
  function add(input: CreateLeaveInput) {
    return createLeave(input).then((created) => {
      leaveList.unshift(created)
      return created
    })
  }

  function update(rec: LeaveRecord) {
    return updateLeave(rec).then((saved) => {
      const i = leaveList.findIndex((r) => r.id === saved.id)
      if (i >= 0) leaveList[i] = saved
      return saved
    })
  }

  function approve(id: number, comment?: string) {
    return approveLeave(id, comment).then((saved) => {
      const i = leaveList.findIndex((r) => r.id === saved.id)
      if (i >= 0) leaveList[i] = saved
      return saved
    })
  }

  function reject(id: number, comment?: string) {
    return rejectLeave(id, comment).then((saved) => {
      const i = leaveList.findIndex((r) => r.id === saved.id)
      if (i >= 0) leaveList[i] = saved
      return saved
    })
  }

  function remove(id: number) {
    return deleteLeave(id).then(() => {
      const i = leaveList.findIndex((r) => r.id === id)
      if (i >= 0) leaveList.splice(i, 1)
    })
  }

  function daysFor(name: string, month: string): number {
    return leaveDaysFor(name, month)
  }

  return { list: leaveList, load, add, update, approve, reject, remove, daysFor }
})
