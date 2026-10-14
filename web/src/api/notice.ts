import { get } from './http'
import type { Notice } from '@/mock/types'

/** 通知公告列表（全员可见，按发布时间倒序）。 */
export const listNotices = () => get<Notice[]>('/api/notices')
