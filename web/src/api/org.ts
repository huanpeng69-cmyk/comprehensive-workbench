import { get } from './http'

/** 组织机构单元（与后端 OrgUnit 实体字段一致）。 */
export interface OrgUnit {
  id: number
  name: string
  type: string
  parentId: number | null
  fullPath: string | null
  sortNo: number | null
  enabled: boolean
}

/** 查询组织机构，可选按类型过滤（PROJECT / DEPARTMENT / COMPANY）。 */
export const listOrgUnits = (type?: string) =>
  get<OrgUnit[]>(`/api/org/units${type ? `?type=${encodeURIComponent(type)}` : ''}`)
