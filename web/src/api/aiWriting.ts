import { get, post, put, clearSessionAndRedirect, buildHeaders } from './http'

export interface AiConfigView {
  /** OpenAI 兼容接口地址 */
  baseUrl: string
  /** 模型名 */
  model: string
  /** 是否已配置密钥（含环境变量来源） */
  apiKeyConfigured: boolean
  /** 密钥脱敏展示，如 sk-a***3456；未配置时为 null */
  apiKeyMasked: string | null
  /** 当前生效来源：db = 页面配置，env = 环境变量 */
  source: string
  /** 当前登录用户是否可修改配置。后端按角色判定，是唯一权威来源 */
  editable: boolean
}

/** 读取模型设置脱敏视图（任意登录可见） */
export function fetchAiConfig() {
  return get<AiConfigView>('/api/ai/writing/config')
}

/** 保存模型设置（仅系统管理员与 HR 可写；apiKey 留空表示沿用旧密钥） */
export function saveAiConfig(payload: {
  baseUrl: string
  model: string
  apiKey: string
}) {
  return put<AiConfigView>('/api/ai/writing/config', payload)
}

/** 连接测试结果：ok=false 时 message 是给人看的原因（HTTP 码 / 超时 / 地址不通） */
export interface AiConnectionCheck {
  ok: boolean
  message: string
  latencyMs: number
}

/**
 * 测试连接：拿候选配置真实发一次极简生成请求（max_tokens=1），不落库。
 * 字段传空即沿用已保存值，与 save 的语义一致。
 */
export function testAiConfig(payload: { baseUrl: string; model: string; apiKey: string }) {
  return post<AiConnectionCheck>('/api/ai/writing/config/test', payload)
}

/* ============================================================
 * 通用 Agent 流式调用
 * ============================================================ */

export interface AgentMessageDto {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
}

 export interface AgentRequestDto {
   systemPrompt?: string
   messages: AgentMessageDto[]
   /** 启用的工具名列表；传 [] 表示不用任何工具（跳过知识库），智能体直连模型；不传 = 全部工具 */
   tools?: string[]
   stream?: boolean
 }

export interface AgentToolCallEvent {
  type: 'tool_call'
  id: string
  name: string
  /** 工具入参的原始 JSON 字符串 */
  args: string
}

export interface AgentToolResultEvent {
  type: 'tool_result'
  id: string
  content: string
}

export interface AgentEvent {
  type: 'message' | 'tool_call' | 'tool_result' | 'done' | 'error'
  content?: string
  id?: string
  name?: string
  args?: string
  message?: string
}

export interface AgentStreamHandlers {
  onMessage?: (delta: string) => void
  onToolCall?: (e: AgentToolCallEvent) => void
  onToolResult?: (e: AgentToolResultEvent) => void
  onError?: (msg: string) => void
  onDone?: () => void
}

/**
 * 调用通用 Agent 端点，逐帧解析自定义 SSE 协议（message / tool_call / tool_result / done / error）。
 * 用法：把完整对话历史 + 可选 systemPrompt 发过去，通过回调增量渲染。
 */
export async function sendAgentMessage(
  payload: AgentRequestDto,
  handlers: AgentStreamHandlers
): Promise<void> {
  let res: Response
  try {
    res = await fetch('/api/ai/writing/agent', {
      method: 'POST',
      // 治理底座 P0b：复用统一请求头构造器（JWT + X-Request-Id），与 http.ts 的 traceId 对齐。
      headers: buildHeaders(),
      body: JSON.stringify({ ...payload, stream: true })
    })
  } catch (e) {
    handlers.onError?.(e instanceof Error ? e.message : '网络请求失败')
    return
  }
  if (!res.ok || !res.body) {
    // 401 同样是「登录失效」，走与 http.ts 一致的兜底：清 token + 跳登录，
    // 否则 AI 对话只会弹一句「请求失败（HTTP 401）」，用户仍不知道要重新登录。
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
      const ev = parseAgentEvent(data)
      if (!ev) continue
      if (ev.type === 'message' && ev.content) {
        handlers.onMessage?.(ev.content)
      } else if (ev.type === 'tool_call') {
        handlers.onToolCall?.(ev as AgentToolCallEvent)
      } else if (ev.type === 'tool_result') {
        handlers.onToolResult?.(ev as AgentToolResultEvent)
      } else if (ev.type === 'error') {
        handlers.onError?.(ev.message || '未知错误')
      } else if (ev.type === 'done') {
        doneSeen = true
        handlers.onDone?.()
      }
    }
  }
  if (!doneSeen) handlers.onDone?.()
}

function extractSseData(frame: string): string | null {
  for (const line of frame.split('\n')) {
    const t = line.trim()
    if (t.startsWith('data:')) return t.slice('data:'.length).trim()
  }
  return null
}

function parseAgentEvent(data: string): AgentEvent | null {
  try {
    return JSON.parse(data) as AgentEvent
  } catch {
    return null
  }
}
