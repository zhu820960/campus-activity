const TOKEN = /^v2\.[0-9]{13}\.[A-Za-z0-9_-]{43}\.[A-Za-z0-9_-]{43}$/

export function buildCheckinCode(activityId, token) {
  if (!Number.isSafeInteger(activityId) || activityId <= 0 || typeof token !== 'string' || !TOKEN.test(token)) {
    throw new Error('签到二维码参数无效')
  }
  return JSON.stringify({ type: 'campus-activity-checkin', version: 1, activityId, token })
}

export function parseCheckinCode(raw) {
  if (typeof raw !== 'string' || raw.length > 1024) throw new Error('二维码内容无效')
  let value
  try { value = JSON.parse(raw) } catch { throw new Error('不是本平台签到二维码') }
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      value.type !== 'campus-activity-checkin' || value.version !== 1) {
    throw new Error('不是本平台签到二维码')
  }
  if (!Number.isSafeInteger(value.activityId) || value.activityId <= 0) throw new Error('活动编号无效')
  if (typeof value.token !== 'string' || !TOKEN.test(value.token)) throw new Error('签到码格式无效')
  return { activityId: value.activityId, token: value.token }
}
