import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  listEmployees,
  createEmployee,
  updateEmployee,
  deleteEmployee,
  importEmployees
} from '@/api/employee'
import type { RosterMember } from '@/mock/types'

/**
 * 人员花名册 store：数据源为后端 PostgreSQL（/api/employees）。
 * 旧版 localStorage（roster-list-v1）不再读写；历史数据请通过
 * 「花名册导入」Excel 重新进入数据库。
 */
export const useRosterStore = defineStore('roster', () => {
  const list = ref<RosterMember[]>([])
  const loading = ref(false)

  /** 从服务器拉取（按当前用户数据范围过滤） */
  async function load() {
    loading.value = true
    try {
      list.value = await listEmployees()
    } finally {
      loading.value = false
    }
  }

  function add(member: Omit<RosterMember, 'id'>) {
    return createEmployee(member).then((created) => {
      list.value.push(created)
      return created
    })
  }

  function update(member: RosterMember) {
    return updateEmployee(member).then((saved) => {
      const i = list.value.findIndex((r) => r.id === saved.id)
      if (i >= 0) list.value[i] = saved
      return saved
    })
  }

  function remove(id: number) {
    return deleteEmployee(id).then(() => {
      list.value = list.value.filter((r) => r.id !== id)
    })
  }

  /** 导入：解析结果整体覆盖服务器数据，成功后重新拉取 */
  function importReplace(members: RosterMember[]) {
    return importEmployees(members).then(() => load())
  }

  /** 兼容旧接口名：现在语义是「重新从服务器加载」，不再是恢复种子数据 */
  function resetToSeed() {
    return load()
  }

  // 页面初始化时拉取一次；失败静默，页面显示空列表（后端异常时会有统一错误提示路径）
  void load().catch(() => {})

  return { list, loading, load, add, update, remove, importReplace, resetToSeed }
})
