const baseUrl = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
const TOKEN_KEY = 'campus_miniapp_token'

export function getToken() {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) uni.setStorageSync(TOKEN_KEY, token)
  else uni.removeStorageSync(TOKEN_KEY)
}

export function isSignedIn() {
  return Boolean(getToken())
}

export function assetUrl(value) {
  if (!value) return ''
  if (/^https:\/\//.test(value)) return value
  return ''
}

export function downloadActivityImage(value) {
  if (!/^\/images\/[0-9a-f]{32}\.(jpg|png)$/.test(value) || !baseUrl || !getToken()) {
    return Promise.reject(new Error('封面暂不可用'))
  }
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('图片接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.downloadFile({
    url: `${baseUrl}/api/miniapp${value}`,
    header: { Authorization: `Bearer ${getToken()}` },
    success: response => response.statusCode === 200 ? resolve(response.tempFilePath)
      : reject(new Error('封面暂不可用')),
    fail: () => reject(new Error('封面暂不可用'))
  }))
}

export function downloadAvatarImage() {
  if (!baseUrl || !getToken()) return Promise.reject(new Error('头像暂不可用'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('头像接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.downloadFile({
    url: `${baseUrl}/api/miniapp/my/avatar`,
    header: { Authorization: `Bearer ${getToken()}` },
    success: response => response.statusCode === 200 ? resolve(response.tempFilePath)
      : reject(new Error('头像暂不可用')),
    fail: () => reject(new Error('头像暂不可用'))
  }))
}

export function uploadAvatarImage(filePath) {
  if (!baseUrl || !getToken()) return Promise.reject(new Error('请先登录'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('头像接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.uploadFile({
    url: `${baseUrl}/api/miniapp/my/avatar`,
    filePath,
    name: 'file',
    header: { Authorization: `Bearer ${getToken()}` },
    success(response) {
      let body
      try { body = JSON.parse(response.data) } catch { body = {} }
      if (response.statusCode >= 200 && response.statusCode < 300) resolve(body)
      else {
        if (response.statusCode === 401) setToken('')
        reject(new Error(body.message || `头像上传失败（${response.statusCode}）`))
      }
    },
    fail: () => reject(new Error('头像上传失败，请检查网络后重试'))
  }))
}

export function uploadOrganizerCover(filePath) {
  if (!baseUrl || !getToken()) return Promise.reject(new Error('请先登录'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('图片接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.uploadFile({
    url: `${baseUrl}/api/miniapp/organizer/images`, filePath, name: 'file',
    header: { Authorization: `Bearer ${getToken()}` },
    success(response) {
      let body
      try { body = JSON.parse(response.data) } catch { body = {} }
      if (response.statusCode >= 200 && response.statusCode < 300 && body.url) resolve(body.url)
      else {
        reject(new Error(body.message || (response.statusCode === 401
          ? '封面上传被拒绝，请检查图片大小；若其他页面也无法访问，请重新微信登录'
          : `封面上传失败（${response.statusCode}）`)))
      }
    },
    fail: () => reject(new Error('封面上传失败，请检查网络后重试'))
  }))
}

export function downloadOrganizerCover(value) {
  if (!/^\/images\/[0-9a-f]{32}\.(jpg|png)$/.test(value) || !baseUrl || !getToken()) {
    return Promise.reject(new Error('封面暂不可用'))
  }
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('图片接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.downloadFile({
    url: `${baseUrl}/api/miniapp/organizer/images/${value.split('/').pop()}`,
    header: { Authorization: `Bearer ${getToken()}` },
    success: response => response.statusCode === 200 ? resolve(response.tempFilePath)
      : reject(new Error('封面暂不可用')),
    fail: () => reject(new Error('封面暂不可用'))
  }))
}

