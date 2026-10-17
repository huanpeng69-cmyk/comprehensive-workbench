/**
 * 岗位工资与假别处理（纯函数：无副作用、无 IO、无 Vue 依赖）。
 *
 * <p><b>口径（业务确认后的最终规则）</b>：
 *
 * <ul>
 *   <li><b>年假</b>：全额发放。不扣工日，岗位工资按满勤计。</li>
 *   <li><b>事假</b>：全额扣除。按「日工资标准 × 事假天数」扣减。</li>
 *   <li><b>病假及其他假别</b>：<b>不对应固定扣款规则</b>。系统不替用户决定扣多少，
 *       而是在界面上预留可填写项（{@code manualDeduction}），由用户自行输入扣款金额。
 *       未填写时按 0 处理，并在 {@code warnings} 里提示该项尚未填写 —— 不允许静默当作"无需扣款"。</li>
 * </ul>
 *
 * <p><b>与上一版实现的差异</b>：上一版给病假设了「计发比例 0.6」自动折算，
 * 属于系统替业务做假设。本版取消该假设，改为人工填写，因为病假工资的地方性/企业性
 * 差异过大，且实际由劳资人员按制度核定，系统硬编码只会制造偏差。
 *
 * <p>岗位工资 = 满勤岗位工资 − 事假扣款 − 手工填写的其他扣款
 * （满勤岗位工资 = 日工资标准 × 应出工日）。
 */

/** 单个假别的处理方式。 */
export type LeaveHandling = 'PAID' | 'DEDUCT_FULL' | 'MANUAL'

/** 一条已批准的请假（按假别归集后的天数）。 */
export interface LeaveDay {
  /** 假别原文，如「年假」「病假」「事假」「调休」 */
  kind: string
  /** 当月与请假区间重叠的天数 */
  days: number
}

/** 假别分类配置。 */
export interface LeavePayConfig {
  /** 应出工日基准（月计薪天数），默认 21.75 */
  standardWorkdays: number
  /** 全额发放、不扣工日的假别 */
  paidKinds: string[]
  /** 全额扣除、按日工资自动扣减的假别 */
  deductedKinds: string[]
  /**
   * 需要人工填写扣款的假别（不对应固定规则）。
   * 默认只含「病假」；其余未列出的假别也按人工处理（见 resolveHandling）。
   */
  manualKinds: string[]
}

export const DEFAULT_LEAVE_PAY_CONFIG: LeavePayConfig = {
  standardWorkdays: 21.75,
  paidKinds: ['年假', '调休', '婚假', '产假', '护理假', '丧假', '工伤假'],
  deductedKinds: ['事假'],
  manualKinds: ['病假']
}

/** 单个假别的处理明细。 */
export interface LeaveKindDetail {
  kind: string
  handling: LeaveHandling
  days: number
  /** 该假别由系统自动扣减的金额（PAID 为 0，MANUAL 也为 0——由人工填写，不走自动计算） */
  autoDeducted: number
}

/** 岗位工资计算结果。 */
export interface BasePayResult {
  /** 应出工日（月计薪基准） */
  standardWorkdays: number
  /** 日工资标准 */
  dailyWage: number
  /** 满勤岗位工资 = 日工资 × 应出工日 */
  fullAttendancePay: number
  /** 事假自动扣款 = 日工资 × 事假天数 */
  personalLeaveDeduction: number
  /** 人工填写的其他假别扣款（病假等），原样计入 */
  manualDeduction: number
  /** 实际岗位工资 = 满勤 − 事假扣款 − 人工扣款 */
  basePay: number
  /** 各假别天数归类 */
  summary: {
    annualDays: number
    personalDays: number
    sickDays: number
    /** 其他假别天数（调休/婚假等，带薪不扣） */
    otherPaidDays: number
    /** 需要人工填写扣款但天数 > 0 的假别名 */
    manualKinds: string[]
  }
  /** 逐个假别的明细 */
  details: LeaveKindDetail[]
  /** 需要人工关注的问题（非致命，但页面必须展示） */
  warnings: string[]
}

/** 金额保留 2 位（四舍五入）。链路只在产出金额处舍入一次。 */
export function round2(v: number): number {
  if (!Number.isFinite(v)) return NaN
  return Math.round((v + Number.EPSILON) * 100) / 100
}

/** 天数保留 4 位 */
export function round4(v: number): number {
  if (!Number.isFinite(v)) return NaN
  return Math.round((v + Number.EPSILON) * 10000) / 10000
}

function safeNum(v: unknown): number {
  const n = typeof v === 'number' ? v : Number(String(v ?? '').replace(/,/g, '').trim())
  return Number.isFinite(n) ? n : 0
}

