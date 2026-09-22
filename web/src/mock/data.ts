import type {
  Staff, PayrollBatch, Notice, SealUsage, PartyFeeRecord, Condolence, Todo, Regulation,
  LeaveRecord, PartyMember, Reception, WelfareItem, RosterMember
} from './types'
import { deriveCategory } from './rosterOptions'

export const PROJECTS = ['云岭项目部', '江湾项目部', '榕荫项目部', '南岸项目部', '荔园项目部']

export const DEPARTMENTS = ['综合管理部', '工程管理部', '合同管理部', '安全环保部', '质检部', '设备物资部', '财务管理部', '测量部']

export const staffList: Staff[] = [
  { id: 1, name: '陈国栋', employeeNo: 'SN001', project: '云岭项目部', department: '综合管理部', position: '项目经理', employmentType: '正式职工', phone: '13800000001', joinDate: '2019-08-07', status: '在职' },
  { id: 2, name: '李明远', employeeNo: 'SN002', project: '云岭项目部', department: '综合管理部', position: '副经理', employmentType: '正式职工', phone: '13800000002', joinDate: '2019-08-07', status: '在职' },
  { id: 3, name: '王海涛', employeeNo: 'SN003', project: '云岭项目部', department: '质检部', position: '质检部部长', employmentType: '正式职工', phone: '13800000003', joinDate: '2021-01-01', status: '在职' },
  { id: 4, name: '赵启明', employeeNo: 'SN004', project: '云岭项目部', department: '施工部', position: '施工部部长', employmentType: '正式职工', phone: '13800000004', joinDate: '2021-01-01', status: '在职' },
  { id: 5, name: '刘志强', employeeNo: 'SN005', project: '云岭项目部', department: '合同管理部', position: '合同部部长', employmentType: '正式职工', phone: '13800000005', joinDate: '2021-01-01', status: '在职' },
  { id: 6, name: '孙长海', employeeNo: 'SN006', project: '云岭项目部', department: '设备物资部', position: '司机', employmentType: '临时工', phone: '13800000006', joinDate: '2022-03-01', status: '在职' },
  { id: 7, name: '周晓琳', employeeNo: 'SN007', project: '云岭项目部', department: '综合管理部', position: '综合部副部长', employmentType: '正式职工', phone: '13800000007', joinDate: '2021-01-01', status: '在职' },
  { id: 8, name: '郑建国', employeeNo: 'SN008', project: '云岭项目部', department: '工程管理部', position: '协调经理', employmentType: '返聘', phone: '13800000008', joinDate: '2021-04-01', status: '在职' },
  { id: 9, name: '吴俊杰', employeeNo: 'SN009', project: '江湾项目部', department: '安全环保部', position: '安全总监', employmentType: '正式职工', phone: '13800000009', joinDate: '2023-02-22', status: '在职' },
  { id: 10, name: '徐鹏', employeeNo: 'SN010', project: '江湾项目部', department: '安全环保部', position: '安全环保部部长', employmentType: '正式职工', phone: '13800000010', joinDate: '2024-01-01', status: '在职' },
  { id: 11, name: '何伟', employeeNo: 'SN011', project: '榕荫项目部', department: '施工部', position: '施工部部长', employmentType: '正式职工', phone: '13800000011', joinDate: '2022-09-01', status: '在职' },
  { id: 12, name: '林涛', employeeNo: 'SN012', project: '榕荫项目部', department: '综合管理部', position: '综合部副部长', employmentType: '正式职工', phone: '13800000012', joinDate: '2022-09-01', status: '在职' },
  { id: 13, name: '黄浩然', employeeNo: 'SN013', project: '江湾项目部', department: '工程管理部', position: '施工员', employmentType: '外聘', phone: '13800000013', joinDate: '2024-06-01', status: '在职' },
  { id: 14, name: '高峰', employeeNo: 'SN014', project: '云岭项目部', department: '设备物资部', position: '物资管理员', employmentType: '外聘', phone: '13800000014', joinDate: '2024-09-01', status: '在职' },
  { id: 15, name: '方静', employeeNo: 'SN015', project: '云岭项目部', department: '财务管理部', position: '会计', employmentType: '正式职工', phone: '13800000015', joinDate: '2022-05-01', status: '在职' }
]

