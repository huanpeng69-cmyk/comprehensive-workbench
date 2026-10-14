import { get, post } from './http'
import type { CondolenceRecord } from '@/mock/types'

/**
 * 慰问金 API：数据统一存 PostgreSQL。提交时由后端启动 condolence 流程（申请人取当前登录用户）。
 * 列表按登录角色数据范围由后端过滤（COMPANY 全部 / PROJECT 本人项目部）。
 * 审批走真实工作流（POST /api/condolences/{id}/approve|reject），仅被指派工会审批人可操作。
 */
export interface CreateCondolenceInput {
  category: string
  reason: string
  amount: number
  amountInWords: string
}

export const listCondolences = () => get<CondolenceRecord[]>('/api/condolences')

export const myCondolences = () => get<CondolenceRecord[]>('/api/condolences/mine')

export const createCondolence = (input: CreateCondolenceInput) =>
  post<CondolenceRecord>('/api/condolences', input)

export const approveCondolence = (id: number, comment?: string) =>
  post<CondolenceRecord>(`/api/condolences/${id}/approve${comment ? `?comment=${encodeURIComponent(comment)}` : ''}`, {})

export const rejectCondolence = (id: number, comment?: string) =>
  post<CondolenceRecord>(`/api/condolences/${id}/reject${comment ? `?comment=${encodeURIComponent(comment)}` : ''}`, {})
