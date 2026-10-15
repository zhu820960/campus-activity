export function activityPhase(activity, now = Date.now()) {
  if (now < Date.parse(activity.startsAt)) return 'UPCOMING'
  if (now < Date.parse(activity.endsAt)) return 'ONGOING'
  return 'ENDED'
}

export const phaseLabel = {
  UPCOMING: '未开始',
  ONGOING: '进行中',
  ENDED: '已结束',
}
