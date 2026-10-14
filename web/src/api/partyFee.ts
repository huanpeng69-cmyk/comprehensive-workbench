import { get } from './http'
import type { PartyFeeRecord } from '@/mock/types'

/** 党费季度明细（敏感：仅全公司数据范围角色可查询，服务端强制校验并审计）。 */
export const listPartyFees = () => get<PartyFeeRecord[]>('/api/party-fees')