export function downloadOrganizerRoster(activityId, kind) {
  if (!Number.isSafeInteger(Number(activityId)) || Number(activityId) < 1 || !['registrations', 'checkins'].includes(kind)) {
    return Promise.reject(new Error('名单类型无效'))
  }
  if (!baseUrl || !getToken()) return Promise.reject(new Error('请先微信登录'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('名单接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.downloadFile({
    url: `${baseUrl}/api/miniapp/organizer/activities/${Number(activityId)}/exports/${kind}`,
    header: { Authorization: `Bearer ${getToken()}` },
    success: response => response.statusCode === 200 ? resolve(response.tempFilePath)
      : reject(new Error(response.statusCode === 403 ? '没有导出权限'
        : response.statusCode === 401 ? '登录已失效，请重新微信登录'
          : `名单导出失败（${response.statusCode}）`)),
    fail: () => reject(new Error('名单下载失败，请检查网络后重试'))
  }))
}

export function downloadPersonalParticipation(query = '') {
  if (!baseUrl || !getToken()) return Promise.reject(new Error('请先微信登录'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('个人记录接口需要 HTTPS'))
  }
  const keyword = String(query || '').trim()
  if (keyword.length > 80) return Promise.reject(new Error('搜索词不能超过80字'))
  const suffix = keyword ? `?query=${encodeURIComponent(keyword)}` : ''
  return new Promise((resolve, reject) => uni.downloadFile({
    url: `${baseUrl}/api/miniapp/my/participation.pdf${suffix}`,
    header: { Authorization: `Bearer ${getToken()}` },
    success: response => response.statusCode === 200 ? resolve(response.tempFilePath)
      : reject(new Error(response.statusCode === 403 ? '当前账号不能导出此记录'
        : response.statusCode === 401 ? '登录已失效，请重新微信登录'
          : `个人记录导出失败（${response.statusCode}）`)),
    fail: () => reject(new Error('个人记录下载失败，请检查网络后重试'))
  }))
}

export function requireSignIn() {
  if (isSignedIn()) return true
  uni.showToast({ title: '请先微信登录', icon: 'none' })
  uni.navigateTo({ url: '/pages/login/index' })
  return false
}

