export function buildCheckinPayload(activityId, token) {
  if (!Number.isSafeInteger(activityId) || activityId < 1 || typeof token !== 'string' || !token) {
    throw new Error('签到二维码参数无效')
  }
  return JSON.stringify({ type: 'campus-activity-checkin', version: 1, activityId, token })
}
