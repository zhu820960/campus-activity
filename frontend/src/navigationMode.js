export function navigationMode(roles = []) {
  if (roles.includes('ADMIN')) return 'ADMIN'
  if (roles.includes('ORGANIZER')) return 'ORGANIZER'
  return 'NONE'
}