export function request(path, { method = 'GET', data, auth = false, headers = {} } = {}) {
  if (!baseUrl) return Promise.reject(new Error('尚未配置 API 地址'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('小程序正式环境必须使用 HTTPS 接口'))
  }
  const token = auth ? getToken() : ''
  if (auth && !token) return Promise.reject(new Error('请先微信登录'))
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${baseUrl}${path}`,
      method,
      data,
      timeout: 10000,
      header: { ...headers, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data)
          return
        }
        if (response.statusCode === 401 && auth) setToken('')
        const fallback = response.statusCode === 401 ? '登录已失效，请重新微信登录' :
          response.statusCode === 403 ? '没有操作权限' :
          response.statusCode === 404 && path.startsWith('/api/miniapp/') ? '微信学生认证服务尚未启用' :
          response.statusCode === 409 ? '当前状态不允许此操作' :
          `请求失败（${response.statusCode}）`
        const error = new Error(response.data?.message || fallback)
        error.status = response.statusCode
        reject(error)
      },
      fail() { reject(new Error('网络连接失败，请检查网络后重试')) }
    })
  })
}

export function uploadAppealEvidence(challenge, filePath) {
  if (!baseUrl || !challenge || !filePath) return Promise.reject(new Error('请先选择学生证照片'))
  if (!/^https:\/\//.test(baseUrl) && !import.meta.env.DEV) {
    return Promise.reject(new Error('证明材料接口需要 HTTPS'))
  }
  return new Promise((resolve, reject) => uni.uploadFile({
    url: `${baseUrl}/api/miniapp/auth/appeals/evidence`, filePath, name: 'file',
    header: { 'X-Appeal-Challenge': challenge },
    success(response) {
      let body
      try { body = JSON.parse(response.data) } catch { body = {} }
      if (response.statusCode >= 200 && response.statusCode < 300) resolve()
      else reject(new Error(body.message || `学生证上传失败（${response.statusCode}）`))
    },
    fail: () => reject(new Error('学生证上传失败，请检查网络后重试'))
  }))
}

export const api = {
  activities: () => request('/api/miniapp/activities', { auth: true }),
  activity: id => request(`/api/miniapp/activities/${encodeURIComponent(id)}`, { auth: true }),
  announcements: () => request('/api/miniapp/announcements', { auth: true }),
  mine: () => request('/api/miniapp/my/registrations', { auth: true }),
  me: () => request('/api/miniapp/auth/me', { auth: true }),
  removeAvatar: () => request('/api/miniapp/my/avatar', { method: 'DELETE', auth: true }),
  messages: () => request('/api/miniapp/my/messages', { auth: true }),
  unreadMessages: () => request('/api/miniapp/my/messages/unread-count', { auth: true }),
  readMessage: id => request(`/api/miniapp/my/messages/${encodeURIComponent(id)}/read`, {
    method: 'POST', auth: true
  }),
  myFeedback: id => request(`/api/miniapp/activities/${encodeURIComponent(id)}/feedback`, { auth: true }),
  saveFeedback: (id, rating, comment) => request(`/api/miniapp/activities/${encodeURIComponent(id)}/feedback`, {
    method: 'PUT', data: { rating, comment }, auth: true
  }),
  login: code => request('/api/miniapp/auth/login', { method: 'POST', data: { code } }),
  bind: (code, email, password) => request('/api/miniapp/auth/bind', {
    method: 'POST', data: { code, email, password }
  }),
  bindStudent: (code, studentNumber, name) => request('/api/miniapp/auth/bind-student', {
    method: 'POST', data: { code, studentNumber, name }
  }),
  appealAvailability: () => request('/api/miniapp/auth/appeals/availability'),
  appealChallenge: code => request('/api/miniapp/auth/appeals/challenge', {
    method: 'POST', data: { code }
  }),
  appealStatus: code => request('/api/miniapp/auth/appeals/status', {
    method: 'POST', data: { code }
  }),
  submitAppeal: (challenge, type, proposedStudentNumber, reason) => request('/api/miniapp/auth/appeals', {
    method: 'POST', data: { type, proposedStudentNumber, reason },
    headers: { 'X-Appeal-Challenge': challenge }
  }),
  activateWebCredentials: (code, password) => request('/api/miniapp/auth/web-credentials', {
    method: 'POST', data: { code, password }, auth: true
  }),
  logout: () => request('/api/miniapp/auth/logout', { method: 'POST', auth: true }),
  register: id => request(`/api/miniapp/activities/${encodeURIComponent(id)}/registrations`, {
    method: 'POST', auth: true
  }),
  cancel: id => request(`/api/miniapp/activities/${encodeURIComponent(id)}/registrations`, {
    method: 'DELETE', auth: true
  }),
  checkin: (id, token) => request(`/api/miniapp/activities/${encodeURIComponent(id)}/checkins`, {
    method: 'POST', data: { token }, auth: true
  }),
  organizerActivities: () => request('/api/miniapp/organizer/activities', { auth: true }),
  organizerActivity: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}`, { auth: true }),
  organizerCreate: data => request('/api/miniapp/organizer/activities', { method: 'POST', data, auth: true }),
  organizerEdit: (id, data) => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}`, {
    method: 'PUT', data, auth: true
  }),
  organizerSubmit: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/submit`, {
    method: 'POST', auth: true
  }),
  organizerRegistrations: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/registrations`, { auth: true }),
  organizerStats: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/stats`, { auth: true }),
  organizerFeedback: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/feedback`, { auth: true }),
  organizerFeedbackSummary: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/feedback/summary`, { auth: true }),
  organizerReviews: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/reviews`, { auth: true }),
  organizerCancellation: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/cancellation`, { auth: true }),
  organizerRequestCancellation: (id, reason) => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/cancellation`, {
    method: 'POST', data: { reason }, auth: true
  }),
  organizerPendingChange: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/changes`, { auth: true }),
  organizerRequestChange: (id, proposed, reason) => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/changes`, {
    method: 'POST', data: { proposed, reason }, auth: true
  }),
  organizerToken: id => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/checkin-token`, {
    method: 'POST', auth: true
  }),
  organizerManualCheckin: (id, studentId, reason) => request(`/api/miniapp/organizer/activities/${encodeURIComponent(id)}/manual-checkin`, {
    method: 'POST', data: { studentId, reason }, auth: true
  }),
  organizerDraft: data => request('/api/miniapp/organizer/ai/draft', { method: 'POST', data, auth: true })
}
