import { get } from './http'

/** 制度正文：由后端鉴权下发，不再走前端 public 静态资源。 */
export function getRegulationContent(docNo: string) {
  return get<string>(`/api/kb/regulations/${encodeURIComponent(docNo)}`)
}
