import * as XLSX from 'xlsx'
import type { SalaryRecord, SalaryMonthData, PerfRecord } from '@/mock/types'
import { DUE_FORMULA, NET_FORMULA } from '@/mock/salaryOptions'

/** 部门分表固定列（公司统一模板，三层表头后数据列固定） */
const C = {
  seq: 0,
  name: 1,
  postDays: 2,
  postSalary: 3,
  title: 4,
  otDays: 5,
  overtime: 6,
  bonus: 7,
  makeUp: 8,
  seniority: 9,
  site: 10,
  due: 11,
  pension: 12,
  unemployment: 13,
  medical: 14,
  housingFund: 15,
  annuity: 16,
  largeMedical: 17,
  childEdu: 18,
  continuingEdu: 19,
  housingLoan: 20,
  housingRent: 21,
  elderlyCare: 22,
  infantCare: 23,
  tax: 24,
  taxAdjust: 25,
  deduct: 26,
  net: 27,
  sign: 28,
  monthPerf: 30,
  unionFee: 31
} as const

function toNum(v: unknown): number {
  const s = String(v ?? '')
    .replace(/[,\s￥元]/g, '')
    .trim()
  if (!s) return 0
  const n = Number(s)
  return Number.isFinite(n) ? n : 0
}

/** 从标题行提取工资月份：如「示范建工2026年(9）」→ 2026-09 */
function pickMonth(rows: string[][]): string | null {
  for (let i = 0; i < Math.min(4, rows.length); i++) {
    for (const cell of rows[i]) {
      const t = String(cell)
      const y = /20\d{2}/.exec(t)
      const m = /[（(]\s*(\d{1,2})\s*[）)]/.exec(t)
      if (y && m) return `${y[0]}-${m[1].padStart(2, '0')}`
    }
  }
  return null
}

/** 从前几行提取单位名：如「单位：合同管理部」→ 合同管理部 */
function pickDepartment(rows: string[][]): string {
  for (let i = 0; i < Math.min(4, rows.length); i++) {
    for (const cell of rows[i]) {
      const t = String(cell).trim()
      if (t.startsWith('单位：') || t.startsWith('单位:')) return t.slice(3).trim()
    }
  }
  return ''
}

const isPersonName = (s: string) => !!s && !/(部|公司|合计|小计|总计|制表|审核|审批)$/.test(s)

/**
 * 解析工资 Excel（.xls/.xlsx）：
 * - 部门分表（表头含「应领工资额」）：解析个人 31 列工资明细，部门名取「单位：xxx」
 * - 汇总表（数据行是部门名）自动跳过；「勿动」辅助表格式不同自动跳过
 * - 绩效表（表头含「绩效级别」）：解析绩效激励明细
 * - 应领/实领写入默认公式，原表数值存 importedDue/importedNet 供差异核对
 */
