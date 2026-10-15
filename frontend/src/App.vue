<script setup>
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { Bell, CalendarDays, ClipboardCheck, LayoutDashboard, Megaphone, QrCode, ScrollText, ShieldCheck, TicketCheck, Users } from '@lucide/vue'
import { api } from './api'
import UserCenter from './components/UserCenter.vue'
import NotificationBell from './components/NotificationBell.vue'
import { hasRole, setUser, user } from './session'
import { navigationMode } from './navigationMode'
import { adminSections, adminSectionForPath } from './adminSections'

const router = useRouter()
const route = useRoute()
const previewMode = import.meta.env.VITE_STAGE2_PREVIEW === 'true'
const adminIcons = { overview: LayoutDashboard, activities: CalendarDays, reviews: ShieldCheck,
  checkins: QrCode, participation: TicketCheck, announcements: Megaphone, users: Users, audit: ScrollText }
const adminNavigation = adminSections.map((section, index) => ({
  to: section.path, label: section.label, icon: adminIcons[section.key],
  group: index === 0 ? '运营' : index === 5 ? '管理' : '',
}))
const organizerNavigation = [{ to: '/organizer', label: '活动管理', icon: LayoutDashboard }]
const mode = computed(() => navigationMode(user.value?.roles))
const navigation = computed(() => mode.value === 'ADMIN' ? adminNavigation : organizerNavigation)
const sectionTitle = computed(() => route.path === '/account' ? '个人资料' : mode.value === 'ADMIN' ? adminSectionForPath(route.path).label : '活动管理')

function adminActive(item) {
  return adminSectionForPath(route.path).path === item.to
}

async function logout() {
  await api('/auth/logout', { method: 'POST' }).catch(() => {})
  setUser(null)
  router.push('/login')
}
</script>

<template>
  <div class="app-shell" :class="{ 'auth-shell': !user, 'admin-shell': hasRole('ADMIN') }">
    <aside v-if="user" class="sidebar">
      <div class="brand"><span class="brand-mark"><ClipboardCheck :size="21" /></span><span>校园活动<small class="brand-caption">管理工作台</small></span></div>
      <nav aria-label="主导航">
        <template v-for="item in navigation" :key="item.to">
          <span v-if="hasRole('ADMIN') && item.group" class="nav-group-label">{{ item.group }}</span>
          <RouterLink :to="item.to" class="nav-link" :active-class="hasRole('ADMIN') ? 'admin-route-match' : 'router-link-active'" :class="{ 'is-active': hasRole('ADMIN') && adminActive(item) }">
            <component :is="item.icon" :size="18" />{{ item.label }}
          </RouterLink>
        </template>
      </nav>
    </aside>
    <div class="workspace">
      <div v-if="previewMode" class="preview-banner" role="status">独立测试副本 · 仅供功能验收，数据不会写入日常开发库</div>
      <header v-if="user" class="topbar">
        <div class="topbar-context"><span class="topbar-brand"><span class="brand-mark"><ClipboardCheck :size="17" /></span>校园活动</span><span class="topbar-divider"></span><span class="topbar-section">{{ sectionTitle }}</span></div>
        <div class="topbar-actions"><NotificationBell /><UserCenter :account="user" @logout="logout" /></div>
      </header>
      <main><RouterView :key="route.path.endsWith('/edit') || route.path.endsWith('/new') ? 'activity-editor' : route.path" /></main>
    </div>
    <nav v-if="user" class="mobile-nav" aria-label="移动端导航">
      <RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :active-class="hasRole('ADMIN') ? 'admin-route-match' : 'router-link-active'" :class="{ 'is-active': hasRole('ADMIN') && adminActive(item) }"><component :is="item.icon" :size="20" /><span>{{ item.label }}</span></RouterLink>
    </nav>
  </div>
</template>
