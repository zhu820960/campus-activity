import { createRouter, createWebHistory } from 'vue-router'
import { hasRole, homePath, loadSession, setUser, user } from './session'
import { api } from './api'
import AccountView from './views/AccountView.vue'
import LoginView from './views/LoginView.vue'
import OrganizerView from './views/OrganizerView.vue'
import ActivityEditorView from './views/ActivityEditorView.vue'
import CheckinHostView from './views/CheckinHostView.vue'
import AdminView from './views/AdminView.vue'
import AdminActivityDetailView from './views/AdminActivityDetailView.vue'
import { legacyAdminPath } from './adminSections'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior() { return { top: 0 } },
  routes: [
    { path: '/', redirect: () => homePath() },
    { path: '/account', component: AccountView, meta: { auth: true } },
    { path: '/organizer/:id', component: CheckinHostView, meta: { role: 'ORGANIZER', organizerArea: true } },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/organizer', component: OrganizerView, meta: { role: 'ORGANIZER', organizerArea: true } },
    { path: '/organizer/new', component: ActivityEditorView, meta: { role: 'ORGANIZER', organizerArea: true } },
    { path: '/organizer/:id/edit', component: ActivityEditorView, meta: { role: 'ORGANIZER', organizerArea: true } },
    { path: '/organizer/:id/checkin', component: CheckinHostView, meta: { role: 'ORGANIZER', organizerArea: true } },
    { path: '/admin', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/activities', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/reviews', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/checkins', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/participation', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/announcements', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/users', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/audit', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/admin/activities/new', component: ActivityEditorView, meta: { role: 'ADMIN' } },
    { path: '/admin/activities/:id/edit', component: ActivityEditorView, meta: { role: 'ADMIN' } },
    { path: '/admin/activities/:id/checkin', component: CheckinHostView, meta: { role: 'ADMIN' } },
    { path: '/admin/activities/:id', component: AdminActivityDetailView, meta: { role: 'ADMIN' } },
    { path: '/:pathMatch(.*)*', redirect: () => homePath() },
  ],
})

router.beforeEach(async (to) => {
  if (to.path === '/admin' && to.query.tab) {
    const target = legacyAdminPath(to.query.tab)
    if (target && target !== '/admin') return target
  }
  await loadSession()
  if (user.value && !hasRole('ADMIN') && !hasRole('ORGANIZER')) {
    await api('/auth/logout', { method: 'POST' }).catch(() => {})
    setUser(null)
  }
  if (to.path === '/login' && user.value) return homePath()
  if ((to.meta.auth || to.meta.role) && !user.value) {
    return { name: 'login', query: { next: to.fullPath } }
  }
  if (to.meta.role && !hasRole(to.meta.role)) return homePath()
  if (hasRole('ADMIN') && to.meta.organizerArea) return '/admin'
})

export default router
