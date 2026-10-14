import { defineStore } from 'pinia'
import { reactive } from 'vue'
import type { CondolenceRecord } from '@/mock/types'
import {
  listCondolences,
  createCondolence,
  approveCondolence,
  rejectCondolence,
  type CreateCondolenceInput
} from '@/api/condolence'

/**
 * 模块级持久化状态：数据以后端 PostgreSQL 为准，启动时从 /api/condolences 拉取
 * （按当前角色数据范围过滤）。
 */
export const condolenceList = reactive<CondolenceRecord[]>([])

async function load(): Promise<void> {
  try {
    const data = await listCondolences()
    condolenceList.splice(0, condolenceList.length, ...data)
  } catch {
    /* 加载失败静默，页面显示空列表 */
  }
}

void load()

export const useCondolenceStore = defineStore('condolence', () => {
  function add(input: CreateCondolenceInput) {
    return createCondolence(input).then((created) => {
      condolenceList.unshift(created)
      return created
    })
  }

  function approve(id: number, comment?: string) {
    return approveCondolence(id, comment).then((saved) => {
      const i = condolenceList.findIndex((r) => r.id === saved.id)
      if (i >= 0) condolenceList[i] = saved
      return saved
    })
  }

  function reject(id: number, comment?: string) {
    return rejectCondolence(id, comment).then((saved) => {
      const i = condolenceList.findIndex((r) => r.id === saved.id)
      if (i >= 0) condolenceList[i] = saved
      return saved
    })
  }

  return { list: condolenceList, load, add, approve, reject }
})