export async function parseSalaryXlsx(
  file: File,
  rosterGrades?: Record<string, string>
): Promise<SalaryMonthData> {
  const buffer = await file.arrayBuffer()
  const wb = XLSX.read(buffer, { type: 'array' })
  if (!wb.SheetNames.length) throw new Error('Excel 文件中没有任何工作表')

  let month: string | null = null
  const records: SalaryRecord[] = []
  const perf: PerfRecord[] = []
  const skipped: string[] = []

  for (const sheetName of wb.SheetNames) {
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[sheetName], { header: 1, raw: false, defval: '' }) as string[][]
    if (!rows.length) continue
    month = month || pickMonth(rows)

    // —— 部门工资分表：表头行同时含「姓  名」与「应领工资额」 ——
    const hIdx = rows.findIndex((r) => {
      const cells = r.map((c) => String(c).replace(/\s/g, ''))
      return cells.some((c) => c.startsWith('姓')) && cells.includes('应领工资额')
    })
    if (hIdx >= 0) {
      const department = pickDepartment(rows) || sheetName
      // 汇总表跳过：表名含「汇总」，或前 3 个非空姓名行都是部门/机构名（非人名）
      const firstNames: string[] = []
      for (let r = hIdx + 1; r < rows.length && firstNames.length < 3; r++) {
        const nm = String(rows[r][C.name] ?? '').trim()
        if (nm) firstNames.push(nm)
      }
      const looksSummary =
        sheetName.includes('汇总') ||
        (firstNames.length > 0 && firstNames.every((nm) => !isPersonName(nm)))
      if (looksSummary) {
        skipped.push(sheetName)
        continue
      }
      for (let r = hIdx + 1; r < rows.length; r++) {
        const row = rows[r]
        const name = String(row[C.name] ?? '').trim()
        if (!name || !isPersonName(name)) continue
        const due = toNum(row[C.due])
        const net = toNum(row[C.net])
        const empty = !due && !net && !toNum(row[C.postSalary])
        if (empty) continue
        records.push({
          id: '',
          month: '',
          department,
          seq: toNum(row[C.seq]),
          name,
          salaryGrade: (rosterGrades && rosterGrades[name]) || '',
          postDays: toNum(row[C.postDays]),
          postSalary: toNum(row[C.postSalary]),
          titleAllowance: toNum(row[C.title]),
          otDays: toNum(row[C.otDays]),
          overtime: toNum(row[C.overtime]),
          bonus: toNum(row[C.bonus]),
          makeUp: toNum(row[C.makeUp]),
          seniority: toNum(row[C.seniority]),
          siteAllowance: toNum(row[C.site]),
          dueTotal: DUE_FORMULA,
          pension: toNum(row[C.pension]),
          unemployment: toNum(row[C.unemployment]),
          medical: toNum(row[C.medical]),
          housingFund: toNum(row[C.housingFund]),
          annuity: toNum(row[C.annuity]),
          largeMedical: toNum(row[C.largeMedical]),
          otherDeduct: 0,
          childEdu: toNum(row[C.childEdu]),
          continuingEdu: toNum(row[C.continuingEdu]),
          housingLoan: toNum(row[C.housingLoan]),
          housingRent: toNum(row[C.housingRent]),
          elderlyCare: toNum(row[C.elderlyCare]),
          infantCare: toNum(row[C.infantCare]),
          tax: toNum(row[C.tax]),
          taxAdjust: toNum(row[C.taxAdjust]),
          deduct: toNum(row[C.deduct]),
          netTotal: NET_FORMULA,
          monthPerf: toNum(row[C.monthPerf]),
          unionFee: toNum(row[C.unionFee]),
          sign: String(row[C.sign] ?? '').trim(),
          importedDue: due,
          importedNet: net
        })
      }
      continue
    }

    // —— 绩效表：表头含「绩效级别」 ——
    const pIdx = rows.findIndex((r) => r.map((c) => String(c).replace(/\s/g, '')).includes('绩效级别'))
    if (pIdx >= 0) {
      const head = rows[pIdx].map((c) => String(c).replace(/\s/g, ''))
      const findCol = (match: (h: string) => boolean) => head.findIndex(match)
      const cSeq = findCol((h) => h === '序号')
      const cDept = findCol((h) => h.startsWith('部'))
      const cName = findCol((h) => h.startsWith('姓'))
      const cScore = findCol((h) => h.includes('月度总成绩'))
      const cLevel = head.indexOf('绩效级别')
      const cCoef = findCol((h) => h === '激励系数')
      const cSep = findCol((h) => /^\d{1,2}月绩效$/.test(h))
      const cAug = findCol((h) => /^\d{1,2}月度基数$/.test(h))
      const cInc = findCol((h) => h === '激励部分')
      const cBal = findCol((h) => h === '平衡系数')
      const cCoop = findCol((h) => h === '配合度系数')
      const cFinal = findCol((h) => h === '最终绩效激励额度')
      const cTotal = findCol((h) => h === '月度绩效总数')
      for (let r = pIdx + 1; r < rows.length; r++) {
        const row = rows[r]
        const name = String(row[cName] ?? '').trim()
        if (!name || !isPersonName(name)) continue
        perf.push({
          id: '',
          month: '',
          seq: cSeq >= 0 ? toNum(row[cSeq]) : perf.length + 1,
          department: cDept >= 0 ? String(row[cDept] ?? '').trim() : '',
          name,
          monthScore: cScore >= 0 ? String(row[cScore] ?? '').trim() : '',
          perfLevel: cLevel >= 0 ? String(row[cLevel] ?? '').trim() : '',
          incentiveCoef: cCoef >= 0 ? String(row[cCoef] ?? '').trim() : '',
          sepPerf: cSep >= 0 ? toNum(row[cSep]) : 0,
          augBase: cAug >= 0 ? toNum(row[cAug]) : 0,
          incentivePart: cInc >= 0 ? toNum(row[cInc]) : 0,
          balanceCoef: cBal >= 0 ? String(row[cBal] ?? '').trim() : '',
          coopCoef: cCoop >= 0 ? String(row[cCoop] ?? '').trim() : '',
          finalIncentive: cFinal >= 0 ? toNum(row[cFinal]) : 0,
          totalPerf: cTotal >= 0 ? toNum(row[cTotal]) : 0
        })
      }
    }
  }

  if (!records.length && !perf.length) {
    throw new Error('未找到可解析的工资分表或绩效表，请确认文件格式')
  }
  if (!month) throw new Error('未能从表中识别工资月份（标题行需含「2026年(9)」样式）')
  const finalMonth = month
  for (const r of records) {
    r.month = finalMonth
    r.id = `${finalMonth}-${r.name}`
  }
  for (const p of perf) {
    p.month = finalMonth
    p.id = `${finalMonth}-${p.name}`
  }
  void skipped
  return { month: finalMonth, importedAt: new Date().toISOString(), records, perf }
}
