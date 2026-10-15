<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Bell } from '../icons'
import { api } from '../api'
import { dateTime } from '../format'

const router = useRouter()
const route = useRoute()
const open = ref(false)
const root = ref(null)
const rows = ref([])
const error = ref('')
const loading = ref(false)
const reading = ref(null)
const unread = ref(0)
let timer
let active = true
async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const [result, count] = await Promise.all([api('/account/notifications'), api('/account/notifications/unread-count')])
    if (active) { rows.value = result; unread.value = count.count; error.value = '' }
  } catch (cause) { if (active) error.value = cause.message }
  finally { loading.value = false }
}
function toggle() { open.value = !open.value; if (open.value) load() }
async function follow(row) {
  if (reading.value) return
  reading.value = row.id
  try {
    if (!row.readAt) {
      const updated = await api(`/account/notifications/${row.id}/read`, { method: 'POST' })
      rows.value = rows.value.map(item => item.id === row.id ? updated : item)
      unread.value = Math.max(0, unread.value - 1)
    }
    if (!/^\/(admin(?:\/|$)|organizer(?:\/|$)|account(?:\?|$))/.test(row.targetPath || '')) throw new Error('通知跳转地址无效')
    open.value = false
    await router.push(row.targetPath)
  } catch (cause) { error.value = cause.message }
  finally { reading.value = null }
}
function outside(event) { if (!root.value?.contains(event.target)) open.value = false }
function escape(event) { if (event.key === 'Escape') open.value = false }
watch(() => route.fullPath, () => { open.value = false; load() })
onMounted(() => {
  load()
  timer = setInterval(() => { if (!document.hidden) load() }, 30000)
  document.addEventListener('pointerdown', outside)
  document.addEventListener('keydown', escape)
})
onUnmounted(() => {
  active = false; clearInterval(timer)
  document.removeEventListener('pointerdown', outside)
  document.removeEventListener('keydown', escape)
})
</script>

<template>
  <div ref="root" class="notification-center">
    <button type="button" class="notification-trigger" :aria-expanded="open" :aria-label="`通知，${unread} 条未读`" @click="toggle"><Bell :size="19" /><span v-if="unread" class="notification-badge">{{ unread > 99 ? '99+' : unread }}</span></button>
    <section v-if="open" class="notification-panel" aria-label="通知列表">
      <div class="notification-heading"><h2>通知</h2><button type="button" class="text-button" :disabled="loading" @click="load">刷新</button></div>
      <p v-if="error" class="message error" role="alert">通知加载失败：{{ error }}</p>
      <p v-if="loading && !rows.length" class="muted-text">正在加载…</p>
      <p v-else-if="!rows.length && !error" class="muted-text">暂无通知</p>
      <button v-for="row in rows" :key="row.id" type="button" class="notification-item" :class="{ unread: !row.readAt }" :disabled="reading !== null" @click="follow(row)"><strong>{{ row.title }}<span v-if="!row.readAt"> · 未读</span></strong><span>{{ row.body }}</span><time>{{ dateTime(row.createdAt) }}</time><small class="notification-detail-link">查看详情 <ArrowRight :size="12" /></small></button>
      <p class="notification-footnote">最近 100 条通知；已读不代表业务已处理。</p>
    </section>
  </div>
</template>
