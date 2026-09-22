import { get, post } from './http'

export interface TaskVo {
  taskId: number
  instanceId: number
  title: string
  bizType: string
  initiator: string
  initiatorName: string
  nodeName: string
  assignee: string
  assigneeName: string
  status: string
  createdAt: string
}

export interface TaskHistory {
  id: number
  nodeSeq: number
  nodeName: string
  assignee: string
  assigneeName: string
  status: string
  comment: string | null
  createdAt: string
  finishedAt: string | null
}

export interface NodeProgress {
  seq: number
  name: string
  state: string
}

export interface ProcessInstanceVo {
  id: number
  bizType: string
  title: string
  initiator: string
  initiatorName: string
  projectName: string | null
  status: string
  createdAt: string
  finishedAt: string | null
  currentNodeName: string | null
  nodes: NodeProgress[]
  tasks: TaskHistory[]
}

export interface InstanceSummaryVo {
  id: number
  bizType: string
  title: string
  initiator: string
  status: string
  currentNodeName: string | null
  createdAt: string
  finishedAt: string | null
}

export const listMyTodos = () => get<TaskVo[]>('/api/todos/mine')

export const approveTask = (taskId: number, comment: string) =>
  post(`/api/todos/${taskId}/approve`, { comment })

export const rejectTask = (taskId: number, comment: string) =>
  post(`/api/todos/${taskId}/reject`, { comment })

export const transmitTask = (taskId: number, toUsername: string, comment: string) =>
  post(`/api/todos/${taskId}/transmit`, { toUsername, comment })

export const getInstance = (id: number) => get<ProcessInstanceVo>(`/api/workflow/instances/${id}`)

export const listMyInstances = () => get<InstanceSummaryVo[]>('/api/workflow/instances/mine')
