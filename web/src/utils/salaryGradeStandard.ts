import { reactive } from 'vue'
import type { GradeStandard } from '@/mock/types'
import { get, put } from '@/api/http'

/**
 * 工资核算配置（应出工日基准 + 薪档标准表）：
 * 数据源为后端 /api/salaries/config（仅全公司数据范围角色可读写）。
 * 模块加载时异步拉取一次；persist* 改为调用服务端接口。
 * 旧 localStorage（salary-grade-standard-v1 / salary-payroll-config-v1）不再读写。
 */

/** 全局工资核算配置（应出工日等基准） */
export interface PayrollConfig {
  /** 月计薪基准工日（法定平均 21.75），岗位工日 = 应出工日 - 请假扣减 */
  standardWorkdays: number
}

const DEFAULT_CONFIG: PayrollConfig = { standardWorkdays: 21.75 }

/** 全局应出工日基准（reactive，跨页面/模块共享） */
export const payrollConfig = reactive<PayrollConfig>({ ...DEFAULT_CONFIG })

/** 薪档标准表（reactive，跨页面/模块共享） */
export const gradeStandards = reactive<GradeStandard[]>([])

let initialized = false

/** 从服务器加载配置；失败时保持默认值（页面会有后端错误提示路径） */
export async function initSalaryConfig(force = false): Promise<void> {
  if (initialized && !force) return
  const data = await get<{ standardWorkdays?: number; standards?: GradeStandard[] }>(
    '/api/salaries/config'
  )
  if (data && typeof data === 'object') {
    if (typeof data.standardWorkdays === 'number' && Number.isFinite(data.standardWorkdays)) {
      payrollConfig.standardWorkdays = data.standardWorkdays
    }
    if (Array.isArray(data.standards)) {
      gradeStandards.splice(0, gradeStandards.length, ...data.standards)
    }
  }
  initialized = true
}

/** 模块加载即拉取（幂等；未登录时请求失败静默，登录页不需要该数据） */
void initSalaryConfig().catch(() => {})

async function persistAll(): Promise<void> {
  await put('/api/salaries/config', {
    standardWorkdays: payrollConfig.standardWorkdays,
    standards: gradeStandards
  })
}

export function persistConfig(): Promise<void> {
  return persistAll()
}

export function setStandardWorkdays(v: number) {
  if (Number.isFinite(v) && v > 0) {
    payrollConfig.standardWorkdays = v
    void persistConfig().catch(() => {})
  }
}

export function persistStandards(): Promise<void> {
  return persistAll()
}

export function getStandard(grade: string | undefined): GradeStandard | undefined {
  if (!grade) return undefined
  return gradeStandards.find((s) => s.grade === grade)
}

export function gradeList(): string[] {
  return gradeStandards.map((s) => s.grade)
}

/** 编辑单条标准（直接 mutate reactive 后持久化到服务端） */
export function updateStandard(grade: string, patch: Partial<GradeStandard>) {
  const s = getStandard(grade)
  if (!s) return
  Object.assign(s, patch)
  void persistAll().catch(() => {})
}

export function addStandard(grade: string) {
  if (!grade || getStandard(grade)) return
  gradeStandards.push({
    id: 'std-' + grade,
    grade,
    postSalaryBase: 0,
    titleAllowanceBase: 0,
    seniorityBase: 0,
    siteBase: 0,
    dailyWageBase: 0,
    pensionRate: 0.08,
    unemploymentRate: 0.003,
    medicalRate: 0.02,
    housingFundRate: 0.12,
    annuityRate: 0.04,
    largeMedicalRate: 0,
    taxThreshold: 5000,
    netStandard: 0
  })
  void persistAll().catch(() => {})
}

export function removeStandard(grade: string) {
  const i = gradeStandards.findIndex((s) => s.grade === grade)
  if (i >= 0) gradeStandards.splice(i, 1)
  void persistAll().catch(() => {})
}

/**
 * 首次使用时按花名册已有薪档建表。samples: 薪档 → 代表人员取数（基数/月度实领标准）。
 * 已有的标准表不会被覆盖。persist 失败时本地已生效，下次修改会再次尝试保存。
 */
export function ensureSeed(opts: { grades: string[]; samples?: Record<string, Partial<GradeStandard>> }) {
  if (gradeStandards.length) return
  const grades = [...new Set((opts.grades || []).filter(Boolean))]
  if (!grades.length) return
  for (const g of grades) {
    const s = opts.samples?.[g]
    gradeStandards.push({
      id: 'std-' + g,
      grade: g,
      postSalaryBase: s?.postSalaryBase ?? 0,
      titleAllowanceBase: s?.titleAllowanceBase ?? 0,
      seniorityBase: s?.seniorityBase ?? 0,
      siteBase: s?.siteBase ?? 0,
      dailyWageBase: s?.dailyWageBase ?? 0,
      pensionRate: s?.pensionRate ?? 0.08,
      unemploymentRate: s?.unemploymentRate ?? 0.003,
      medicalRate: s?.medicalRate ?? 0.02,
      housingFundRate: s?.housingFundRate ?? 0.12,
      annuityRate: s?.annuityRate ?? 0.04,
      largeMedicalRate: s?.largeMedicalRate ?? 0,
      taxThreshold: s?.taxThreshold ?? 5000,
      netStandard: s?.netStandard ?? 0
    })
  }
  void persistAll().catch(() => {})
}
