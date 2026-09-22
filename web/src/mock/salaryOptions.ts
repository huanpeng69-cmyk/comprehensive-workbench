import type { SalaryRecord, GradeStandard } from './types'

/** 工资字段中文标签（编辑表单与公式引用 [标签] 共用） */
export const SALARY_FIELD_LABELS: Partial<Record<keyof SalaryRecord, string>> = {
  postDays: '岗位工日',
  postSalary: '岗位工资',
  titleAllowance: '专业技术职务津贴',
  otDays: '加班工日',
  overtime: '加班费',
  bonus: '奖金',
  makeUp: '补差工资',
  seniority: '年功工资',
  siteAllowance: '项目施工津贴',
  dueTotal: '应领工资额',
  pension: '养老保险金',
  unemployment: '失业保险金',
  medical: '医疗保险金',
  housingFund: '住房公积金',
  annuity: '企业年金',
  largeMedical: '大额医保金',
  otherDeduct: '其他扣除',
  childEdu: '子女教育',
  continuingEdu: '继续教育',
  housingLoan: '房贷利息',
  housingRent: '住房租金',
  elderlyCare: '赡养老人',
  infantCare: '婴幼儿照护费',
  tax: '个人所得税',
  taxAdjust: '个税调整',
  deduct: '扣款',
  netTotal: '实领工资额',
  monthPerf: '月度绩效',
  unionFee: '会费'
}

/** 公式引用：[中文标签] → 字段 key */
export const SALARY_LABEL_TO_KEY: Record<string, keyof SalaryRecord> = Object.fromEntries(
  Object.entries(SALARY_FIELD_LABELS).map(([k, v]) => [v as string, k as keyof SalaryRecord])
)

/** 应领工资额默认公式 */
export const DUE_FORMULA = '=[岗位工资]+[专业技术职务津贴]+[加班费]+[奖金]+[补差工资]+[年功工资]+[项目施工津贴]'
/** 实领工资额默认公式（五险一金+个税+扣款） */
export const NET_FORMULA =
  '=[应领工资额]-[养老保险金]-[失业保险金]-[医疗保险金]-[住房公积金]-[企业年金]-[大额医保金]-[个人所得税]-[个税调整]-[扣款]'

/* ---------------- 薪档标准表 ---------------- */
export const GRADE_STD_FIELD_LABELS: Record<keyof GradeStandard, string> = {
  id: '编号',
  grade: '薪档',
  postSalaryBase: '岗位工资基数',
  titleAllowanceBase: '职务津贴标准',
  seniorityBase: '年功基数',
  siteBase: '施工津贴基数',
  dailyWageBase: '日工资标准',
  pensionRate: '养老比例',
  unemploymentRate: '失业比例',
  medicalRate: '医疗比例',
  housingFundRate: '公积金比例',
  annuityRate: '年金比例',
  largeMedicalRate: '大额医保比例',
  taxThreshold: '个税起征点',
  netStandard: '月度实领标准'
}
/** 公式引用：[薪档标准.中文标签] → 标准表字段 key */
export const GRADE_STD_LABEL_TO_KEY: Record<string, keyof GradeStandard> = Object.fromEntries(
  Object.entries(GRADE_STD_FIELD_LABELS)
    .filter(([k]) => k !== 'id' && k !== 'grade')
    .map(([k, v]) => [v as string, k as keyof GradeStandard])
)
/** 基数类字段（「按薪档标准重算」时改写为公式引用标准表 + 工日勾稽）
 * 工日勾稽：岗位工日 = 应出工日 - 请假扣减；岗位工资 = 日工资标准 × 岗位工日 */
export const BASE_FORMULA_BY_FIELD: Partial<Record<keyof SalaryRecord, string>> = {
  postDays: '=[应出工日]-[请假.当月天数]',
  postSalary: '=[薪档标准.日工资标准]*[岗位工日]',
  titleAllowance: '=[薪档标准.职务津贴标准]',
  seniority: '=[薪档标准.年功基数]',
  siteAllowance: '=[薪档标准.施工津贴基数]'
}
