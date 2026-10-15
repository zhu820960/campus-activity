<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import QRCode from 'qrcode'
import { ArrowLeft, CheckCircle2, CircleHelp, QrCode, RefreshCw, Users } from '@lucide/vue'
import { api } from '../api'
import { buildCheckinPayload } from '../checkinPayload'
import { dateTime, personInitial } from '../format'

const route = useRoute()
const id = Number(route.params.id)
const adminMode = route.path.startsWith('/admin/')
const base = adminMode ? `/admin/activities/${id}` : `/organizer/activities/${id}`
const activity = ref(null)
const stats = ref(null)
const rows = ref([])
const qr = ref('')
const expiresAt = ref(0)
const seconds = ref(0)
const clock = ref(Date.now())
const busy = ref(false)
const error = ref('')
const message = ref('')
const manualId = ref(null)
const manualReason = ref('')
const reviews = ref([])
const reviewError = ref('')
let timer
const canIssue = computed(() => activity.value?.status === 'PUBLISHED'
  && clock.value >= Date.parse(activity.value.checkinOpensAt)
  && clock.value <= Date.parse(activity.value.checkinClosesAt))
const windowMessage = computed(() => !activity.value ? ''
  : clock.value < Date.parse(activity.value.checkinOpensAt) ? '签到尚未开始'
    : clock.value > Date.parse(activity.value.checkinClosesAt) ? '签到时段已结束' : '')

async function refreshData() {
  try {
    const [nextStats, nextRows] = await Promise.all([
      api(`${base}/stats`), api(`${base}/registrations`),
    ])
    stats.value = nextStats
    rows.value = nextRows
  } catch (cause) { error.value = cause.message }
}

async function refreshQr() {
  busy.value = true
  error.value = ''
  try {
    const issued = await api(`${base}/checkin-token`, { method: 'POST' })
    const payload = buildCheckinPayload(id, issued.token)
    qr.value = await QRCode.toDataURL(payload, { width: 300, margin: 1, color: { dark: '#183a34', light: '#ffffff' } })
    expiresAt.value = Date.parse(issued.expiresAt)
    seconds.value = Math.max(0, Math.ceil((expiresAt.value - Date.now()) / 1000))
  } catch (cause) { error.value = cause.message; qr.value = '' }
  finally { busy.value = false }
}

async function manual(row) {
  error.value = ''
  message.value = ''
  try {
    await api(`${base}/manual-checkin`, {
      method: 'POST', body: { studentId: row.studentId, reason: manualReason.value },
    })
    manualId.value = null
    manualReason.value = ''
    message.value = `${row.studentName} 补签成功`
    await refreshData()
  } catch (cause) { error.value = cause.message }
}

