import { post } from './http'

export interface LoginResult {
  token: string
  userId: number
  username: string
  name: string
  projectId: number
  projectName: string
  roles: string[]
}

export function login(username: string, password: string) {
  return post<LoginResult>('/api/auth/login', { username, password })
}
