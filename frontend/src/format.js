export function dateTime(value) {
  if (!value) return '待定'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

export function shortDate(value) {
  const date = new Date(value)
  return {
    day: new Intl.DateTimeFormat('zh-CN', { day: '2-digit' }).format(date),
    month: new Intl.DateTimeFormat('zh-CN', { month: 'short' }).format(date),
  }
}

export function personInitial(value) {
  return String(value || '').replace(/^(?:\[[^\]]+\]\s*)+/, '').trim().slice(0, 1) || '用'
}

export const statusLabel = {
  DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', CANCELLED: '已取消',
}
