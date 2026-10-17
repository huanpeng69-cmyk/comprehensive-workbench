import type { SalaryRecord, RosterMember, GradeStandard } from '@/mock/types'
import { SALARY_LABEL_TO_KEY, GRADE_STD_LABEL_TO_KEY } from '@/mock/salaryOptions'
import { evalFormula } from './formula'
import { getStandard, payrollConfig } from './salaryGradeStandard'
import { leaveDaysFor, leaveDaysByKind } from '@/stores/leave'
import type { LeaveDay } from './salaryBasePay'

/**
 * 请假天数解析器（注入式，默认接共享请假 store；可在调用侧覆盖）。
 * 返回按假别归集的请假天数，供公式标签 `[请假.年假]` / `[请假.病假]` / `[请假.事假]` 取值。
 */
let leaveDaysResolver: (name: string, month: string) => LeaveDay[] = leaveDaysByKind
export function setLeaveDaysResolver(fn: (name: string, month: string) => LeaveDay[]) {
  leaveDaysResolver = fn
}

/** 旧口径：所有可扣假别压成一个总天数。保留给 `[请假.当月天数]`，兼容历史数据里的旧公式。 */
let leaveTotalResolver: (name: string, month: string) => number = leaveDaysFor
export function setLeaveTotalResolver(fn: (name: string, month: string) => number) {
  leaveTotalResolver = fn
}

/** 按假别取当月天数；未出现的假别返回 0 */
function leaveDaysOfKind(name: string, month: string, kind: string): number {
  let total = 0
  for (const d of leaveDaysResolver(name, month)) {
    if (d.kind === kind) total += d.days
  }
  return total
}

/** 勾稽上下文：当前记录对应的薪档与花名册来源 */
export interface SalaryCtx {
  grade?: string
  roster?: RosterMember | undefined
  standard?: GradeStandard | undefined
}

/** 薪档标准 lookup（页面直接调，避免重复逻辑） */
export function standardForGrade(grade: string | undefined): GradeStandard | undefined {
  return grade ? getStandard(grade) : undefined
}

/** 花名册可引用字段（[花名册.月薪] → monthlyPay） */
const ROSTER_LABEL_TO_KEY: Record<string, keyof RosterMember> = { 月薪: 'monthlyPay' }

function rosterNumericVal(member: RosterMember | undefined, field: string): number {
  if (!member) return NaN
  const k = ROSTER_LABEL_TO_KEY[field]
  if (!k) return NaN
  const v = member[k]
  if (typeof v === 'number') return v
  const s = String(v ?? '').trim()
  if (!s) return 0
  if (!s.startsWith('=')) {
    const n = Number(s.replace(/,/g, ''))
    return Number.isFinite(n) ? n : 0
  }
  return rosterVal(member, k)
}

/** 取工资字段的有效值（支持行内公式 + 跨表命名空间） */
export function salaryVal(rec: SalaryRecord, key: keyof SalaryRecord, depth = 0, ctx?: SalaryCtx): number {
  const v = rec[key]
  if (typeof v === 'number') return v
  const s = String(v ?? '').trim()
  if (!s) return 0
  if (!s.startsWith('=')) {
    const n = Number(s.replace(/,/g, ''))
    return Number.isFinite(n) ? n : 0
  }
  if (depth > 20) return NaN
  const grade = ctx?.grade || rec.salaryGrade || ctx?.roster?.salaryGrade || ''
  const standard = ctx?.standard || standardForGrade(grade)
  const nextCtx: SalaryCtx = { ...ctx, grade, standard }
  return evalFormula(s.slice(1), (label) => resolveSalaryLabel(label, rec, depth + 1, nextCtx), depth)
}

