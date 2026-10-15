let csrfToken = null

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

async function csrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new ApiError('无法建立安全会话，请刷新页面', response.status)
  csrfToken = (await response.json()).token
}

export async function api(path, { method = 'GET', body } = {}) {
  const headers = {}
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    if (!csrfToken) await csrf()
    headers['X-XSRF-TOKEN'] = csrfToken
  }
  const response = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : (body instanceof FormData ? body : JSON.stringify(body)),
  })
  if (response.status === 204) return null
  const data = await response.json().catch(() => ({}))
  if (!response.ok) {
    if (response.status === 403) csrfToken = null
    if (response.status === 401 && !['/auth/me', '/auth/login'].includes(path)
      && window.location.pathname !== '/login') {
      csrfToken = null
      const next = window.location.pathname + window.location.search
      window.location.replace(`/login?next=${encodeURIComponent(next)}`)
    }
    throw new ApiError(data.message || `请求失败 (${response.status})`, response.status)
  }
  return data
}

export async function apiFile(path) {
  const response = await fetch(`/api${path}`, { credentials: 'include', cache: 'no-store' })
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    if (response.status === 401 && window.location.pathname !== '/login') {
      csrfToken = null
      const next = window.location.pathname + window.location.search
      window.location.replace(`/login?next=${encodeURIComponent(next)}`)
    }
    throw new ApiError(data.message || `下载失败 (${response.status})`, response.status)
  }
  return response.blob()
}

export function resetCsrf() {
  csrfToken = null
}
