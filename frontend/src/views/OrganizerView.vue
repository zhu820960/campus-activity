<script setup>
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight, Bell, CalendarPlus, ClipboardList, QrCode } from '@lucide/vue'
import { api } from '../api'
import { dateTime, statusLabel } from '../format'

const rows = ref([])
const messages = ref([])
const feedbackActivity = ref(null)
const feedbackItems = ref([])
const feedbackSummary = ref({ count: 0, averageRating: 0 })
const feedbackBusy = ref(false)
const reviewActivity = ref(null)
const reviewHistory = ref([])
const reviewBusy = ref(false)
const error = ref('')
const busyId = ref(null)
const cancelTarget = ref(null)
const cancelReason = ref('')
const cancelError = ref('')
const cancelLoading = ref(false)
const cancelPending = ref(null)
const cancelVerified = ref(false)
const success = ref('')
async function openCancellation(activity) {
  cancelTarget.value = activity; cancelReason.value = ''; cancelError.value = ''; cancelPending.value = null
  cancelVerified.value = false; cancelLoading.value = true
  try {
    const [change, cancellation] = await Promise.all([
      api(`/organizer/activities/${activity.id}/changes`),
      api(`/organizer/activities/${activity.id}/cancellation`),
    ])
    cancelPending.value = change?.id ? change : cancellation?.id ? cancellation : null
    cancelVerified.value = true
  } catch (cause) { cancelError.value = cause.message }
  finally { cancelLoading.value = false }
}
async function requestCancellation() {
  if (busyId.value || !cancelVerified.value || cancelLoading.value || cancelPending.value || !cancelReason.value.trim()) return
  busyId.value = cancelTarget.value.id; cancelError.value = ''
  try {
    await api(`/organizer/activities/${cancelTarget.value.id}/cancellation`, { method: 'POST', body: { reason: cancelReason.value.trim() } })
    cancelTarget.value = null; success.value = '下架申请已提交，等待管理员审核；审核通过前活动保持发布状态。'
    await load()
  } catch (cause) { cancelError.value = cause.message }
  finally { busyId.value = null }
}
async function load() {
  try {
    rows.value = await api('/organizer/activities')
    messages.value = await api('/organizer/messages').catch(cause => {
      if (cause.status === 404) return []
      throw cause
    })
  }
  catch (cause) { error.value = cause.message }
}
async function markRead(message) {
  if (message.readAt) return
  try {
    const updated = await api(`/organizer/messages/${message.id}/read`, { method: 'POST' })
    messages.value = messages.value.map(item => item.id === message.id ? updated : item)
  } catch (cause) { error.value = cause.message }
}
async function showFeedback(activity) {
  feedbackActivity.value = activity
  feedbackBusy.value = true; error.value = ''
  try {
    [feedbackItems.value, feedbackSummary.value] = await Promise.all([
      api(`/organizer/activities/${activity.id}/feedback`),
      api(`/organizer/activities/${activity.id}/feedback/summary`),
    ])
  } catch (cause) { error.value = cause.message; feedbackItems.value = [] }
  finally { feedbackBusy.value = false }
}
async function showReviews(activity) {
  reviewActivity.value = activity
  reviewBusy.value = true; error.value = ''
  try { reviewHistory.value = await api(`/organizer/activities/${activity.id}/reviews`) }
  catch (cause) { error.value = cause.message; reviewHistory.value = [] }
  finally { reviewBusy.value = false }
}
async function submit(activity) {
  busyId.value = activity.id
  error.value = ''
  try { await api(`/organizer/activities/${activity.id}/submit`, { method: 'POST' }); await load() }
  catch (cause) { error.value = cause.message }
  finally { busyId.value = null }
}
onMounted(load)
</script>

