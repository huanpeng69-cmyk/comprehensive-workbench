// 空串 = 同源调用 /api/...，由 vite dev 代理转发到后端 8080（见 vite.config.ts server.proxy）。
// 这样无论用 http://localhost:5173、127.0.0.1 还是局域网 IP 打开，都不会触发跨域拦截。
const BASE_URL = ''

export interface ApiResult<T> {
  code: string
  message: string
  data: T
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
  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    throw new Error(`服务异常（HTTP ${res.status}）`)
  }
  const body = (await res.json()) as ApiResult<T>
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

export function get<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}
