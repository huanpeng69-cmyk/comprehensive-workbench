import { get, post, put, del } from './http'

/** 记忆类型枚举名，与后端 com.workbench.memory.domain.MemoryType 一一对应。 */
export type MemoryType = 'SEMANTIC' | 'PROCEDURAL' | 'EPISODIC'

/** 记忆来源枚举名，与后端 com.workbench.memory.domain.MemorySource 一一对应。 */
export type MemorySource = 'MANUAL' | 'AGENT'

/**
 * 类型下拉项：label / description 与后端 MemoryType 的 label() / description() 文案保持一致。
 * 这里硬编码是因为下拉要在打开对话框前就有值；后端改文案时需同步这一处。
 */
export const MEMORY_TYPE_OPTIONS: { value: MemoryType; label: string; description: string }[] = [
  { value: 'SEMANTIC', label: '语义记忆', description: '关于我是谁的事实与长期偏好' },
  { value: 'PROCEDURAL', label: '程序记忆', description: '希望怎么做事、以什么形式输出' },
  { value: 'EPISODIC', label: '情景记忆', description: '过去的具体经历与结论' }
]

export interface MemoryItem {
  id: number
  type: MemoryType
  typeLabel: string
  content: string
  importance: number
  source: MemorySource
  sourceLabel: string
  /** ISO 本地时间串（后端 LocalDateTime）；由页面格式化成「YYYY-MM-DD HH:mm」。 */
  createdAt: string
  updatedAt: string
  /** 为 null 表示永不过期。 */
  expiresAt: string | null
  /** 已过期的记忆仍在列表里可见，只是不再参与召回。 */
  expired: boolean
}

export interface MemoryCreatePayload {
  content: string
  type?: MemoryType
  importance?: number
  /** 1..3650；不传（undefined）表示永不过期。 */
  ttlDays?: number
}

/**
 * 修改载荷：正文、类型、重要度、有效期都按「目标状态」整体回传。
 *
 * 注意 ttlDays 与新增时的语义不同（见后端 MemoryUpdateRequest）：
 * 不传/null = 保持原有有效期不变，0 = 改为永不过期，N>0 = 从现在起 N 天。
 */
export interface MemoryUpdatePayload {
  content: string
  type?: MemoryType
  importance?: number
  ttlDays?: number | null
}

export interface MemoryRecallPayload {
  /** 当前话题；留空则退化为按重要度 + 新鲜度排序。 */
  query?: string
  topK?: number
  tokenBudget?: number
}

export interface MemoryRecallResult {
  /** 可直接拼进 system prompt 的记忆块；没有命中时是空字符串。 */
  block: string
  memories: MemoryItem[]
  estimatedTokens: number
  /** 因超出 token 预算而未逐条展开的条数。 */
  omittedCount: number
}

/** 我的记忆列表（含已过期项，由 expired 标注）。 */
export function listMemories() {
  return get<MemoryItem[]>('/api/memories')
}

/** 新增一条记忆；内容重复时后端返回既有那条（幂等合并，重要度取较大值）。 */
export function createMemory(body: MemoryCreatePayload) {
  return post<MemoryItem>('/api/memories', body)
}

/** 修改我的一条记忆；不存在或不属于当前用户都返回 404。 */
export function updateMemory(id: number, body: MemoryUpdatePayload) {
  return put<MemoryItem>(`/api/memories/${id}`, body)
}

/** 删除我的一条记忆；不存在或不属于当前用户都返回 404。 */
export function deleteMemory(id: number) {
  return del<void>(`/api/memories/${id}`)
}

/** 召回预览：给定当前话题，返回将要注入提示词的那一块记忆。 */
export function recallMemories(body: MemoryRecallPayload) {
  return post<MemoryRecallResult>('/api/memories/recall', body)
}