export const payrollBatches: PayrollBatch[] = [
  { id: 1, period: '2026-08', project: '云岭项目部', headcount: 31, baseSalary: 133195, titleAllowance: 758, overtime: 14871, prePerformance: 2029, monthlyPerformance: 0, seniority: 22995, siteAllowance: 173848, annualPerformance: 0, other: 0, total: 346696, status: '主要负责人审批', submittedBy: '方静' },
  { id: 2, period: '2026-08', project: '江湾项目部', headcount: 24, baseSalary: 108432, titleAllowance: 640, overtime: 9210, prePerformance: 1560, monthlyPerformance: 8400, seniority: 14200, siteAllowance: 98600, annualPerformance: 0, other: 1200, total: 242042, status: '财务负责人审核', submittedBy: '方静' },
  { id: 3, period: '2026-08', project: '榕荫项目部', headcount: 18, baseSalary: 81200, titleAllowance: 480, overtime: 6400, prePerformance: 900, monthlyPerformance: 6200, seniority: 9800, siteAllowance: 74100, annualPerformance: 0, other: 0, total: 179180, status: '人资负责人审核', submittedBy: '方静' },
  { id: 4, period: '2026-07', project: '云岭项目部', headcount: 31, baseSalary: 131800, titleAllowance: 758, overtime: 13200, prePerformance: 2029, monthlyPerformance: 0, seniority: 22800, siteAllowance: 171200, annualPerformance: 0, other: 0, total: 341587, status: '已发布', submittedBy: '方静' },
  { id: 5, period: '2026-07', project: '南岸项目部', headcount: 12, baseSalary: 54300, titleAllowance: 320, overtime: 4100, prePerformance: 600, monthlyPerformance: 3800, seniority: 6400, siteAllowance: 49500, annualPerformance: 0, other: 0, total: 118020, status: '已发布', submittedBy: '方静' }
]

export const notices: Notice[] = [
  { id: 1, title: '关于开展2026年中秋国庆期间安全生产检查的通知', category: '通知', scope: '各项目部、各部门', publishedAt: '2026-09-15 09:30', readCount: 42, totalCount: 56, urgent: true },
  { id: 2, title: '关于印发《南方公司出差旅费报销管理办法（2025版）》的宣贯通知', category: '制度宣贯', scope: '各项目部、各部门', publishedAt: '2026-09-12 14:10', readCount: 38, totalCount: 56, urgent: false },
  { id: 3, title: '关于组织2026年第三季度工会活动的通知', category: '工会活动', scope: '全体会员', publishedAt: '2026-09-10 16:00', readCount: 51, totalCount: 56, urgent: false },
  { id: 4, title: '云岭项目部周例会会议纪要（2026年第36期）', category: '会议纪要', scope: '项目部各部门', publishedAt: '2026-09-08 18:20', readCount: 29, totalCount: 45, urgent: false },
  { id: 5, title: '关于2026年防暑费发放的温馨提示（6-10月）', category: '温馨提示', scope: '全体员工', publishedAt: '2026-06-05 10:00', readCount: 55, totalCount: 56, urgent: false }
]

export const sealUsages: SealUsage[] = [
  { id: 1, sealName: '项目部公章', count: 2, purpose: '投标文件用章', department: '合同管理部', operator: '刘志强', date: '2026-09-16', approvedBy: '陈国栋', status: '已登记' },
  { id: 2, sealName: '项目部公章', count: 1, purpose: '证明材料用章', department: '综合管理部', operator: '周晓琳', date: '2026-09-15', approvedBy: '李明远', status: '已登记' },
  { id: 3, sealName: '财务专用章', count: 1, purpose: '银行开户变更', department: '财务管理部', operator: '方静', date: '2026-09-14', approvedBy: '陈国栋', status: '已登记' },
  { id: 4, sealName: '项目部公章', count: 3, purpose: '分包合同用章', department: '合同管理部', operator: '刘志强', date: '2026-09-12', approvedBy: '陈国栋', status: '已登记' },
  { id: 5, sealName: '党支部印章', count: 1, purpose: '党员证明用章', department: '党支部', operator: '李明远', date: '2026-09-10', approvedBy: '陈国栋', status: '已登记' },
  { id: 6, sealName: '项目部公章', count: 1, purpose: '资质升级材料用章', department: '工程管理部', operator: '郑建国', date: '2026-09-17', approvedBy: '', status: '待审批' }
]