onMounted(async () => {
  try { activity.value = await api(adminMode ? `/admin/activities/${id}` : `/activities/${id}`); await refreshData() }
  catch (cause) { error.value = cause.message }
  if (!adminMode) {
    try { reviews.value = await api(`${base}/reviews`) }
    catch (cause) { reviewError.value = cause.message }
  }
  timer = setInterval(async () => {
    clock.value = Date.now()
    if (!qr.value) return
    if (!canIssue.value) { qr.value = ''; return }
    seconds.value = Math.max(0, Math.ceil((expiresAt.value - Date.now()) / 1000))
    if (seconds.value === 0 && !busy.value) await refreshQr()
  }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <RouterLink :to="adminMode ? `/admin/activities/${id}` : '/organizer'" class="back-link"><ArrowLeft :size="16" />返回{{ adminMode ? '活动详情' : '活动管理' }}</RouterLink>
  <section class="page-heading"><div><p class="eyebrow">现场管理</p><h1>{{ activity?.title || '活动签到' }}</h1><p v-if="activity">{{ dateTime(activity.startsAt) }} · {{ activity.location }}</p></div><button class="button secondary" @click="refreshData"><RefreshCw :size="16" />刷新名单</button></section>
  <p v-if="error" class="message error">{{ error }}</p><p v-if="message" class="message success">{{ message }}</p>
  <section v-if="!adminMode && activity" class="account-panel"><h2>活动详情</h2><p>状态：{{ { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', CANCELLED: '已取消' }[activity.status] }}</p><p>{{ activity.description }}</p><p v-if="activity.reviewNote">审核意见：{{ activity.reviewNote }}</p><RouterLink v-if="['DRAFT', 'REJECTED'].includes(activity.status)" class="button secondary" :to="`/organizer/${id}/edit`">编辑并重新提交</RouterLink><h3>审核历史</h3><p v-if="reviewError" class="message error">{{ reviewError }}</p><p v-else-if="!reviews.length" class="muted-text">暂无审核记录</p><p v-for="(review, index) in reviews" :key="index">{{ review.action === 'REVIEW_APPROVED' ? '审核通过' : '审核驳回' }} · {{ review.detail || '无附加意见' }} · {{ dateTime(review.createdAt) }}</p></section>
  <div class="host-layout">
    <section class="qr-section"><div class="section-title"><QrCode :size="19" /><h2>现场二维码</h2></div><div class="qr-frame"><img v-if="qr && canIssue" :src="qr" alt="活动签到二维码" /><div v-else class="qr-placeholder"><QrCode :size="48" /></div></div><p v-if="qr && canIssue" class="muted-text">{{ seconds }} 秒后更新 · 仅供小程序内扫码</p><p v-else-if="windowMessage" class="muted-text">{{ windowMessage }}</p><button class="button secondary" :disabled="busy || !canIssue" @click="refreshQr"><RefreshCw :size="16" />{{ qr ? '更新二维码' : '生成二维码' }}</button></section>
    <section class="host-content"><div v-if="stats" class="stats-strip"><div><span>报名人数</span><strong>{{ stats.registeredCount }}</strong></div><div><span>已签到</span><strong>{{ stats.checkedCount }}</strong></div><div><span>未签到</span><strong>{{ stats.absentCount }}</strong></div><div><span>名额上限</span><strong>{{ stats.capacity }}</strong></div></div><div class="section-title"><Users :size="19" /><h2>报名名单</h2></div><div v-if="!rows.length" class="empty-state compact-empty">暂无报名记录</div><div v-else class="record-list roster-list"><article v-for="row in rows" :key="row.id" class="roster-item">
      <div class="record-avatar">{{ personInitial(row.studentName) }}</div>
      <div class="roster-person"><strong>{{ row.studentName }}</strong><span>学号 {{ row.studentNumber || '待补录' }}</span><small v-if="row.studentEmail">{{ row.studentEmail }}</small></div>
      <div class="roster-attendance"><span class="status" :class="row.status === 'ACTIVE' ? 'published' : 'muted'">{{ row.status === 'ACTIVE' ? '有效报名' : '已取消' }}</span><span v-if="row.checkedAt" class="status published"><CheckCircle2 :size="13" />{{ row.checkinMethod === 'MANUAL' ? '已补签' : '已签到' }}</span><span v-else class="status muted">未签到</span><small v-if="row.checkedAt">{{ dateTime(row.checkedAt) }}</small><small v-if="row.checkinMethod === 'MANUAL'">操作人：{{ row.checkinOperatorName || '未记录' }} · 原因：{{ row.manualCheckinReason || '未记录' }}</small></div>
      <div v-if="!adminMode" class="roster-actions"><button v-if="row.status === 'ACTIVE' && !row.checkedAt" class="text-button" @click="manualId = row.studentId; manualReason = ''"><CircleHelp :size="14" />补签</button></div>
      <div v-if="!adminMode && manualId === row.studentId" class="roster-inline-form"><label>补签原因<textarea v-model.trim="manualReason" rows="2" maxlength="500" placeholder="说明现场核验情况" /></label><div class="row-actions"><button class="button primary" :disabled="!manualReason" @click="manual(row)">确认补签</button><button class="button secondary" @click="manualId = null">取消</button></div></div>
    </article></div></section>
  </div>
</template>