<template>
  <section class="page-heading"><div><p class="eyebrow">组织者工作台</p><h1>活动管理</h1><p>创建活动、提交审核与查看现场数据</p></div><RouterLink class="button primary" to="/organizer/new"><CalendarPlus :size="17" />新建活动</RouterLink></section>
  <p v-if="error" class="message error">{{ error }}</p>
  <p v-if="success" class="message success">{{ success }}</p>
  <section class="organizer-messages"><div class="section-title"><Bell :size="19" /><h2>个人消息</h2><span class="muted-text">{{ messages.filter(item => !item.readAt).length }} 条未读</span></div><p v-if="!messages.length" class="muted-text">暂无个人消息</p><button v-for="message in messages.slice(0, 8)" :key="message.id" class="organizer-message-row" :class="{ unread: !message.readAt }" @click="markRead(message)"><strong>{{ message.title }}</strong><span>{{ message.body }}</span><time>{{ dateTime(message.createdAt) }}</time></button></section>
  <div v-if="!rows.length" class="empty-state"><ClipboardList :size="30" /><strong>还没有创建活动</strong><RouterLink class="button secondary" to="/organizer/new">新建活动</RouterLink></div>
  <div v-else class="record-list organizer-activity-list"><article v-for="activity in rows" :key="activity.id" class="organizer-activity-item">
    <div class="organizer-activity-date"><CalendarPlus :size="18" /><span>{{ dateTime(activity.startsAt) }}</span></div>
    <div class="organizer-activity-main"><div><h2>{{ activity.title }}</h2><span class="status" :class="activity.status.toLowerCase()">{{ statusLabel[activity.status] }}</span></div><p v-if="activity.reviewNote" class="record-attention">驳回原因：{{ activity.reviewNote }}</p><span class="record-overline">{{ activity.registeredCount }} / {{ activity.capacity }} 人报名</span></div>
    <div class="organizer-activity-actions"><RouterLink v-if="['DRAFT', 'REJECTED'].includes(activity.status)" :to="`/organizer/${activity.id}/edit`" class="text-button">编辑<ArrowRight :size="14" /></RouterLink><button v-if="['DRAFT', 'REJECTED'].includes(activity.status)" class="text-button" :disabled="busyId === activity.id" @click="submit(activity)">提交审核</button><RouterLink v-if="activity.status === 'PUBLISHED'" :to="`/organizer/${activity.id}/checkin`" class="text-button"><QrCode :size="15" />签到与统计</RouterLink><RouterLink v-if="activity.status === 'PUBLISHED'" :to="`/organizer/${activity.id}/edit`" class="text-button">申请修改</RouterLink><button v-if="activity.status === 'PUBLISHED'" class="text-button danger-action" @click="openCancellation(activity)">申请下架</button><button v-if="activity.status === 'PUBLISHED'" class="text-button" @click="showFeedback(activity)">查看反馈</button><button class="text-button" @click="showReviews(activity)">审核历史</button></div>
  </article></div>
  <section v-if="reviewActivity" class="organizer-review-history"><h2>{{ reviewActivity.title }} · 审核历史</h2><p class="admin-scope-note">仅含新版本启用后记录的审核意见，旧记录不能补造。</p><p v-if="reviewBusy" class="muted-text">正在加载…</p><p v-else-if="!reviewHistory.length" class="muted-text">暂无审核记录</p><div v-for="(event, index) in reviewHistory" :key="index" class="admin-audit-row"><span>{{ event.action === 'REVIEW_APPROVED' ? '审核通过' : '审核驳回' }}<small v-if="event.detail" class="cell-note">{{ event.detail }}</small></span><strong>{{ event.actorName }}</strong><time>{{ dateTime(event.createdAt) }}</time></div></section>
  <section v-if="feedbackActivity" class="feedback-admin"><h2>{{ feedbackActivity.title }} · 活动反馈</h2><p class="admin-scope-note">有效评价 {{ feedbackSummary.count }} 条 · 平均 {{ feedbackSummary.count ? feedbackSummary.averageRating.toFixed(1) : '—' }} 分</p><p v-if="feedbackBusy" class="muted-text">正在加载反馈…</p><p v-else-if="!feedbackItems.length" class="muted-text">暂无活动评价</p><article v-for="item in feedbackItems" :key="item.id" class="feedback-admin-row"><div><strong>{{ item.studentName }} · {{ item.rating }}/5</strong><p>{{ item.comment }}</p><small>{{ dateTime(item.updatedAt) }}</small></div></article></section>
  <div v-if="cancelTarget" class="admin-modal-backdrop" @keydown.esc="!busyId && !cancelLoading && (cancelTarget = null)"><form class="admin-modal" role="dialog" aria-modal="true" aria-labelledby="cancellation-title" @submit.prevent="requestCancellation"><h2 id="cancellation-title">申请下架活动</h2><p>{{ cancelTarget.title }}</p><p>管理员审核通过后活动将取消，停止报名与签到，已报名学生收到通知，历史记录保留。</p><p v-if="cancelLoading" role="status">正在核对待审申请…</p><p v-if="cancelPending" class="message error">已有修改或下架申请待审核，请等待处理后再操作。</p><label v-if="!cancelPending">下架原因<textarea v-model.trim="cancelReason" required maxlength="500" rows="4" :disabled="cancelLoading || !!busyId" /></label><p v-if="cancelError" class="message error" role="alert">{{ cancelError }}</p><div class="row-actions"><button type="button" class="button secondary" :disabled="!!busyId || cancelLoading" @click="cancelTarget = null">返回</button><button type="submit" class="button primary" :disabled="!!busyId || cancelLoading || !!cancelPending || !cancelVerified || !cancelReason.trim()">提交下架审核</button></div></form></div>
</template>
