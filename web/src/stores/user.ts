import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi } from '@/api/auth'
import { roleDisplayName } from '@/api/roles'

const KEY_TOKEN = 'workbench-token'
const KEY_NAME = 'workbench-name'
const KEY_USERNAME = 'workbench-username'
const KEY_ROLE = 'workbench-role'
const KEY_PROJECT = 'workbench-project'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(KEY_TOKEN) || '')
  const name = ref(localStorage.getItem(KEY_NAME) || '')
  const username = ref(localStorage.getItem(KEY_USERNAME) || '')
  const role = ref(localStorage.getItem(KEY_ROLE) || '')
  const project = ref(localStorage.getItem(KEY_PROJECT) || '')
  const avatar = ref('')

  async function login(user: string, password: string) {
    const result = await loginApi(user, password)
    token.value = result.token
    name.value = result.name
    username.value = result.username
    role.value = roleDisplayName(result.roles)
    project.value = result.projectName || ''
    localStorage.setItem(KEY_TOKEN, result.token)
    localStorage.setItem(KEY_NAME, result.name)
    localStorage.setItem(KEY_USERNAME, result.username)
    localStorage.setItem(KEY_ROLE, role.value)
    localStorage.setItem(KEY_PROJECT, project.value)
  }

  function logout() {
    name.value = ''
    token.value = ''
    username.value = ''
    role.value = ''
    project.value = ''
    localStorage.removeItem(KEY_TOKEN)
    localStorage.removeItem(KEY_NAME)
    localStorage.removeItem(KEY_USERNAME)
    localStorage.removeItem(KEY_ROLE)
    localStorage.removeItem(KEY_PROJECT)
  }

  return { token, name, username, role, project, avatar, login, logout }
})
