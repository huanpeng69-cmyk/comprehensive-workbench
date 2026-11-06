// 空串 = 同源调用 /api/...，由 vite dev 代理转发到后端 8081（见 vite.config.ts server.proxy）。
// 这样无论用 http://localhost:5173、127.0.0.1 还是局域网 IP 打开，都不会触发跨域拦截。
const BASE_URL = ''

export interface ApiResult<T> {
  code: string
  message: string
  data: T
  /** 后端链路 id（治理底座 P1/P3），与日志、审计、X-Request-Id 同值；旧后端可能不返回。 */
  traceId?: string
}

/**
 * 会话失效兜底：清理本地登录态并跳回登录页。
 *
 * 背景（2026-10-14 线上故障根因）：JWT 有效期 12 小时，过期后所有 /api 请求都会被拦成
 * 未认证。旧实现只检查 token 字符串是否存在（路由守卫），且把响应一律当成业务错误抛掉，
 * 结果是页面照常渲染、却显示「未配置」、提问没反应，用户完全看不出是登录过期。
 * 现在只要确认是「未认证」，就清 token + 跳登录，让故障显性化。
 */
export function clearSessionAndRedirect(): void {
  for (const key of Object.keys(localStorage)) {
    if (key.startsWith('workbench-')) {
      localStorage.removeItem(key)
    }
  }
  if (!location.hash.startsWith('#/login')) {
    location.hash = '#/login'
  }
}

/** 链路 id 的存储 key：同一个浏览器会话（tab）共用同一个 id，便于把一次交互的前端日志与后端日志对上。 */
const KEY_TRACE = 'workbench-traceId'

/** 生成并缓存本次会话的链路 id；跨请求复用，直到 tab 关闭。 */
export function traceId(): string {
  let id = sessionStorage.getItem(KEY_TRACE)
  if (!id) {
    id = `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 8)}`
    sessionStorage.setItem(KEY_TRACE, id)
  }
  return id
}

/**
 * 统一请求头构造器：JWT + 链路 id（治理底座 P0/P0b）。
 *
 * <p>导出供 SSE 旁路 {@code aiWriting.ts} 复用，避免两条调用路径的头构造各写一份、
 * 导致 traceId 对不上。业务侧不会直接用到，统一走 {@link request}。</p>
 */
export function buildHeaders(extra?: Record<string, string>): Record<string, string> {
  const token = localStorage.getItem('workbench-token')
  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8',
    'X-Request-Id': traceId(),
    ...(extra || {})
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

/**
 * 带链路 id 的请求错误（治理底座 P1/P3）。
 *
 * <p>继承 {@link Error}，所以既有的 {@code catch (e) { e.message }} 与
 * {@code err instanceof Error} 判断全部照旧可用；额外挂 {@link #traceId} 只为报错弹窗
 * 能展示「本次请求 id」，方便用户截图后直接 grep 后端日志与审计。</p>
 */
export class ApiError extends Error {
  readonly traceId: string

  constructor(message: string, traceId: string) {
    super(message)
    this.name = 'ApiError'
    this.traceId = traceId
  }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = buildHeaders(options.headers as Record<string, string> | undefined)
  const res = await fetch(`${BASE_URL}${path}`, { ...options, headers })
  // 后端回传的链路 id 若与本地不一致（如被网关改写），以后端为准，保持前端报错可回带。
  const respTrace = res.headers.get('X-Request-Id')
  if (respTrace) {
    sessionStorage.setItem(KEY_TRACE, respTrace)
  }
  // 注意不要连 403 一起清：403 是「已登录但无权限」，清会话只会让用户莫名其妙被踢出去
  // （见后端 GlobalExceptionHandler.handleAccessDenied）。
  if (res.status === 401) {
    clearSessionAndRedirect()
    throw new ApiError('登录已过期，请重新登录',
        res.headers.get('X-Request-Id') || traceId())
  }
  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    throw new ApiError(`服务异常（HTTP ${res.status}）`, res.headers.get('X-Request-Id') || traceId())
  }
  const body = (await res.json()) as ApiResult<T>
  // 后端 traceId 优先（网关可能改写），缺省回落到本地会话 id，保证报错一定能回带一个 id。
  const tid = body.traceId || res.headers.get('X-Request-Id') || traceId()
  if (body.code === '401') {
    clearSessionAndRedirect()
    throw new ApiError(body.message || '登录已过期，请重新登录', tid)
  }
  if (body.code !== '0') {
    throw new ApiError(body.message || '请求失败', tid)
  }
  return body.data
}


export function post<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function put<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

export function del<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' })
}

export function get<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}
