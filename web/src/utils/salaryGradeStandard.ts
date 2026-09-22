import { reactive } from 'vue'
import type { GradeStandard } from '@/mock/types'

const STORAGE_KEY = 'salary-grade-standard-v1'
const CONFIG_KEY = 'salary-payroll-config-v1'

/** 全局工资核算配置（应出工日等基准） */
export interface PayrollConfig {
  /** 月计薪基准工日（法定平均 21.75），岗位工日 = 应出工日 - 请假扣减 */
  standardWorkdays: number
}

function loadConfig(): PayrollConfig {
  try {
    const raw = localStorage.getItem(CONFIG_KEY)
    if (raw) {
      const c = JSON.parse(raw)
      if (c && typeof c.standardWorkdays === 'number' && Number.isFinite(c.standardWorkdays)) {
        return { standardWorkdays: c.standardWorkdays }
      }
    }
  } catch {
    /* 解析失败回退默认 */
  }
  return { standardWorkdays: 21.75 }
}

/** 全局应出工日基准（reactive，跨页面/模块共享并持久化） */
export const payrollConfig = reactive<PayrollConfig>(loadConfig())

export function persistConfig() {
  try {
    localStorage.setItem(CONFIG_KEY, JSON.stringify(payrollConfig))
  } catch {
    /* 配额异常静默 */
  }
}

export function setStandardWorkdays(v: number) {
  if (Number.isFinite(v) && v > 0) {
    payrollConfig.standardWorkdays = v
    persistConfig()
  }
}

function load(): GradeStandard[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const a = JSON.parse(raw)
      if (Array.isArray(a)) return a
    }
  } catch {
    /* 解析失败回退空 */
  }
  return []
}

/** 薪档标准表（reactive，跨页面/模块共享） */
export const gradeStandards = reactive<GradeStandard[]>(load())

export function persistStandards() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(gradeStandards))
  } catch {
    /* 配额异常静默 */
  }
}

export function getStandard(grade: string | undefined): GradeStandard | undefined {
  if (!grade) return undefined
  return gradeStandards.find((s) => s.grade === grade)
}

export function gradeList(): string[] {
  return gradeStandards.map((s) => s.grade)
}

/** 编辑单条标准（直接 mutate reactive 后持久化） */
export function updateStandard(grade: string, patch: Partial<GradeStandard>) {
  const s = getStandard(grade)
  if (!s) return
  Object.assign(s, patch)
  persistStandards()
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
  persistStandards()
}

export function removeStandard(grade: string) {
  const i = gradeStandards.findIndex((s) => s.grade === grade)
  if (i >= 0) gradeStandards.splice(i, 1)
  persistStandards()
}

/**
 * 首次使用时按花名册已有薪档建表。samples: 薪档 → 代表人员取数（基数/月度实领标准）。
 * 已有的标准表不会被覆盖。
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
  persistStandards()
}
