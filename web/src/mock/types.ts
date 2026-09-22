// 业务数据类型定义（与真实业务档案字段一致）

export type EmploymentType = '正式职工' | '临时工' | '外聘' | '返聘'

export interface Staff {
  id: number
  name: string
  employeeNo: string
  project: string
  department: string
  position: string
  employmentType: EmploymentType
  phone: string
  joinDate: string
  status: string
}

export type PayrollStatus = '制表' | '人资负责人审核' | '财务负责人审核' | '主要负责人审批' | '已发布'

export interface PayrollBatch {
  id: number
  period: string
  project: string
  headcount: number
  baseSalary: number
  titleAllowance: number
  overtime: number
  prePerformance: number
  monthlyPerformance: number
  seniority: number
  siteAllowance: number
  annualPerformance: number
  other: number
  total: number
  status: PayrollStatus
  submittedBy: string
}

export interface Notice {
  id: number
  title: string
  category: string
  scope: string
  publishedAt: string
  readCount: number
  totalCount: number
  urgent: boolean
}

export interface SealUsage {
  id: number
  sealName: string
  count: number
  purpose: string
  department: string
  operator: string
  date: string
  approvedBy: string
  status: '待审批' | '已登记'
}

export interface PartyFeeRecord {
  id: number
  name: string
 base1: number
 fee1: number
 base2: number
 fee2: number
  base3: number
  fee3: number
  total: number
  quarter: string
}

export interface Condolence {
  id: number
  applicant: string
  category: string
  reason: string
  amount: number
  amountInWords: string
  projectUnionOpinion: string
  companyUnionOpinion: string
  status: string
  appliedAt: string
}

export interface Todo {
  id: number
  title: string
  module: string
  type: string
  deadline: string
  urgent: boolean
}

export interface Regulation {
  id: number
  docNo: string
  title: string
  category: string
  issuer: string
  date: string
  latest: boolean
  scope: string
}

export type ReceptionLevel = 'A' | 'B' | 'C'
export interface LeaveRecord {
  id: number
  applicant: string
  project: string
  type: '事假' | '病假' | '年假' | '调休' | '婚假' | '产假' | '护理假' | '其他'
  start: string
  end: string
  days: number
  reason: string
  status: '待审批' | '已批准' | '已驳回'
  appliedAt: string
}

export interface PartyMember {
  id: number
  name: string
  gender: '男' | '女'
  project: string
  org: string
  partyPost: string
  joinDate: string
  status: '正式党员' | '预备党员'
}

export interface Reception {
  id: number
  title: string
  level: ReceptionLevel
  category: '商务' | '外事' | '其他'
  guests: string
  guestCount: number
  date: string
  host: string
  department: string
  status: '方案待审' | '已接待' | '清单已归档'
  amount: number
  voucherNo: string
  details: { time: string; item: string; place: string; amount: number }[]
}

export interface WelfareItem {
  id: number
  category: string
  name: string
  spec: string
  quantity: number
  recipient: string
  department: string
  status: '已领取' | '未领取'
  signedAt: string
}

// ---- 人员花名册（对齐「荔园项目2026年7月人员花名册统计表」23 字段）----
export type Gender = '男' | '女'
export type EmploymentTypeFull = '正式工' | '临时工' | '劳务派遣'
export type RosterCategory = '正式工' | '临时工' | '后勤辅助'
export type Education = '本科' | '专科' | '中专' | '高中' | '技校' | '初中'
export type Political = '党员' | '群众' | '团员'
export type SalaryMechanism = '年薪制' | '岗位绩效制' | '固定合同'
export type ProfSeries = '无' | '业务员'
export type ContractRel = '示范建工' | '城建公司' | '临时工'

export interface RosterMember {
  id: number
  seq: number // 序号
  name: string // 姓名
  gender: Gender // 性别
  idCard: string // 身份证号
  phone: string // 手机号
  age: number // 年龄
  joinDate: string // 入职时间 yyyy-mm-dd
  employmentType: EmploymentTypeFull // 合同类型/用工形式
  education: Education // 最高学历
  school: string // 毕业院校
  major: string // 专业
  title: string // 职称
  political: Political // 政治面貌
  position: string // 当前岗位
  adminSeries: string // 行政职系
  profSeries: ProfSeries // 专业职系
  salaryMechanism: SalaryMechanism // 薪酬机制
  salaryGrade: string // 薪档
  monthlyPay: number // 标准/临时工月度实领标准（元/月）
  firstAdjust: string // 首次调整时间 YYYY.MM
  project: string // 当前项目/项目部
  status: string // 当前状态
  certificates: string // 资质证书
  contractRel: ContractRel // 合同关系
  category: RosterCategory // 派生分组：正式工/临时工/后勤辅助
}

