import { ref } from 'vue'
import { api, resetCsrf } from './api'

export const user = ref(null)
let loaded = false

export async function loadSession() {
  if (loaded) return user.value
  loaded = true
  try {
    user.value = await api('/auth/me')
  } catch {
    user.value = null
  }
  return user.value
}

export function setUser(value) {
  user.value = value
  loaded = true
  resetCsrf()
}

export function hasRole(role) {
  return user.value?.roles?.includes(role) ?? false
}

export function homePath(account = user.value) {
  if (account?.roles?.includes('ADMIN')) return '/admin'
  if (account?.roles?.includes('ORGANIZER')) return '/organizer'
  return '/login'
}
