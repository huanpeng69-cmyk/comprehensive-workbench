import { post, buildHeaders, clearSessionAndRedirect } from './http'

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

/* ============================================================
 * 流式问答（SSE）
 * ============================================================ */

/** meta 帧携带的来源信息：正文到来前就能先把引用渲染出来 */
export interface KbStreamMeta {
  citations?: KbCitation[]
  retrievalMode?: string
  graphEvidence?: unknown[]
  mode?: string
}

export interface KbStreamHandlers {
  onMeta?: (meta: KbStreamMeta) => void
  onDelta?: (delta: string) => void
  onDone?: () => void
  onError?: (msg: string) => void
}

/** 服务端下发的帧体，字段名与 KbProxyController#chatStream 对齐 */
interface KbStreamFrame {
  type: 'meta' | 'delta' | 'done' | 'error'
  content?: string
  message?: string
  citations?: KbCitation[]
  retrieval_mode?: string | null
  graph_evidence?: unknown[]
  mode?: string | null
}

function extractSseData(frame: string): string | null {
  for (const line of frame.split('\n')) {
    const t = line.trim()
    if (t.startsWith('data:')) return t.slice('data:'.length).trim()
  }
  return null
}

/**
 * 流式调用知识库问答端点，逐帧回调。
 *
 * 注意：知识库服务本身没有流式端点，服务端是「拿到整段答案后按句切片逐帧推送」，
 * 所以文字会逐步长出来，但首字延迟仍等于知识库整段生成耗时。
 */
export async function chatKbStream(
  payload: { question: string; history?: KbChatHistoryItem[]; top_k?: number; min_relevance?: number },
  handlers: KbStreamHandlers
): Promise<void> {
  let res: Response
  try {
    res = await fetch('/api/kb/chat/stream', {
      method: 'POST',
      headers: buildHeaders(),
      body: JSON.stringify(payload)
    })
  } catch (e) {
    handlers.onError?.(e instanceof Error ? e.message : '网络请求失败')
    return
  }
  if (!res.ok || !res.body) {
    // 401 与 http.ts 口径一致：清会话并跳登录，否则用户只看到一句「请求失败」
    if (res.status === 401) {
      clearSessionAndRedirect()
      handlers.onError?.('登录已过期，请重新登录')
      return
    }
    let msg = `请求失败（HTTP ${res.status}）`
    try {
      const j = await res.json()
      msg = (j && (j.message || j.error)) || msg
    } catch {
      /* 非 JSON 错误体 */
    }
    handlers.onError?.(msg)
    return
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let doneSeen = false
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      const data = extractSseData(frame)
      if (!data || data === '[DONE]') continue
      let ev: KbStreamFrame
      try {
        ev = JSON.parse(data) as KbStreamFrame
      } catch {
        continue
      }
      if (ev.type === 'meta') {
        handlers.onMeta?.({
          citations: ev.citations || [],
          retrievalMode: ev.retrieval_mode ?? undefined,
          graphEvidence: ev.graph_evidence || [],
          mode: ev.mode ?? undefined
        })
      } else if (ev.type === 'delta') {
        if (ev.content) handlers.onDelta?.(ev.content)
      } else if (ev.type === 'error') {
        handlers.onError?.(ev.message || '知识库未返回结果')
      } else if (ev.type === 'done') {
        doneSeen = true
        handlers.onDone?.()
      }
    }
  }
  if (!doneSeen) handlers.onDone?.()
}
