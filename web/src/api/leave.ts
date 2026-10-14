import { get, post, put, del } from './http'
import type { LeaveRecord } from '@/mock/types'

/**
 * 请假 API：数据统一存 PostgreSQL，提交时由后端启动 leave 流程（申请人取当前登录用户）。
 * 列表按登录角色数据范围由后端过滤（COMPANY 全部 / PROJECT 本人项目部）。
 * 审批走真实工作流（POST /api/leaves/{id}/approve|reject），仅被指派的项目部经理可操作。
 */
export interface CreateLeaveInput {
  type: LeaveRecord['type']
  start: string
  end: string
  days: number
  reason: string
}

export const listLeaves = () => get<LeaveRecord[]>('/api/leaves')

export const myLeaves = () => get<LeaveRecord[]>('/api/leaves/mine')

export const createLeave = (input: CreateLeaveInput) =>
  post<LeaveRecord>('/api/leaves', input)

export const updateLeave = (member: LeaveRecord) =>
  put<LeaveRecord>(`/api/leaves/${member.id}`, member)

export const deleteLeave = (id: number) => del<void>(`/api/leaves/${id}`)

export const approveLeave = (id: number, comment?: string) =>
  post<LeaveRecord>(`/api/leaves/${id}/approve${comment ? `?comment=${encodeURIComponent(comment)}` : ''}`, {})

export const rejectLeave = (id: number, comment?: string) =>
  post<LeaveRecord>(`/api/leaves/${id}/reject${comment ? `?comment=${encodeURIComponent(comment)}` : ''}`, {})
