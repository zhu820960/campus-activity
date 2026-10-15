const TIME_FIELDS = [
  'registrationOpensAt', 'registrationClosesAt', 'startsAt',
  'endsAt', 'checkinOpensAt', 'checkinClosesAt'
]

export function chinaDateTime(value) {
  if (!value) return { date: '', time: '' }
  const shifted = new Date(Date.parse(value) + 8 * 60 * 60 * 1000)
  return { date: shifted.toISOString().slice(0, 10), time: shifted.toISOString().slice(11, 16) }
}

export function createOrganizerForm(activity = {}) {
  const form = {
    title: activity.title || '', description: activity.description || '',
    articleSummary: activity.articleSummary || '', coverImageUrl: activity.coverImageUrl || '',
    location: activity.location || '', longitude: activity.longitude ?? null,
    latitude: activity.latitude ?? null, capacity: activity.capacity || 20
  }
  for (const field of TIME_FIELDS) form[field] = chinaDateTime(activity[field])
  return form
}

function instant(value) {
  if (!value?.date || !value?.time) return null
  const date = new Date(`${value.date}T${value.time}:00+08:00`)
  return Number.isNaN(date.getTime()) ? null : date
}

export function organizerQuickSchedule(form, beforeMinutes, afterMinutes, now = new Date(), registrationBeforeMinutes = 30) {
  const start = instant(form.startsAt)
  const end = instant(form.endsAt)
  if (!start || !end) throw new Error('请先选择活动开始和结束时间')
  if (end <= start) throw new Error('活动结束时间须晚于开始时间')
  const before = Number(beforeMinutes)
  const after = Number(afterMinutes)
  if (String(beforeMinutes).trim() === '' || String(afterMinutes).trim() === ''
      || !Number.isInteger(before) || !Number.isInteger(after) || before < 0 || after < 0
      || before > 240 || after > 240 || before + after === 0) {
    throw new Error('签到时间请填写0至240分钟，开始前后不能都为0')
  }
  const registrationBefore = Number(registrationBeforeMinutes)
  if (String(registrationBeforeMinutes).trim() === '' || !Number.isInteger(registrationBefore) || registrationBefore < 0 || registrationBefore > 10080) {
    throw new Error('报名提前截止请填写0至10080分钟')
  }
  const registrationClose = new Date(start.getTime() - registrationBefore * 60 * 1000)
  if (registrationClose <= now) throw new Error('报名截止时间已过，请调整活动开始时间或提前截止分钟数')
  const checkinClose = new Date(start.getTime() + after * 60 * 1000)
  if (checkinClose > end) throw new Error('签到截止不能晚于活动结束，请调整分钟数或结束时间')
  return {
    ...form,
    registrationOpensAt: chinaDateTime(now.toISOString()),
    registrationClosesAt: chinaDateTime(registrationClose.toISOString()),
    checkinOpensAt: chinaDateTime(new Date(start.getTime() - before * 60 * 1000).toISOString()),
    checkinClosesAt: chinaDateTime(checkinClose.toISOString())
  }
}

export function organizerQuickSettings(activity) {
  const start = Date.parse(activity.startsAt)
  const registrationClose = Date.parse(activity.registrationClosesAt)
  const checkinOpen = Date.parse(activity.checkinOpensAt)
  const checkinClose = Date.parse(activity.checkinClosesAt)
  if ([start, registrationClose, checkinOpen, checkinClose].some(Number.isNaN)) return null
  const before = (start - checkinOpen) / 60000
  const after = (checkinClose - start) / 60000
  const registrationBefore = (start - registrationClose) / 60000
  if (!Number.isInteger(registrationBefore) || registrationBefore < 0 || registrationBefore > 10080 || !Number.isInteger(before)
      || !Number.isInteger(after) || before < 0 || after < 0 || before > 240
      || after > 240 || before + after === 0) return null
  return { before, after, registrationBefore }
}

export function organizerPayload(form) {
  const result = { ...form, title: form.title.trim(), description: form.description.trim(),
    articleSummary: form.articleSummary.trim(), location: form.location.trim(),
    capacity: Number(form.capacity) }
  if (!result.title || !result.description || !result.location) throw new Error('请填写活动名称、地点和正文')
  if (!Number.isInteger(result.capacity) || result.capacity < 1) throw new Error('人数上限须大于 0')
  for (const field of TIME_FIELDS) {
    const value = form[field]
    if (!value.date || !value.time) throw new Error('请填写完整的活动、报名和签到时间')
    const instant = new Date(`${value.date}T${value.time}:00+08:00`)
    if (Number.isNaN(instant.getTime())) throw new Error('时间格式无效')
    result[field] = instant.toISOString()
  }
  return result
}
