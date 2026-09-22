import * as XLSX from 'xlsx'
import type { RosterMember, EmploymentTypeFull, Education, Political, SalaryMechanism, ProfSeries, ContractRel, Gender } from '@/mock/types'
import { deriveCategory } from '@/mock/rosterOptions'

/** 中文表头 → 字段 key（兼容多种写法） */
const HEADER_MAP: Record<string, keyof RosterMember> = {
  序号: 'seq',
  姓名: 'name',
  性别: 'gender',
  身份证号: 'idCard',
  手机号: 'phone',
  年龄: 'age',
  入职时间: 'joinDate',
  合同类型: 'employmentType',
  用工形式: 'employmentType',
  最高学历: 'education',
  毕业院校: 'school',
  专业: 'major',
  职称: 'title',
  政治面貌: 'political',
  当前岗位: 'position',
  行政职系: 'adminSeries',
  专业职系: 'profSeries',
  薪酬机制: 'salaryMechanism',
  薪档: 'salaryGrade',
  '标准/临时工月度实领标准': 'monthlyPay',
  月度实领标准: 'monthlyPay',
  首次调整时间: 'firstAdjust',
  当前项目: 'project',
  当前状态: 'status',
  资质证书: 'certificates',
  合同关系: 'contractRel'
}

const EMPLOYMENT_VALUES: EmploymentTypeFull[] = ['正式工', '临时工', '劳务派遣']
const EDUCATION_VALUES: Education[] = ['本科', '专科', '中专', '高中', '技校', '初中']
const POLITICAL_VALUES: Political[] = ['党员', '群众', '团员']
const SALARY_MECH_VALUES: SalaryMechanism[] = ['年薪制', '岗位绩效制', '固定合同']
const PROF_SERIES_VALUES: ProfSeries[] = ['无', '业务员']
const CONTRACT_REL_VALUES: ContractRel[] = ['示范建工', '城建公司', '临时工']
const GENDER_VALUES: Gender[] = ['男', '女']

function pick<T extends string>(value: string, allowed: T[], fallback: T): T {
  const v = (value || '').trim()
  return (allowed as string[]).includes(v) ? (v as T) : fallback
}

function toNumber(v: unknown): number {
  if (v === '' || v == null) return 0
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

/** 规范化日期：把 2013/6/1 之类转成 2013-06-01 */
function normalizeDate(v: unknown): string {
  if (!v) return ''
  let s = String(v).trim()
  s = s.replace(/\//g, '-')
  // 仅保留 yyyy-mm-dd 形式
  const m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (m) return `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`
  return s
}

/**
 * 解析上传的花名册 Excel，返回 RosterMember[]。
 * - 遍历所有表头含「姓名」的工作表（花名册可能按 正式工/临时工/后勤 拆成多张表），合并解析
 * - 按身份证号跨表去重（分类子表与总表会有重复），无身份证号时用姓名兜底
 * - 自动定位各表表头行，身份证号按文本保真，入职时间规范化，分组自动派生
 */
export async function parseRosterXlsx(file: File): Promise<RosterMember[]> {
  const buffer = await file.arrayBuffer()
  const wb = XLSX.read(buffer, { type: 'array' })
  if (!wb.SheetNames.length) throw new Error('Excel 文件中没有任何工作表')

  const result: RosterMember[] = []
  const seen = new Set<string>()
  let seqFallback = 1

  for (const sheetName of wb.SheetNames) {
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[sheetName], { header: 1, raw: false, defval: '' }) as string[][]
    // 定位该表的表头行
    const headerIdx = rows.findIndex((r) => r.some((c) => String(c).trim() === '姓名'))
    if (headerIdx < 0) continue
    const header = rows[headerIdx].map((c) => String(c).trim())

    // 建立 表头 → 列索引 映射
    const colMap: Partial<Record<keyof RosterMember, number>> = {}
    header.forEach((h, i) => {
      const key = HEADER_MAP[h]
      if (key && colMap[key] === undefined) colMap[key] = i
    })
    if (colMap.name === undefined) continue

    const get = (row: string[], key: keyof RosterMember): string => {
      const i = colMap[key]
      if (i == null) return ''
      return row[i] != null ? String(row[i]).trim() : ''
    }

    for (let r = headerIdx + 1; r < rows.length; r++) {
      const row = rows[r]
      const name = get(row, 'name')
      if (!name) continue // 跳过空行

      const idCard = get(row, 'idCard')
      // 跨表去重：身份证号优先，无身份证号用姓名兜底
      const dedupeKey = idCard || `name:${name}`
      if (seen.has(dedupeKey)) continue
      seen.add(dedupeKey)

      const employmentType = pick(get(row, 'employmentType'), EMPLOYMENT_VALUES, '临时工')
      const position = get(row, 'position')
      const member: RosterMember = {
        id: Date.now() + result.length,
        seq: colMap.seq != null ? toNumber(get(row, 'seq')) || seqFallback++ : seqFallback++,
        name,
        gender: pick(get(row, 'gender'), GENDER_VALUES, '男'),
        idCard,
        phone: get(row, 'phone'),
        age: toNumber(get(row, 'age')),
        joinDate: normalizeDate(get(row, 'joinDate')),
        employmentType,
        education: pick(get(row, 'education'), EDUCATION_VALUES, '高中'),
        school: get(row, 'school'),
        major: get(row, 'major'),
        title: get(row, 'title'),
        political: pick(get(row, 'political'), POLITICAL_VALUES, '群众'),
        position,
        adminSeries: get(row, 'adminSeries'),
        profSeries: pick(get(row, 'profSeries'), PROF_SERIES_VALUES, '无'),
        salaryMechanism: pick(get(row, 'salaryMechanism'), SALARY_MECH_VALUES, '岗位绩效制'),
        salaryGrade: get(row, 'salaryGrade'),
        monthlyPay: toNumber(get(row, 'monthlyPay')),
        firstAdjust: get(row, 'firstAdjust'),
        project: get(row, 'project') || '荔园项目',
        status: get(row, 'status') || '正常在岗',
        certificates: get(row, 'certificates'),
        contractRel: pick(get(row, 'contractRel'), CONTRACT_REL_VALUES, '示范建工'),
        category: '正式工'
      }
      member.category = deriveCategory(member)
      result.push(member)
    }
  }

  if (!result.length) throw new Error('未找到表头含「姓名」的人员花名册工作表，或解析后没有任何有效记录')
  return result
}
