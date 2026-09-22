/** 后端角色码到中文名的映射，用于头部展示。 */
const ROLE_NAMES: Record<string, string> = {
  EMPLOYEE: '普通员工',
  PROJECT_MANAGER: '项目经理',
  HR_ADMIN: '人力管理员',
  FINANCE: '财务',
  PARTY_ADMIN: '党建管理员',
  UNION_ADMIN: '工会管理员',
  SYSTEM_ADMIN: '系统管理员'
}

export function roleDisplayName(roles: string[]): string {
  if (!roles || roles.length === 0) {
    return '普通员工'
  }
  return roles.map((r) => ROLE_NAMES[r] || r).join('、')
}
