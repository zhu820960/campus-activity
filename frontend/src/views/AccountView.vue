<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { user } from '../session'
const busy = ref(false)
const error = ref('')
const message = ref('')
const route = useRoute()
const notification = ref(null)
watch(() => route.query.notification, async id => {
  notification.value = null
  if (!/^[1-9][0-9]*$/.test(String(id || ''))) return
  try {
    const rows = await api('/account/notifications')
    notification.value = rows.find(row => String(row.id) === id) || null
    if (!notification.value) error.value = '通知不存在或已超出最近通知范围'
  } catch (cause) { error.value = cause.message }
}, { immediate: true })
async function upload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 2 * 1024 * 1024) {
    error.value = '请选择不超过2MB的 JPEG 或 PNG 图片'; event.target.value = ''; return
  }
  busy.value = true; error.value = ''; message.value = ''
  try {
    const body = new FormData(); body.append('file', file)
    const account = await api('/account/avatar', { method: 'POST', body })
    user.value = { ...account, avatarVersion: Date.now() }
    message.value = '头像已更新，与小程序共用本人头像'
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false; event.target.value = '' }
}
async function remove() {
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api('/account/avatar', { method: 'DELETE' })
    user.value = { ...user.value, hasAvatar: false, avatarVersion: Date.now() }
    message.value = '已恢复默认头像'
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
</script>
<template>
  <section class="page-heading"><div><p class="eyebrow">个人账号</p><h1>个人资料</h1><p>查看账号信息与管理本人头像</p></div></section>
  <p v-if="error" class="message error">{{ error }}</p><p v-if="message" class="message success">{{ message }}</p>
  <section v-if="notification" class="account-panel"><h2>{{ notification.title }}</h2><p>{{ notification.body }}</p><p v-if="notification.activityId" class="admin-scope-note">本人参与的活动可前往小程序查看。</p></section>
  <section v-if="user" class="account-panel"><h2>{{ user.name }}</h2><p>学号：{{ user.studentNumber || '不适用' }}</p><p>邮箱：{{ user.email || '未设置' }}</p><p>身份：{{ user.roles.includes('ADMIN') ? '管理员' : '活动组织者' }}</p><div class="account-avatar"><img v-if="user.hasAvatar" :src="`/api/account/avatar?v=${user.avatarVersion || 0}`" alt="本人头像" /><span v-else>{{ Array.from(user.name || '用')[0] }}</span></div><div class="row-actions"><label class="button secondary">{{ user.hasAvatar ? '更换头像' : '上传头像' }}<input class="visually-hidden" type="file" accept="image/jpeg,image/png" :disabled="busy" @change="upload" /></label><button v-if="user.hasAvatar" type="button" class="button secondary" :disabled="busy" @click="remove">移除头像</button></div><p class="admin-scope-note">JPEG 或 PNG，最多2MB；组织者与小程序共用本人头像。</p></section>
</template>
