<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ChevronDown, LogOut } from '../icons'

const props = defineProps({ account: { type: Object, required: true } })
const emit = defineEmits(['logout'])
const open = ref(false)
const avatarFailed = ref(false)
const root = ref(null)
const role = computed(() => props.account.roles?.includes('ADMIN') ? '超级管理员' : '活动组织者')
const avatar = computed(() => props.account.hasAvatar ? `/api/account/avatar?v=${props.account.avatarVersion || 0}` : '')
const initial = computed(() => Array.from((props.account.name || '用').trim())[0] || '用')
watch(avatar, () => { avatarFailed.value = false })

function closeOutside(event) {
  if (!root.value?.contains(event.target)) open.value = false
}
function closeOnEscape(event) {
  if (event.key === 'Escape') open.value = false
}
onMounted(() => {
  document.addEventListener('pointerdown', closeOutside)
  document.addEventListener('keydown', closeOnEscape)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', closeOutside)
  document.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <div ref="root" class="user-center">
    <button class="user-center-trigger" type="button" aria-label="打开用户菜单" :aria-expanded="open" aria-haspopup="menu" @click="open = !open">
      <span class="user-avatar" aria-hidden="true"><img v-if="avatar && !avatarFailed" :src="avatar" alt="" @error="avatarFailed = true" /><span v-else>{{ initial }}</span></span>
      <span class="user-center-summary"><strong>{{ account.name }}</strong><small>{{ role }}</small></span>
      <ChevronDown :size="16" :class="{ 'is-open': open }" />
    </button>
    <div v-if="open" class="user-center-menu" role="menu" aria-label="用户菜单">
      <div class="user-center-profile">
        <span class="user-avatar user-avatar-large" aria-hidden="true"><img v-if="avatar && !avatarFailed" :src="avatar" alt="" @error="avatarFailed = true" /><span v-else>{{ initial }}</span></span>
        <div><strong>{{ account.name }}</strong><span class="user-role">{{ role }}</span><small>{{ account.email || account.studentNumber || '账号信息未设置' }}</small></div>
      </div>
      <RouterLink to="/account" class="user-center-logout" role="menuitem" @click="open = false">个人资料与头像</RouterLink>
      <button class="user-center-logout" type="button" role="menuitem" @click="open = false; emit('logout')"><LogOut :size="17" />退出登录</button>
    </div>
  </div>
</template>