export const partyFees: PartyFeeRecord[] = [
  { id: 1, name: '陈国栋', base1: 2917.72, fee1: 14.59, base2: 2786.88, fee2: 13.93, base3: 2841.88, fee3: 14.21, total: 56.94, quarter: '2026-Q3' },
  { id: 2, name: '李明远', base1: 2311.57, fee1: 11.56, base2: 2193.88, fee2: 10.97, base3: 2248.88, fee3: 11.24, total: 45.02, quarter: '2026-Q3' },
  { id: 3, name: '王海涛', base1: 2311.98, fee1: 11.56, base2: 2191.88, fee2: 10.96, base3: 2246.88, fee3: 11.23, total: 44.99, quarter: '2026-Q3' },
  { id: 4, name: '赵启明', base1: 3857.26, fee1: 38.57, base2: 4516.94, fee2: 45.17, base3: 3055.94, fee3: 30.56, total: 158.30, quarter: '2026-Q3' },
  { id: 5, name: '韩雪松', base1: 4443.04, fee1: 44.43, base2: 3698.72, fee2: 36.99, base3: 2987.72, fee3: 14.94, total: 145.13, quarter: '2026-Q3' },
  { id: 6, name: '曹磊', base1: 3465.81, fee1: 34.66, base2: 3820.64, fee2: 38.21, base3: 1820.64, fee3: 9.10, total: 81.97, quarter: '2026-Q3' }
]

export const condolences: Condolence[] = [
  { id: 1, applicant: '周晓琳', category: '生育慰问', reason: '2026年8月生育一子', amount: 1000, amountInWords: '人民币壹仟元', projectUnionOpinion: '同意', companyUnionOpinion: '同意', status: '已发放', appliedAt: '2026-09-02' },
  { id: 2, applicant: '黄浩然', category: '困难帮扶', reason: '家属重病住院', amount: 2000, amountInWords: '人民币贰仟元', projectUnionOpinion: '同意', companyUnionOpinion: '', status: '二级单位工会审批中', appliedAt: '2026-09-14' },
  { id: 3, applicant: '林涛', category: '退休慰问', reason: '光荣退休', amount: 1500, amountInWords: '人民币壹仟伍佰元', projectUnionOpinion: '同意', companyUnionOpinion: '同意', status: '已发放', appliedAt: '2026-08-28' }
]

export const todos: Todo[] = [
  { id: 1, title: '云岭项目部 2026-08 工资发放审批', module: '人力', type: '审批', deadline: '2026-09-20', urgent: true },
  { id: 2, title: '关于开展2026年中秋国庆期间安全生产检查的通知 - 已读回执', module: '办公室', type: '阅读', deadline: '2026-09-19', urgent: true },
  { id: 3, title: '榕荫项目部 2026-08 工资发放审批', module: '人力', type: '审批', deadline: '2026-09-21', urgent: false },
  { id: 4, title: '黄浩然 困难帮扶慰问金审批', module: '工会', type: '审批', deadline: '2026-09-22', urgent: false },
  { id: 5, title: '云岭项目部周例会纪要 - 24小时异议期', module: '办公室', type: '阅读', deadline: '2026-09-18', urgent: true },
  { id: 6, title: '2026年第三季度党费收缴', module: '党建', type: '办理', deadline: '2026-09-30', urgent: false }
]

