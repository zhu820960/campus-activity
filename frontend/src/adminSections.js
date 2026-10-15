export const adminSections = [
  { key: 'overview', path: '/admin', label: '运营工作台', description: '全平台活动与参与概览' },
  { key: 'activities', path: '/admin/activities', label: '活动管理', description: '检索活动并进入编辑或详情' },
  { key: 'reviews', path: '/admin/reviews', label: '活动审核', description: '处理组织者提交的活动' },
  { key: 'checkins', path: '/admin/checkins', label: '现场签到', description: '查看签到窗口与现场二维码' },
  { key: 'participation', path: '/admin/participation', label: '参与记录', description: '跨活动检索报名与签到记录' },
  { key: 'announcements', path: '/admin/announcements', label: '公告管理', description: '发布、编辑和管理校园公告' },
  { key: 'users', path: '/admin/users', label: '用户与权限', description: '查看账号并管理组织者授权' },
  { key: 'audit', path: '/admin/audit', label: '操作审计', description: '检索活动与权限操作记录' },
]

export function adminSectionForPath(path) {
  if (path.startsWith('/admin/activities/')) return adminSections[1]
  return adminSections.find(section => section.path === path) || adminSections[0]
}

export function legacyAdminPath(tab) {
  return adminSections.find(section => section.key === tab)?.path || null
}