/**
 * 判定某假别的处理方式。
 *
 * <p>优先级：显式全额扣除假别 → 显式带薪假别 → 显式人工假别 → <b>其余一律人工</b>。
 *
 * <p>「其余一律人工」是有意的保守选择：病假、护理假之外的未知假别（例如新增的
 * 「育儿假」「独生子女护理假」）系统不知道该怎么算，宁可让用户填，也不替业务拍板。
 */
export function resolveHandling(
  kind: string,
  config: LeavePayConfig = DEFAULT_LEAVE_PAY_CONFIG
): LeaveHandling {
  const k = String(kind ?? '').trim()
  if (!k) return 'MANUAL'
  if (config.deductedKinds.includes(k)) return 'DEDUCT_FULL'
  if (config.paidKinds.includes(k)) return 'PAID'
  if (config.manualKinds.includes(k)) return 'MANUAL'
  return 'MANUAL'
}

/**
 * 计算岗位工资。
 *
 * @param leaves          已批准的请假（按假别归集；同假别多条会累加）
 * @param dailyWage       日工资标准（来自薪档标准）
 * @param manualDeduction 用户为病假等"无固定规则"假别手工填写的扣款金额
 * @param config          假别分类配置
 */
export function calcBasePay(
  leaves: LeaveDay[] | undefined,
  dailyWage: number,
  manualDeduction: number | undefined,
  config: LeavePayConfig = DEFAULT_LEAVE_PAY_CONFIG
): BasePayResult {
  const warnings: string[] = []

  const standardWorkdays = safeNum(config.standardWorkdays)
  const wage = safeNum(dailyWage)
  const manual = Math.max(0, safeNum(manualDeduction))

  if (!(standardWorkdays > 0)) {
    warnings.push(`应出工日基准非法（${config.standardWorkdays}），无法计算满勤岗位工资`)
  }
  if (!(wage > 0)) {
    warnings.push('日工资标准缺失或为 0，岗位工资无法计算（请先维护薪档标准的「日工资标准」）')
  }

  // 按假别合并
  const merged = new Map<string, number>()
  for (const item of leaves ?? []) {
    const kind = String(item?.kind ?? '').trim()
    if (!kind) continue
    const days = Math.max(0, safeNum(item?.days))
    if (days <= 0) continue
    merged.set(kind, round4((merged.get(kind) ?? 0) + days))
  }

  const details: LeaveKindDetail[] = []
  const manualKindsWithDays: string[] = []
  let annualDays = 0
  let personalDays = 0
  let sickDays = 0
  let otherPaidDays = 0

  for (const [kind, days] of merged) {
    const handling = resolveHandling(kind, config)
    if (handling === 'PAID') {
      if (kind === '年假') annualDays = round4(annualDays + days)
      else otherPaidDays = round4(otherPaidDays + days)
    } else if (handling === 'DEDUCT_FULL') {
      if (kind === '事假') personalDays = round4(personalDays + days)
    } else {
      if (kind === '病假') sickDays = round4(sickDays + days)
      manualKindsWithDays.push(kind)
    }
    details.push({
      kind,
      handling,
      days,
      // 只有全额扣除类才由系统自动算钱；人工类不走自动计算
      autoDeducted: 0
    })
  }

  const fullAttendancePay = round2(wage * standardWorkdays)
  const personalLeaveDeduction = round2(wage * personalDays)

  // 人工填写项的校验
  if (manualKindsWithDays.length > 0) {
    if (manual <= 0) {
      warnings.push(
        `以下假别不对应固定扣款规则，需人工填写扣款金额，当前为 0：${manualKindsWithDays.join('、')}`
      )
    }
  } else if (manual > 0) {
    warnings.push('本期没有需要人工扣款的假别，但填写了扣款金额，请确认是否填错位置')
  }

  let basePay = round2(fullAttendancePay - personalLeaveDeduction - manual)
  if (basePay < 0) {
    warnings.push(
      `扣款合计（事假 ${personalLeaveDeduction} + 人工 ${manual}）超过满勤岗位工资 ${fullAttendancePay}，岗位工资已按 0 处理`
    )
    basePay = 0
  }

  // 明细里回填自动扣款金额（事假才有；用于页面展开说明）
  for (const d of details) {
    if (d.handling === 'DEDUCT_FULL' && d.kind === '事假') {
      d.autoDeducted = round2(wage * d.days)
    }
  }

  return {
    standardWorkdays: round4(standardWorkdays),
    dailyWage: round2(wage),
    fullAttendancePay,
    personalLeaveDeduction,
    manualDeduction: round2(manual),
    basePay,
    summary: {
      annualDays,
      personalDays,
      sickDays,
      otherPaidDays,
      manualKinds: manualKindsWithDays
    },
    details,
    warnings
  }
}
