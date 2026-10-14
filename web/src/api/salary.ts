import { get, put, post, del } from './http'
import type { SalaryMonthData, SalaryRecord, PerfRecord } from '@/mock/types'

/**
 * 工资制作 API：数据统一存 PostgreSQL，前端不再持久化业务数据。
 * 整个模块为敏感数据，仅全公司数据范围角色（人力/财务/系统管理）可访问，
 * 后端对包括查询在内的所有请求强制校验并审计。
 */
export const listSalaryMonths = () => get<SalaryMonthData[]>('/api/salaries/months')

export const saveSalaryMonth = (
  month: string,
  data: { records: SalaryRecord[]; perf: PerfRecord[] }
) => put<SalaryMonthData>(`/api/salaries/months/${encodeURIComponent(month)}`, data)

export const deleteSalaryMonth = (month: string) =>
  del<void>(`/api/salaries/months/${encodeURIComponent(month)}`)

export const updateSalaryRecord = (month: string, record: SalaryRecord) =>
  put<SalaryRecord>(
    `/api/salaries/months/${encodeURIComponent(month)}/records/${encodeURIComponent(record.id)}`,
    record
  )

export const updatePerfRecord = (month: string, record: PerfRecord) =>
  put<PerfRecord>(
    `/api/salaries/months/${encodeURIComponent(month)}/perf/${encodeURIComponent(record.id)}`,
    record
  )

/** 新增单条绩效明细。 */
export const addPerfRecord = (month: string, record: PerfRecord) =>
  post<PerfRecord>(`/api/salaries/months/${encodeURIComponent(month)}/perf`, record)

/** 删除单条绩效明细。 */
export const removePerfRecord = (month: string, recordId: string) =>
  del<void>(`/api/salaries/months/${encodeURIComponent(month)}/perf/${encodeURIComponent(recordId)}`)
/** 新增单条工资明细（对齐花名册「新增人员」）。 */
export const addSalaryRecord = (month: string, record: SalaryRecord) =>
  post<SalaryRecord>(`/api/salaries/months/${encodeURIComponent(month)}/records`, record)

/** 删除单条工资明细（对齐花名册「删除此行」）。 */
export const removeSalaryRecord = (month: string, recordId: string) =>
  del<void>(
    `/api/salaries/months/${encodeURIComponent(month)}/records/${encodeURIComponent(recordId)}`
  )