export const regulations: Regulation[] = [
  { id: 1, docNo: '集团〔2024〕126号', title: '二级单位及项目部薪酬分配管理办法（2024版）', category: '人力薪酬', issuer: '示范建工集团', date: '2024-07-31', latest: true, scope: '公司各单位、直属项目部' },
  { id: 2, docNo: '集团〔2024〕124号', title: '二级单位及项目绩效薪酬管理指导意见', category: '人力薪酬', issuer: '示范建工集团', date: '2024-07-31', latest: true, scope: '公司各单位、直属项目部' },
  { id: 3, docNo: '集团〔2024〕128号', title: '考勤和休假管理办法（2024版）', category: '人力薪酬', issuer: '示范建工集团', date: '2024-07-31', latest: true, scope: '公司各单位、直属项目部' },
  { id: 4, docNo: '集团〔2024〕127号', title: '员工职业发展管理办法（2024版）', category: '人力薪酬', issuer: '示范建工集团', date: '2024-07-31', latest: true, scope: '公司各单位、直属项目部' },
  { id: 5, docNo: '集团发〔2021〕304号', title: '员工职（执）业资格证书管理办法（2021版）', category: '人力薪酬', issuer: '示范建工集团', date: '2021-12-01', latest: true, scope: '公司各分公司、总部各部门' },
  { id: 6, docNo: '集团人事〔2026〕8号', title: '关于调整项目非年薪制员工岗位工资标准的通知', category: '人力薪酬', issuer: '示范建工集团', date: '2026-03-01', latest: true, scope: '公司各单位、直属项目部' },
  { id: 7, docNo: '集团办〔2024〕49号', title: '公务用车管理办法（2024版）', category: '办公行政', issuer: '示范建工集团', date: '2024-06-01', latest: true, scope: '公司各单位、总部各部门' },
  { id: 8, docNo: '分公司财管〔2025〕1号', title: '南方公司出差旅费报销管理办法（2025版）', category: '财务审计', issuer: '示范建工集团南方公司', date: '2025-01-15', latest: true, scope: '南方公司各项目部、各部门' }
]
export const leaveRecords: LeaveRecord[] = [
  { id: 1, applicant: '王海涛', project: '云岭项目部', type: '年假', start: '2026-09-22', end: '2026-09-24', days: 3, reason: '回乡探亲', status: '已批准', appliedAt: '2026-09-15 10:20' },
  { id: 2, applicant: '赵启明', project: '云岭项目部', type: '事假', start: '2026-09-19', end: '2026-09-19', days: 1, reason: '办理证件', status: '待审批', appliedAt: '2026-09-17 08:45' },
  { id: 3, applicant: '孙长海', project: '云岭项目部', type: '病假', start: '2026-09-18', end: '2026-09-20', days: 3, reason: '感冒发烧，医院建议休息', status: '待审批', appliedAt: '2026-09-18 07:30' },
  { id: 4, applicant: '吴俊杰', project: '江湾项目部', type: '调休', start: '2026-09-21', end: '2026-09-21', days: 1, reason: '上周末加班调休', status: '已批准', appliedAt: '2026-09-16 15:10' },
  { id: 5, applicant: '何伟', project: '榕荫项目部', type: '事假', start: '2026-09-25', end: '2026-09-26', days: 2, reason: '家属住院陪护', status: '已驳回', appliedAt: '2026-09-14 09:05' }
]

export const partyMembers: PartyMember[] = [
  { id: 1, name: '陈国栋', gender: '男', project: '云岭项目部', org: '云岭项目部党支部', partyPost: '支部书记', joinDate: '2012-07-01', status: '正式党员' },
  { id: 2, name: '李明远', gender: '男', project: '云岭项目部', org: '云岭项目部党支部', partyPost: '组织委员', joinDate: '2014-06-15', status: '正式党员' },
  { id: 3, name: '王海涛', gender: '男', project: '云岭项目部', org: '云岭项目部党支部', partyPost: '党员', joinDate: '2016-12-01', status: '正式党员' },
  { id: 4, name: '周晓琳', gender: '女', project: '云岭项目部', org: '云岭项目部党支部', partyPost: '宣传委员', joinDate: '2018-05-20', status: '正式党员' },
  { id: 5, name: '吴俊杰', gender: '男', project: '江湾项目部', org: '江湾项目部党支部', partyPost: '支部书记', joinDate: '2011-11-11', status: '正式党员' },
  { id: 6, name: '徐鹏', gender: '男', project: '江湾项目部', org: '江湾项目部党支部', partyPost: '纪检委员', joinDate: '2019-03-28', status: '正式党员' },
  { id: 7, name: '何伟', gender: '男', project: '榕荫项目部', org: '榕荫项目部党支部', partyPost: '党员', joinDate: '2015-09-01', status: '正式党员' },
  { id: 8, name: '林涛', gender: '男', project: '榕荫项目部', org: '榕荫项目部党支部', partyPost: '党员', joinDate: '2020-08-14', status: '正式党员' },
  { id: 9, name: '高峰', gender: '男', project: '云岭项目部', org: '云岭项目部党支部', partyPost: '党员', joinDate: '2024-06-30', status: '预备党员' }
]

