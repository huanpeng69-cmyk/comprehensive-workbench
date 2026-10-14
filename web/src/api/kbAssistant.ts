import { post } from './http'

export interface KbCitation {
  index: number
  source: string
  page_range: string
  text: string
}

export interface KbChatHistoryItem {
  role: string
  content: string
}

export interface KbChatResult {
  ok: boolean
  answer?: string
  citations?: KbCitation[]
  graph_evidence?: unknown[]
  retrieval_mode?: string
  mode?: string
  error?: string
}

export interface KbHit {
  source?: string
  page_range?: string
  text?: string
  relevance?: number
  chunk_id?: string
  evidence_type?: string
}

export interface KbQueryResult {
  total?: number
  hits?: KbHit[]
  ok?: boolean
  error?: string
}

/** 企业知识库智能客服：把问题 + 历史会话转发到工作台后端桥接接口。 */
export function chatKb(payload: {
  question: string
  history?: KbChatHistoryItem[]
  top_k?: number
  min_relevance?: number
}) {
  return post<KbChatResult>('/api/kb/chat', payload)
}

/** 企业知识库原始检索：返回相关片段、来源与相关性。 */
export function queryKb(payload: { question: string; top_k?: number }) {
  return post<KbQueryResult>('/api/kb/query', payload)
}
