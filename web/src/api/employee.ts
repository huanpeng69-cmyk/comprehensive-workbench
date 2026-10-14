import { get, post, put, del } from './http'
import type { RosterMember } from '@/mock/types'

/**
 * 人员花名册 API：数据统一存 PostgreSQL，前端不再持久化业务数据。
 * 列表按登录角色的数据范围由后端过滤（COMPANY 全部 / PROJECT 本人项目部）。
 * 新增与整体导入需要全公司数据权限（如人力管理员），由后端强制校验。
 */
export const listEmployees = () => get<RosterMember[]>('/api/employees')

export const createEmployee = (member: Omit<RosterMember, 'id'>) =>
  post<RosterMember>('/api/employees', member)

export const updateEmployee = (member: RosterMember) =>
  put<RosterMember>(`/api/employees/${member.id}`, member)

export const deleteEmployee = (id: number) => del<void>(`/api/employees/${id}`)

/** 花名册整体导入（覆盖式），返回导入条数 */
export const importEmployees = (members: RosterMember[]) =>
  post<number>('/api/employees/import', members)