export const receptions: Reception[] = [
  {
    id: 1, title: '业主季度履约检查接待', level: 'A', category: '商务', guests: '张总一行 5 人', guestCount: 5, date: '2026-09-12',
    host: '陈国栋', department: '综合管理部', status: '清单已归档', amount: 3860, voucherNo: '记-09-112',
    details: [
      { time: '09:30', item: '车辆接送', place: '项目部—天河工地', amount: 320 },
      { time: '10:00', item: '会议室服务', place: '项目部一楼会议室', amount: 180 },
      { time: '12:00', item: '工作餐', place: '项目部食堂包间', amount: 1560 },
      { time: '15:00', item: '宣传展板布置', place: '施工现场', amount: 800 },
      { time: '17:30', item: '返程车辆', place: '天河工地—项目部', amount: 1000 }
    ]
  },
  {
    id: 2, title: '地方安监部门安全检查', level: 'B', category: '外事', guests: '区应急管理局 3 人', guestCount: 3, date: '2026-09-16',
    host: '李明远', department: '安全环保部', status: '已接待', amount: 1240, voucherNo: '',
    details: [
      { time: '09:00', item: '车辆接送', place: '区应急管理局—项目部', amount: 240 },
      { time: '11:30', item: '工作餐', place: '项目部食堂', amount: 600 },
      { time: '14:00', item: '会议室服务', place: '项目部三楼会议室', amount: 400 }
    ]
  },
  {
    id: 3, title: '分包商履约约谈', level: 'C', category: '商务', guests: '宏基劳务公司 2 人', guestCount: 2, date: '2026-09-17',
    host: '刘志强', department: '合同管理部', status: '方案待审', amount: 0, voucherNo: '',
    details: []
  }
]

export const welfareItems: WelfareItem[] = [
  { id: 1, category: '月饼（蛋黄酥）', name: '流心奶黄月饼', spec: '盒装 8 枚', quantity: 1, recipient: '陈国栋', department: '综合管理部', status: '已领取', signedAt: '2026-09-14 09:12' },
  { id: 2, category: '月饼（蛋黄酥）', name: '流心奶黄月饼', spec: '盒装 8 枚', quantity: 1, recipient: '李明远', department: '综合管理部', status: '已领取', signedAt: '2026-09-14 10:03' },
  { id: 3, category: '月饼（蛋黄酥）', name: '流心奶黄月饼', spec: '盒装 8 枚', quantity: 1, recipient: '王海涛', department: '质检部', status: '未领取', signedAt: '' },
  { id: 4, category: '月饼（蛋黄酥）', name: '流心奶黄月饼', spec: '盒装 8 枚', quantity: 1, recipient: '赵启明', department: '施工部', status: '未领取', signedAt: '' },
  { id: 5, category: '水果', name: '秋季时令水果礼盒', spec: '盒装 5 斤', quantity: 1, recipient: '陈国栋', department: '综合管理部', status: '已领取', signedAt: '2026-09-15 14:40' },
  { id: 6, category: '水果', name: '秋季时令水果礼盒', spec: '盒装 5 斤', quantity: 1, recipient: '周晓琳', department: '综合管理部', status: '已领取', signedAt: '2026-09-15 15:20' },
  { id: 7, category: '水果', name: '秋季时令水果礼盒', spec: '盒装 5 斤', quantity: 1, recipient: '方静', department: '财务管理部', status: '未领取', signedAt: '' },
  { id: 8, category: '水果', name: '秋季时令水果礼盒', spec: '盒装 5 斤', quantity: 1, recipient: '高峰', department: '设备物资部', status: '已领取', signedAt: '2026-09-16 08:55' }
]

