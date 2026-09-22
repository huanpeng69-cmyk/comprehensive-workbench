import type { RosterMember } from './types'

/** 字段元信息：同时驱动表格列与新增/编辑表单 */
export interface RosterField {
  key: keyof RosterMember
  label: string
  type: 'text' | 'number' | 'date' | 'enum' | 'textarea'
  group: string
  width?: number
  required?: boolean
  /** 半封闭枚举：下拉 + 允许自由录入 */
  free?: boolean
  options?: string[]
}

export const GENDER_OPTIONS = ['男', '女']
export const EMPLOYMENT_OPTIONS = ['正式工', '临时工', '劳务派遣']
export const EDUCATION_OPTIONS = ['本科', '专科', '中专', '高中', '技校', '初中']
export const POLITICAL_OPTIONS = ['党员', '群众', '团员']
export const SALARY_MECH_OPTIONS = ['年薪制', '岗位绩效制', '固定合同']
export const PROF_SERIES_OPTIONS = ['无', '业务员']
export const CONTRACT_REL_OPTIONS = ['示范建工', '城建公司', '临时工']
export const CATEGORY_OPTIONS = ['正式工', '临时工', '后勤辅助']

export const SALARY_GRADE_OPTIONS = [
  'P1（一类）', 'P2（一类）', 'P3（一类）', 'P4（一类）', 'P5', 'P6',
  'P7', 'P8', 'P9', 'P10', 'P11', 'P12', 'P13', 'P14', 'P15'
]
export const ADMIN_SERIES_OPTIONS = [
  '项目经理', '项目班子副职', '项目班子副总师', '项目部门正职',
  '项目部门正职级', '项目部门副职级', '经验级', '入门级'
]
export const TITLE_OPTIONS = [
  '正高级工程师', '高级工程师', '工程师', '助理工程师', '技术员'
]
export const PROJECT_OPTIONS = [
  '荔园项目', '云岭项目部', '江湾项目部', '榕荫项目部', '南岸项目部'
]
export const STATUS_OPTIONS = [
  '正常在岗', '休假', '待岗', '借调', '离职'
]

/** 后勤辅助岗位关键字：命中当前岗位则归为「后勤辅助」 */
export const LOGISTICS_KEYWORDS = [
  '司机', '保洁', '厨师', '门卫', '后勤', '辅助', '杂工', '食堂',
  '帮厨', '保安', '养护', '炊事'
]

/** 用工形式 → 彩色标签类型 */
export const EMPLOYMENT_TAG: Record<string, string> = {
  正式工: 'success',
  临时工: 'warning',
  劳务派遣: 'info'
}
export const CATEGORY_TAG: Record<string, string> = {
  正式工: 'success',
  临时工: 'warning',
  后勤辅助: 'info'
}
export const GENDER_TAG: Record<string, string> = {
  男: '',
  女: 'danger'
}

/** 根据用工形式与当前岗位派生分组 */
export function deriveCategory(m: Pick<RosterMember, 'employmentType' | 'position'>): RosterMember['category'] {
  const pos = m.position || ''
  if (LOGISTICS_KEYWORDS.some((k) => pos.includes(k))) return '后勤辅助'
  if (m.employmentType === '正式工') return '正式工'
  return '临时工'
}

export const ROSTER_FIELDS: RosterField[] = [
  // 基本信息
  { key: 'name', label: '姓名', type: 'text', group: '基本信息', width: 100, required: true },
  { key: 'gender', label: '性别', type: 'enum', group: '基本信息', width: 64, required: true, options: GENDER_OPTIONS },
  { key: 'idCard', label: '身份证号', type: 'text', group: '基本信息', width: 170, required: true },
  { key: 'phone', label: '手机号', type: 'text', group: '基本信息', width: 130 },
  { key: 'age', label: '年龄', type: 'number', group: '基本信息', width: 64 },
  { key: 'joinDate', label: '入职时间', type: 'date', group: '基本信息', width: 110 },
  // 用工与合同
  { key: 'employmentType', label: '用工形式', type: 'enum', group: '用工与合同', width: 100, required: true, options: EMPLOYMENT_OPTIONS },
  { key: 'contractRel', label: '合同关系', type: 'enum', group: '用工与合同', width: 100, options: CONTRACT_REL_OPTIONS },
  { key: 'political', label: '政治面貌', type: 'enum', group: '用工与合同', width: 90, options: POLITICAL_OPTIONS },
  { key: 'position', label: '当前岗位', type: 'text', group: '用工与合同', width: 130 },
  { key: 'adminSeries', label: '行政职系', type: 'enum', group: '用工与合同', width: 130, free: true, options: ADMIN_SERIES_OPTIONS },
  { key: 'profSeries', label: '专业职系', type: 'enum', group: '用工与合同', width: 90, options: PROF_SERIES_OPTIONS },
  // 学历与职称
  { key: 'education', label: '最高学历', type: 'enum', group: '学历与职称', width: 90, options: EDUCATION_OPTIONS },
  { key: 'school', label: '毕业院校', type: 'text', group: '学历与职称', width: 160 },
  { key: 'major', label: '专业', type: 'text', group: '学历与职称', width: 150 },
  { key: 'title', label: '职称', type: 'enum', group: '学历与职称', width: 120, free: true, options: TITLE_OPTIONS },
  // 薪酬
  { key: 'salaryMechanism', label: '薪酬机制', type: 'enum', group: '薪酬', width: 110, options: SALARY_MECH_OPTIONS },
  { key: 'salaryGrade', label: '薪档', type: 'enum', group: '薪酬', width: 100, free: true, options: SALARY_GRADE_OPTIONS },
  { key: 'monthlyPay', label: '月度实领标准', type: 'number', group: '薪酬', width: 120 },
  { key: 'firstAdjust', label: '首次调整时间', type: 'text', group: '薪酬', width: 110 },
  // 项目与状态
  { key: 'project', label: '当前项目', type: 'enum', group: '项目与状态', width: 110, options: PROJECT_OPTIONS },
  { key: 'status', label: '当前状态', type: 'enum', group: '项目与状态', width: 90, options: STATUS_OPTIONS },
  { key: 'certificates', label: '资质证书', type: 'textarea', group: '项目与状态', width: 220 }
]

/** 表单分组顺序 */
export const ROSTER_GROUPS = ['基本信息', '用工与合同', '学历与职称', '薪酬', '项目与状态']
