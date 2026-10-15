export function formatTime(value) {
  if (!value) return '时间待定'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间待定'
  const two = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())} ${two(date.getHours())}:${two(date.getMinutes())}`
}

export function registrationLabel(activity, now = Date.now()) {
  if (activity.status === 'CANCELLED') return '活动已取消'
  if (activity.status !== 'PUBLISHED') return '暂不可报名'
  if (activity.registrationOpensAt && now < Date.parse(activity.registrationOpensAt)) return '报名未开始'
  if (activity.registrationClosesAt && now >= Date.parse(activity.registrationClosesAt)) return '报名已截止'
  if (activity.startsAt && now >= Date.parse(activity.startsAt)) return '活动已开始'
  if (activity.registeredCount >= activity.capacity) return '名额已满'
  return '可报名'
}

export function activityBadge(activity, now = Date.now()) {
  if (activity.status === 'CANCELLED') return { text: '已取消', tone: 'closed' }
  if (activity.endsAt && now >= Date.parse(activity.endsAt)) return { text: '已结束', tone: 'ended' }
  if (activity.startsAt && now >= Date.parse(activity.startsAt)) return { text: '进行中', tone: 'live' }
  const text = registrationLabel(activity, now)
  return { text, tone: text === '可报名' ? 'open' : 'closed' }
}

export function locationReady(activity) {
  return Number.isFinite(Number(activity?.latitude)) && Number.isFinite(Number(activity?.longitude)) &&
    activity.latitude !== null && activity.longitude !== null &&
    Number(activity.latitude) >= -90 && Number(activity.latitude) <= 90 &&
    Number(activity.longitude) >= -180 && Number(activity.longitude) <= 180
}