// 人员花名册种子数据（对齐「荔园项目2026年7月人员花名册统计表」23 字段）
type RosterSeed = Omit<RosterMember, 'category'>
const rosterSeedBase: RosterSeed[] = [
  { id: 1, seq: 1, name: '陈国栋', gender: '男', idCard: '110101196808010011', phone: '13900000001', age: 43, joinDate: '2013-06-01', employmentType: '正式工', education: '本科', school: '长春工程学院', major: '水利水电工程', title: '正高级工程师', political: '党员', position: '项目经理', adminSeries: '项目经理', profSeries: '无', salaryMechanism: '年薪制', salaryGrade: 'P2（一类）', monthlyPay: 7000, firstAdjust: '2013.06', project: '荔园项目', status: '正常在岗', certificates: '一级建造师（水利）建安B', contractRel: '示范建工' },
  { id: 2, seq: 2, name: '马晓东', gender: '男', idCard: '110101198507030022', phone: '13900000002', age: 41, joinDate: '2024-08-01', employmentType: '正式工', education: '本科', school: '黑龙江大学', major: '土木工程', title: '工程师', political: '党员', position: '项目副经理', adminSeries: '项目班子副职', profSeries: '无', salaryMechanism: '年薪制', salaryGrade: 'P4（一类）', monthlyPay: 6300, firstAdjust: '2024.08', project: '荔园项目', status: '正常在岗', certificates: '一级建造师（建筑）建安B', contractRel: '示范建工' },
  { id: 3, seq: 3, name: '何伟', gender: '男', idCard: '110101198911160033', phone: '13900000003', age: 37, joinDate: '2026-01-01', employmentType: '正式工', education: '专科', school: '长沙南方职业学院', major: '建筑工程技术', title: '工程师', political: '群众', position: '副总工程师', adminSeries: '项目班子副总师', profSeries: '无', salaryMechanism: '年薪制', salaryGrade: 'P5', monthlyPay: 5200, firstAdjust: '2026.01', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '示范建工' },
  { id: 4, seq: 4, name: '孙长海', gender: '男', idCard: '110101198103150044', phone: '13900000004', age: 45, joinDate: '2022-03-01', employmentType: '临时工', education: '初中', school: '', major: '', title: '', political: '群众', position: '司机', adminSeries: '', profSeries: '无', salaryMechanism: '固定合同', salaryGrade: '', monthlyPay: 4500, firstAdjust: '2022.03', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '临时工' },
  { id: 5, seq: 5, name: '蒋大伟', gender: '男', idCard: '110101197611200055', phone: '13900000005', age: 50, joinDate: '2021-05-01', employmentType: '劳务派遣', education: '初中', school: '', major: '', title: '', political: '群众', position: '厨师', adminSeries: '', profSeries: '无', salaryMechanism: '固定合同', salaryGrade: '', monthlyPay: 4200, firstAdjust: '2021.05', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '临时工' },
  { id: 6, seq: 6, name: '沈雨欣', gender: '女', idCard: '110101199603120066', phone: '13900000006', age: 30, joinDate: '2023-09-01', employmentType: '正式工', education: '本科', school: '广东工业大学', major: '工程管理', title: '助理工程师', political: '团员', position: '资料员', adminSeries: '', profSeries: '业务员', salaryMechanism: '岗位绩效制', salaryGrade: 'P8', monthlyPay: 5800, firstAdjust: '2023.09', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '城建公司' },
  { id: 7, seq: 7, name: '罗建华', gender: '男', idCard: '110101199011080077', phone: '13900000007', age: 35, joinDate: '2020-11-01', employmentType: '正式工', education: '专科', school: '广州城建职业学院', major: '工程造价', title: '工程师', political: '党员', position: '合同管理员', adminSeries: '项目部门正职', profSeries: '无', salaryMechanism: '岗位绩效制', salaryGrade: 'P6', monthlyPay: 6100, firstAdjust: '2020.11', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '示范建工' },
  { id: 8, seq: 8, name: '唐子豪', gender: '男', idCard: '110101199804150088', phone: '13900000008', age: 28, joinDate: '2024-02-01', employmentType: '劳务派遣', education: '本科', school: '华南理工大学', major: '安全工程', title: '技术员', political: '群众', position: '安全员', adminSeries: '', profSeries: '业务员', salaryMechanism: '岗位绩效制', salaryGrade: 'P9', monthlyPay: 5000, firstAdjust: '2024.02', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '城建公司' },
  { id: 9, seq: 9, name: '谢文婷', gender: '女', idCard: '110101199303200099', phone: '13900000009', age: 33, joinDate: '2019-07-01', employmentType: '正式工', education: '本科', school: '中山大学', major: '财务会计', title: '工程师', political: '党员', position: '会计', adminSeries: '项目部门正职', profSeries: '无', salaryMechanism: '岗位绩效制', salaryGrade: 'P7', monthlyPay: 6500, firstAdjust: '2019.07', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '示范建工' },
  { id: 10, seq: 10, name: '卢守义', gender: '男', idCard: '110101197911050010', phone: '13900000010', age: 47, joinDate: '2018-04-01', employmentType: '临时工', education: '高中', school: '', major: '', title: '', political: '群众', position: '门卫', adminSeries: '', profSeries: '无', salaryMechanism: '固定合同', salaryGrade: '', monthlyPay: 3800, firstAdjust: '2018.04', project: '荔园项目', status: '正常在岗', certificates: '', contractRel: '临时工' }
]
export const rosterList: RosterMember[] = rosterSeedBase.map((r) => ({
  ...r,
  category: deriveCategory(r)
}))

