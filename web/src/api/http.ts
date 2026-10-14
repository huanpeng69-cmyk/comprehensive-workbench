// 空串 = 同源调用 /api/...，由 vite dev 代理转发到后端 8080（见 vite.config.ts server.proxy）。
// 这样无论用 http://localhost:5173、127.0.0.1 还是局域网 IP 打开，都不会触发跨域拦截。
const BASE_URL = ''

export interface ApiResult<T> {
  code: string
  message: string
  data: T
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

/**
 * 统一 fetch 封装：注入 JWT、解析后端 Result 结构、抛出业务错误。
 */
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem('workbench-token')
  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8',
    ...(options.headers as Record<string, string> | undefined)
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  const res = await fetch(`${BASE_URL}${path}`, { ...options, headers })
  // 401 = 未认证（无 token / token 过期 / token 非法），一律按登录失效处理。
  // 注意不要连 403 一起清：403 是「已登录但无权限」，清会话只会让用户莫名其妙被踢出去
  // （见后端 GlobalExceptionHandler.handleAccessDenied）。
  if (res.status === 401) {
    clearSessionAndRedirect()
    throw new Error('登录已过期，请重新登录')
  }
  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    throw new Error(`服务异常（HTTP ${res.status}）`)
  }
  const body = (await res.json()) as ApiResult<T>
  if (body.code === '401') {
    clearSessionAndRedirect()
    throw new Error(body.message || '登录已过期，请重新登录')
  }
  if (body.code !== '0') {
    throw new Error(body.message || '请求失败')
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