function resolveSalaryLabel(label: string, rec: SalaryRecord, depth: number, ctx: SalaryCtx): number {
  if (label === '应出工日') {
    return payrollConfig.standardWorkdays
  }
  if (label.startsWith('请假.')) {
    const field = label.slice(3)
    // 旧口径：所有可扣假别压成总数（兼容历史公式，语义见 stores/leave.ts 的 DEDUCTIBLE_TYPES）
    if (field === '当月天数') return leaveTotalResolver(rec.name, rec.month)
    // 新口径：按假别取天数，供分假别扣减公式使用（口径见 utils/salaryBasePay.ts）
    if (field === '年假' || field === '病假' || field === '事假') {
      return leaveDaysOfKind(rec.name, rec.month, field)
    }
    return NaN
  }
  if (label.startsWith('薪档标准.')) {
    const k = GRADE_STD_LABEL_TO_KEY[label.slice(5)]
    return k ? gradeStandardVal(ctx.standard, k, depth) : NaN
  }
  if (label.startsWith('花名册.')) {
    return rosterNumericVal(ctx.roster, label.slice(4))
  }
  const k = SALARY_LABEL_TO_KEY[label]
  return k ? salaryVal(rec, k, depth, ctx) : NaN
}

/** 薪档标准字段求值（支持标准表内字段互引公式） */
export function gradeStandardVal(
  g: GradeStandard | undefined,
  key: keyof GradeStandard,
  depth = 0
): number {
  if (!g) return NaN
  const v = g[key]
  if (typeof v === 'number') return v
  const s = String(v ?? '').trim()
  if (!s || !s.startsWith('=')) {
    const n = Number(s.replace(/,/g, ''))
    return Number.isFinite(n) ? n : 0
  }
  if (depth > 20) return NaN
  return evalFormula(
    s.slice(1),
    (label) => {
      if (label === '应出工日') return payrollConfig.standardWorkdays
      const k = GRADE_STD_LABEL_TO_KEY[label]
      return k ? gradeStandardVal(g, k, depth + 1) : NaN
    },
    depth
  )
}


/** 薪档标准字段的原始取值（未命中返回 NaN，便于"是否有公式/值"判断） */
export function gradeStandardRaw(g: GradeStandard | undefined, key: keyof GradeStandard): number {
  if (!g) return NaN
  const v = g[key]
  if (typeof v === 'number') return v
  const s = String(v ?? '').trim()
  if (!s) return NaN
  if (s.startsWith('=')) return gradeStandardVal(g, key)
  const n = Number(s.replace(/,/g, ''))
  return Number.isFinite(n) ? n : NaN
}

/** 花名册字段求值（支持 =公式，主要给 monthlyPay 引用薪档标准用） */
export function rosterVal(member: RosterMember, key: keyof RosterMember, depth = 0): number {
  const v = member[key]
  if (typeof v === 'number') return v
  const s = String(v ?? '').trim()
  if (!s) return 0
  if (!s.startsWith('=')) {
    const n = Number(s.replace(/,/g, ''))
    return Number.isFinite(n) ? n : 0
  }
  if (depth > 20) return NaN
  const standard = standardForGrade(member.salaryGrade)
  return evalFormula(
    s.slice(1),
    (label) => {
      if (label.startsWith('薪档标准.')) {
        const k = GRADE_STD_LABEL_TO_KEY[label.slice(5)]
        return k ? gradeStandardVal(standard, k, depth + 1) : NaN
      }
      if (label.startsWith('花名册.')) return rosterNumericVal(member, label.slice(4))
      return NaN
    },
    depth
  )
}

/** 花名册月薪：若公式则求值，否则原值 */
export function rosterMonthlyPay(member: RosterMember): number {
  const v = member.monthlyPay
  if (typeof v === 'number') return v
  const s = String(v ?? '').trim()
  if (!s) return 0
  if (!s.startsWith('=')) {
    const n = Number(s.replace(/,/g, ''))
    return Number.isFinite(n) ? n : 0
  }
  return rosterVal(member, 'monthlyPay')
}

/** 是否公式格 */
export function isFormula(v: unknown): boolean {
  return typeof v === 'string' && v.trim().startsWith('=')
}

/** 金额格式化 */
export function money(v: number, digits = 2): string {
  if (!Number.isFinite(v)) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

/** 五险一金合计 */
export function insuranceTotal(rec: SalaryRecord): number {
  return (
    salaryVal(rec, 'pension') +
    salaryVal(rec, 'unemployment') +
    salaryVal(rec, 'medical') +
    salaryVal(rec, 'housingFund') +
    salaryVal(rec, 'annuity') +
    salaryVal(rec, 'largeMedical')
  )
}
