import { defineStore } from 'pinia'
import { ref } from 'vue'
import { rosterList } from '@/mock/data'
import type { RosterMember } from '@/mock/types'

const STORAGE_KEY = 'roster-list-v1'

/** 旧版本 localStorage 数据可能缺字段（如 phone），统一补默认值 */
function normalizeMember(r: Record<string, any>): RosterMember {
  return {
    id: Number(r?.id) || 0,
    seq: Number(r?.seq) || 0,
    name: String(r?.name ?? ''),
    gender: (r?.gender === '女' ? '女' : '男') as RosterMember['gender'],
    idCard: String(r?.idCard ?? ''),
    phone: String(r?.phone ?? ''),
    age: Number(r?.age) || 0,
    joinDate: String(r?.joinDate ?? ''),
    employmentType: (r?.employmentType ?? '临时工') as RosterMember['employmentType'],
    education: (r?.education ?? '高中') as RosterMember['education'],
    school: String(r?.school ?? ''),
    major: String(r?.major ?? ''),
    title: String(r?.title ?? ''),
    political: (r?.political ?? '群众') as RosterMember['political'],
    position: String(r?.position ?? ''),
    adminSeries: String(r?.adminSeries ?? ''),
    profSeries: (r?.profSeries ?? '无') as RosterMember['profSeries'],
    salaryMechanism: (r?.salaryMechanism ?? '岗位绩效制') as RosterMember['salaryMechanism'],
    salaryGrade: String(r?.salaryGrade ?? ''),
    monthlyPay: Number(r?.monthlyPay) || 0,
    firstAdjust: String(r?.firstAdjust ?? ''),
    project: String(r?.project ?? '荔园项目'),
    status: String(r?.status ?? '正常在岗'),
    certificates: String(r?.certificates ?? ''),
    contractRel: (r?.contractRel ?? '示范建工') as RosterMember['contractRel'],
    category: (r?.category ?? '临时工') as RosterMember['category']
  }
}

function loadInitial(): RosterMember[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr) && arr.length) return arr.map(normalizeMember)
    }
  } catch {
    /* 解析失败则回退到种子数据 */
  }
  return JSON.parse(JSON.stringify(rosterList))
}

export const useRosterStore = defineStore('roster', () => {
  const list = ref<RosterMember[]>(loadInitial())

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(list.value))
    } catch {
      /* 存储配额异常时静默失败，不影响内存态 */
    }
  }

  function nextId(): number {
    return list.value.reduce((m, r) => Math.max(m, r.id), 0) + 1
  }

  function add(member: Omit<RosterMember, 'id'>) {
    list.value.push({ ...member, id: nextId() })
    persist()
  }

  function update(member: RosterMember) {
    const i = list.value.findIndex((r) => r.id === member.id)
    if (i >= 0) {
      list.value[i] = { ...member }
      persist()
    }
  }

  function remove(id: number) {
    list.value = list.value.filter((r) => r.id !== id)
    persist()
  }

  /** 导入时用上传结果整体替换 */
  function importReplace(members: RosterMember[]) {
    list.value = members
    persist()
  }

  function resetToSeed() {
    list.value = JSON.parse(JSON.stringify(rosterList))
    persist()
  }

  return { list, add, update, remove, importReplace, resetToSeed, persist }
})
