export function validStudentNumber(value) {
  return typeof value === 'string' && /^[0-9]{14}$/.test(value)
}

export function cleanRealName(value) {
  const name = typeof value === 'string' ? value.trim() : ''
  return name.length > 0 && name.length <= 80 ? name : ''
}
