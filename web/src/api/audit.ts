import { get } from './http'

/** 审计结果码；基座改造前的历史记录为 null，页面显示「未标注」。 */
export type AuditResult = 'SUCCESS' | 'DENIED' | 'FAILURE'

/** 一条审计记录，对应后端 AuditLogView。 */
export interface AuditLogItem {
  id: number
  username: string
  action: string
  targetType: string | null
  targetId: string | null
  detail: string | null
  result: AuditResult | null
  resultCode: string | null
  traceId: string | null
  /** 本地时间字符串，如 2026-11-05T10:14:14.200925 */
  createdAt: string
}

export interface AuditLogPage {
  items: AuditLogItem[]
  total: number
  page: number
  size: number
}

export interface AuditLogQuery {
  username?: string
  action?: string
  result?: AuditResult | ''
  traceId?: string
  /** ISO-8601 本地时间，如 2026-11-01T00:00:00 */
  from?: string
  to?: string
  page?: number
  size?: number
}

/**
 * 分页查询审计日志（仅 SYSTEM_ADMIN，后端 @PreAuthorize 把关）。
 *
 * 空字符串参数在这里被剔除，避免序列化成 `action=` 让后端当成「筛选 action 为空串」而查不到数据。
 */
export function listAuditLogs(query: AuditLogQuery = {}) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') {
      continue
    }
    params.set(key, String(value))
  }
  const qs = params.toString()
  return get<AuditLogPage>(`/api/audit-logs${qs ? `?${qs}` : ''}`)
}