/** 数值格：number 或以 = 开头的公式字符串（如 "=[岗位工资]+[奖金]"） */
export type NumLike = number | string

/** 薪档标准（工资制作的核心勾稽源：改这里 → 工资基数类自动重算） */
export interface GradeStandard {
  id: string // 唯一键（薪档 code）
  grade: string // 薪档名称，如 P2（一类）/ P5
  /* 基数类（工资记录按公式取数） */
  postSalaryBase: NumLike // 岗位工资基数
  titleAllowanceBase: NumLike // 专业技术职务津贴标准
  seniorityBase: NumLike // 年功工资基数（每年）
  siteBase: NumLike // 项目施工津贴基数
  dailyWageBase: NumLike // 日工资标准（日工资 = 日工资标准；岗位工资 = 日工资标准 × 岗位工日）
  /* 五险一金 / 个税系数（标准表配置，工资记录可选引用） */
  pensionRate: NumLike // 养老保险比例
  unemploymentRate: NumLike // 失业保险比例
  medicalRate: NumLike // 医疗保险比例
  housingFundRate: NumLike // 住房公积金比例
  annuityRate: NumLike // 企业年金比例
  largeMedicalRate: NumLike // 大额医保比例
  taxThreshold: NumLike // 个税起征点
  /* 派生标准 */
  netStandard: NumLike // 月度实领标准（对应花名册 monthlyPay）
}

/** 工资制作：单人单月工资明细（对齐「9月工资(加个税).xls」部门分表 31 列结构） */
export interface SalaryRecord {
  id: string // `${month}-${name}`
  month: string // 工资所属月 yyyy-MM
  department: string // 所在部门（分表对应单位）
  seq: number
  name: string
  salaryGrade: string // 薪档（从花名册按姓名回填，可改；决定标准表取数）
  /* 工资部分 */
  postDays: NumLike // 岗位工资-工日
  postSalary: NumLike // 岗位工资-金额
  titleAllowance: NumLike // 专业技术职务津贴
  otDays: NumLike // 加班-工日
  overtime: NumLike // 加班-金额
  bonus: NumLike // 奖金
  makeUp: NumLike // 补差工资
  seniority: NumLike // 年功工资
  siteAllowance: NumLike // 项目施工津贴
  dueTotal: NumLike // 应领工资额（默认公式，可改写）
  /* 专项扣除（五险一金） */
  pension: NumLike // 养老保险金
  unemployment: NumLike // 失业保险金
  medical: NumLike // 医疗保险金
  housingFund: NumLike // 住房公积金
  annuity: NumLike // 企业年金
  largeMedical: NumLike // 大额医保金
  /* 其他扣除 */
  otherDeduct: NumLike
  /* 专项附加扣除（留档，不直接扣款） */
  childEdu: NumLike
  continuingEdu: NumLike
  housingLoan: NumLike
  housingRent: NumLike
  elderlyCare: NumLike
  infantCare: NumLike
  /* 个税与实发 */
  tax: NumLike // 个人所得税
  taxAdjust: NumLike // 个人所得税调整
  deduct: NumLike // 扣款（其他）
  netTotal: NumLike // 实领工资额（默认公式，可改写）
  /* 附列 */
  monthPerf: NumLike // 月度绩效奖金（第30列）
  unionFee: NumLike // 会费（第31列）
  sign: string // 领款人签字
  /* 导入原值（用于编辑后差异核对） */
  importedDue?: number
  importedNet?: number
}

/** 绩效表（「绩效」工作表）：绩效激励计算 */
export interface PerfRecord {
  id: string
  month: string
  seq: number
  department: string
  name: string
  monthScore: string // 月度总成绩
  perfLevel: string // 绩效级别
  incentiveCoef: string // 激励系数
  sepPerf: NumLike // 当月绩效（如 9月绩效）
  augBase: NumLike // 上月基数（如 8月度基数）
  incentivePart: NumLike // 激励部分
  balanceCoef: string // 平衡系数
  coopCoef: string // 配合度系数
  finalIncentive: NumLike // 最终绩效激励额度
  totalPerf: NumLike // 月度绩效总数
}

/** 一个月的工资制作数据（上传整体替换） */
export interface SalaryMonthData {
  month: string
  importedAt: string
  records: SalaryRecord[]
  perf: PerfRecord[]
}

