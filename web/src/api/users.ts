import { get, post, put, del } from './http'

export interface RoleOption {
  code: string
  name: string
  dataScope: string
  enabled: boolean
}

export interface UserItem {
  id: number
  username: string
  name: string
  phone: string | null
  employmentType: string | null
  orgId: number | null
  projectId: number | null
  projectName: string | null
  roleCodes: string[]
  roleNames: string[]
  enabled: boolean
}

export interface UserQuery {
  keyword?: string
  projectId?: number
  roleCode?: string
}

export function listUsers(params?: UserQuery) {
  const qs = new URLSearchParams()
  if (params?.keyword) qs.set('keyword', params.keyword)
  if (params?.projectId != null) qs.set('projectId', String(params.projectId))
  if (params?.roleCode) qs.set('roleCode', params.roleCode)
  const q = qs.toString()
  return get<UserItem[]>(`/api/users${q ? '?' + q : ''}`)
}

export function getUser(id: number) {
  return get<UserItem>(`/api/users/${id}`)
}

export function createUser(body: Partial<UserItem> & { password?: string }) {
  return post<UserItem>('/api/users', body)
}

export function updateUser(id: number, body: Partial<UserItem> & { password?: string }) {
  return put<UserItem>(`/api/users/${id}`, body)
}

export function deleteUser(id: number) {
  return del<void>(`/api/users/${id}`)
}

export function resetPassword(id: number, password: string) {
  return post<void>(`/api/users/${id}/password`, { password })
}

export function listRoles() {
  return get<RoleOption[]>('/api/users/roles')
}
