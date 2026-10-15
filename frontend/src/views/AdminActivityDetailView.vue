<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ArrowLeft, Download, QrCode, RefreshCw } from '../icons'
import { api, apiFile } from '../api'
import { dateTime, personInitial, statusLabel } from '../format'
import { user } from '../session'
import ActivityMap from '../components/ActivityMap.vue'

const id = Number(useRoute().params.id)
const activity = ref(null)
const stats = ref(null)
const registrations = ref([])
const audit = ref([])
const feedbackItems = ref([])
const feedbackSummary = ref({ count: 0, averageRating: 0 })
const cancellationRequest = ref(null)
const changeRequest = ref(null)
const mutationAudits = ref([])
const cancellationReviewNote = ref('')
const emergencyReason = ref('')
const changeReviewNote = ref('')
const hideReasons = reactive({})
const auditLabels = { CREATED: '创建活动', EDITED: '编辑活动', DIRECT_PUBLISHED: '直接发布', REVIEW_APPROVED: '审核通过', REVIEW_REJECTED: '审核驳回', QR_ISSUED: '生成现场二维码', FEEDBACK_HIDDEN: '隐藏评价' }
const filter = ref('ALL')
const error = ref('')
const message = ref('')
const loading = ref(true)
const busy = ref(false)
const exporting = ref('')
const reviewNote = ref('')
const clock = ref(Date.now())
let clockTimer
const filtered = computed(() => registrations.value.filter((row) =>
  filter.value === 'ALL'
  || (filter.value === 'ACTIVE' && row.status === 'ACTIVE')
  || (filter.value === 'CHECKED' && row.checkedAt)
  || (filter.value === 'WAITING' && row.status === 'ACTIVE' && !row.checkedAt)
  || (filter.value === 'CANCELLED' && row.status === 'CANCELLED')))
const checkinEnded = computed(() => activity.value && clock.value > Date.parse(activity.value.checkinClosesAt))
const attendanceRate = computed(() => stats.value?.registeredCount
  ? `${Math.round(stats.value.checkedCount / stats.value.registeredCount * 100)}%` : '—')
const canCheckin = computed(() => activity.value?.status === 'PUBLISHED'
  && clock.value >= Date.parse(activity.value.checkinOpensAt)
  && clock.value <= Date.parse(activity.value.checkinClosesAt))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [nextActivity, nextStats, nextRegistrations, nextAudit, nextFeedback, nextFeedbackSummary, pendingCancellations, nextMutationAudits, pendingChanges] = await Promise.all([
      api(`/admin/activities/${id}`), api(`/admin/activities/${id}/stats`),
      api(`/admin/activities/${id}/registrations`), api(`/admin/activities/${id}/audit`),
      api(`/admin/activities/${id}/feedback`).catch(cause => { if (cause.status === 404) return []; throw cause }),
      api(`/admin/activities/${id}/feedback/summary`).catch(cause => { if (cause.status === 404) return { count: 0, averageRating: 0 }; throw cause }),
      api('/admin/activity-cancellations/pending').catch(cause => { if (cause.status === 404) return []; throw cause }),
      api(`/admin/activities/${id}/cancellation/audit`).catch(cause => { if (cause.status === 404) return []; throw cause }),
      api('/admin/activity-changes/pending').catch(cause => { if (cause.status === 404) return []; throw cause }),
    ])
    activity.value = nextActivity
    stats.value = nextStats
    registrations.value = nextRegistrations
    audit.value = nextAudit
    feedbackItems.value = nextFeedback
    feedbackSummary.value = nextFeedbackSummary
    cancellationRequest.value = pendingCancellations.find(row => row.activityId === id) || null
    changeRequest.value = pendingChanges.find(row => row.activityId === id) || null
    mutationAudits.value = nextMutationAudits
  } catch (cause) { error.value = cause.message }
  finally { loading.value = false }
}
async function hideFeedback(item) {
  const reason = hideReasons[item.id]?.trim()
  if (!reason) return
  busy.value = true; error.value = ''
  try {
    await api(`/admin/feedback/${item.id}/hide`, { method: 'POST', body: { reason } })
    hideReasons[item.id] = ''
    message.value = '评价已隐藏'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
onMounted(() => { load(); clockTimer = setInterval(() => { clock.value = Date.now() }, 15000) })
onUnmounted(() => clearInterval(clockTimer))

async function review(approved) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await api(`/admin/activities/${id}/review`, { method: 'POST', body: {
      approved, note: approved ? '' : reviewNote.value.trim(),
    } })
    message.value = approved ? '活动已通过审核' : '活动已驳回'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function publish() {
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/activities/${id}/publish`, { method: 'POST' })
    message.value = '活动已发布'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function reviewCancellation(approved) {
  if (!cancellationRequest.value || (!approved && !cancellationReviewNote.value.trim())) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api(`/admin/activity-cancellations/${cancellationRequest.value.id}/review`, {
      method: 'POST', body: { approved, note: cancellationReviewNote.value.trim() },
    })
    message.value = approved ? '活动已取消，已通知可追溯的有效报名者' : '取消申请已驳回'
    cancellationReviewNote.value = ''
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function emergencyCancel() {
  const reason = emergencyReason.value.trim()
  if (!reason || busy.value || !window.confirm('确认立即取消活动？已报名学生将收到站内通知。')) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api(`/admin/activities/${id}/cancellation`, { method: 'POST', body: { reason } })
    message.value = '活动已紧急取消'
    emergencyReason.value = ''
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function reviewChange(approved) {
  if (!changeRequest.value || (!approved && !changeReviewNote.value.trim())) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api(`/admin/activity-changes/${changeRequest.value.id}/review`, {
      method: 'POST', body: { approved, note: changeReviewNote.value.trim() },
    })
    message.value = approved ? '活动变更已生效' : '变更申请已驳回'
    changeReviewNote.value = ''
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function exportRoster(kind) {
  if (exporting.value || !window.confirm('名单包含姓名和学号。确认下载并仅用于活动管理？')) return
  exporting.value = kind; error.value = ''
  try {
    const blob = await apiFile(`/admin/activities/${id}/exports/${kind}`)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `activity-${id}-${kind}.xlsx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 30000)
  } catch (cause) { error.value = cause.message }
  finally { exporting.value = '' }
}
</script>

<template>
  <RouterLink class="back-link" to="/admin/activities"><ArrowLeft :size="16" />返回活动管理</RouterLink>
  <p v-if="error" class="message error">{{ error }}</p>
  <p v-if="message" class="message success">{{ message }}</p>
  <div v-if="loading" class="empty-state">正在加载活动数据…</div>
  <template v-else-if="activity && stats">
    <section class="page-heading"><div><p class="eyebrow">活动详情 / #{{ activity.id }}</p><h1>{{ activity.title }}</h1><p>{{ activity.organizerName }} · {{ dateTime(activity.startsAt) }} · {{ activity.location }}</p></div><div class="row-actions"><button class="button secondary" @click="load"><RefreshCw :size="16" />刷新</button><RouterLink v-if="canCheckin" class="button primary" :to="`/admin/activities/${id}/checkin`" title="展示现场二维码"><QrCode :size="16" />现场二维码</RouterLink><RouterLink v-if="activity.status === 'PUBLISHED' && !changeRequest && !cancellationRequest" class="button secondary" :to="`/admin/activities/${id}/edit`">修改活动</RouterLink><RouterLink v-if="['DRAFT', 'REJECTED'].includes(activity.status) && activity.organizerId === user?.id" class="button secondary" :to="`/admin/activities/${id}/edit`">编辑活动</RouterLink><button v-if="['DRAFT', 'REJECTED', 'PENDING'].includes(activity.status) && activity.organizerId === user?.id" class="button primary" :disabled="busy" @click="publish">直接发布</button></div></section>
    <div class="admin-summary"><div><span>活动状态</span><strong class="admin-status"><span class="status" :class="activity.status.toLowerCase()">{{ statusLabel[activity.status] }}</span></strong></div><div><span>有效报名 / 名额</span><strong>{{ stats.registeredCount }} / {{ stats.capacity }}</strong></div><div><span>{{ checkinEnded ? '实到人数' : '已签到人数' }}</span><strong>{{ stats.checkedCount }}</strong></div><div><span>{{ checkinEnded ? '到场率' : '签到进度' }}</span><strong>{{ attendanceRate }}</strong></div></div>
    <div class="admin-activity-info"><div><span>报名时间</span><strong>{{ dateTime(activity.registrationOpensAt) }} 至 {{ dateTime(activity.registrationClosesAt) }}</strong></div><div><span>签到时间</span><strong>{{ dateTime(activity.checkinOpensAt) }} 至 {{ dateTime(activity.checkinClosesAt) }}</strong></div><div><span>活动地点</span><strong>{{ activity.location }}</strong></div><div v-if="activity.reviewNote"><span>当前审核意见（非历史）</span><strong>{{ activity.reviewNote }}</strong></div><div v-if="activity.status === 'CANCELLED'"><span>取消原因</span><strong>{{ activity.cancelReason || '未填写' }}</strong></div></div>
    <section v-if="activity.status === 'PUBLISHED'" class="admin-review-panel"><h2>活动取消</h2>
      <template v-if="cancellationRequest"><p>组织者申请取消：{{ cancellationRequest.reason }}</p><p>提交时间：{{ dateTime(cancellationRequest.createdAt) }}</p><label>审核意见<input v-model.trim="cancellationReviewNote" maxlength="1000" placeholder="驳回时必填" /></label><div class="row-actions"><button class="button secondary" :disabled="busy || !cancellationReviewNote" @click="reviewCancellation(false)">驳回申请</button><button class="button primary" :disabled="busy" @click="reviewCancellation(true)">确认取消</button></div></template>
      <label>紧急取消原因<input v-model.trim="emergencyReason" maxlength="500" placeholder="管理员紧急取消时填写" /></label><button class="button secondary" :disabled="busy || !emergencyReason" @click="emergencyCancel">立即取消活动</button>
    </section>
    <section v-if="changeRequest" class="admin-review-panel"><h2>待审核活动变更</h2><p>修改原因：{{ changeRequest.reason }}</p><p>原活动内容在批准前继续对学生展示。</p>
      <div class="admin-activity-info"><div><span>标题</span><strong>{{ activity.title }} → {{ changeRequest.proposed.title }}</strong></div><div><span>活动时间</span><strong>{{ dateTime(activity.startsAt) }} 至 {{ dateTime(activity.endsAt) }} → {{ dateTime(changeRequest.proposed.startsAt) }} 至 {{ dateTime(changeRequest.proposed.endsAt) }}</strong></div><div><span>报名时间</span><strong>{{ dateTime(activity.registrationOpensAt) }} 至 {{ dateTime(activity.registrationClosesAt) }} → {{ dateTime(changeRequest.proposed.registrationOpensAt) }} 至 {{ dateTime(changeRequest.proposed.registrationClosesAt) }}</strong></div><div><span>签到时间</span><strong>{{ dateTime(activity.checkinOpensAt) }} 至 {{ dateTime(activity.checkinClosesAt) }} → {{ dateTime(changeRequest.proposed.checkinOpensAt) }} 至 {{ dateTime(changeRequest.proposed.checkinClosesAt) }}</strong></div><div><span>地点</span><strong>{{ activity.location }} → {{ changeRequest.proposed.location }}</strong></div><div><span>名额</span><strong>{{ activity.capacity }} → {{ changeRequest.proposed.capacity }}</strong></div></div>
      <details><summary>查看候选推文、封面和坐标</summary><p>{{ changeRequest.proposed.articleSummary }}</p><p class="long-copy">{{ changeRequest.proposed.description }}</p><p>坐标：{{ changeRequest.proposed.longitude ?? '未选' }}，{{ changeRequest.proposed.latitude ?? '未选' }}</p><img v-if="changeRequest.proposed.coverImageUrl" class="admin-story-cover" :src="changeRequest.proposed.coverImageUrl" alt="候选封面" /></details>
      <label>审核意见<input v-model.trim="changeReviewNote" maxlength="1000" placeholder="驳回时必填" /></label><div class="row-actions"><button class="button secondary" :disabled="busy || !changeReviewNote" @click="reviewChange(false)">驳回变更</button><button class="button primary" :disabled="busy" @click="reviewChange(true)">批准并生效</button></div>
    </section>
    <section class="admin-story"><h2>活动推文</h2><img v-if="activity.coverImageUrl" class="admin-story-cover" :src="activity.coverImageUrl" alt="活动封面" /><p v-if="activity.articleSummary" class="admin-story-summary">{{ activity.articleSummary }}</p><p class="long-copy">{{ activity.description }}</p></section>
    <section v-if="Number.isFinite(activity.longitude) && Number.isFinite(activity.latitude)" class="admin-map"><h2>活动位置</h2><ActivityMap :longitude="activity.longitude" :latitude="activity.latitude" /></section>
    <section v-if="activity.status === 'PENDING' && activity.organizerId !== user?.id" class="admin-review-panel"><h2>审核活动</h2><label>驳回原因<input v-model.trim="reviewNote" maxlength="500" placeholder="驳回时填写" /></label><div class="row-actions"><button class="button secondary" :disabled="busy || !reviewNote" @click="review(false)">驳回</button><button class="button primary" :disabled="busy" @click="review(true)">通过审核</button></div></section>
    <div class="admin-list-heading"><div><h2>报名与签到记录</h2><p>补签由该活动组织者处理</p></div><div class="row-actions"><button class="button secondary" :disabled="Boolean(exporting)" @click="exportRoster('registrations')"><Download :size="16" />{{ exporting === 'registrations' ? '生成中…' : '导出报名' }}</button><button class="button secondary" :disabled="Boolean(exporting)" @click="exportRoster('checkins')"><Download :size="16" />{{ exporting === 'checkins' ? '生成中…' : '导出签到' }}</button><label><span class="visually-hidden">筛选报名记录</span><select v-model="filter"><option value="ALL">全部记录</option><option value="ACTIVE">有效报名</option><option value="CHECKED">已签到</option><option value="WAITING">待签到</option><option value="CANCELLED">已取消</option></select></label></div></div>
    <div v-if="!filtered.length" class="empty-state compact-empty">暂无符合条件的报名记录</div>
    <div v-else class="record-list roster-list"><article v-for="row in filtered" :key="row.id" class="roster-item">
      <div class="record-avatar">{{ personInitial(row.studentName) }}</div><div class="roster-person"><strong>{{ row.studentName }}</strong><span>学号 {{ row.studentNumber || '待补录' }}</span><small v-if="row.studentEmail">{{ row.studentEmail }}</small></div>
      <div class="roster-attendance"><span class="status" :class="row.status === 'ACTIVE' ? 'published' : 'muted'">{{ row.status === 'ACTIVE' ? '有效报名' : '已取消' }}</span><span class="status" :class="row.checkedAt ? 'published' : 'muted'">{{ row.checkedAt ? row.checkinMethod === 'MANUAL' ? '人工补签' : '扫码签到' : '未签到' }}</span><small>报名 {{ dateTime(row.registeredAt) }}</small><small v-if="row.checkedAt">签到 {{ dateTime(row.checkedAt) }}</small><small v-if="row.checkinMethod === 'MANUAL'">操作人：{{ row.checkinOperatorName || '未记录' }} · 原因：{{ row.manualCheckinReason || '未记录' }}</small></div>
    </article></div>
    <section class="feedback-admin"><h2>活动评价</h2><p class="admin-scope-note">有效评价 {{ feedbackSummary.count }} 条 · 平均 {{ feedbackSummary.count ? feedbackSummary.averageRating.toFixed(1) : '—' }} 分。隐藏评价不计入统计。</p><p v-if="!feedbackItems.length" class="muted-text">暂无活动评价</p><article v-for="item in feedbackItems" :key="item.id" class="feedback-admin-row"><div><strong>{{ item.studentName }} · {{ item.rating }}/5</strong><span v-if="item.hiddenAt" class="status rejected">已隐藏</span><p>{{ item.comment }}</p><small>{{ dateTime(item.updatedAt) }}</small><small v-if="item.hiddenAt">处理原因：{{ item.hiddenReason }}</small></div><div v-if="!item.hiddenAt" class="feedback-moderation"><input v-model.trim="hideReasons[item.id]" maxlength="300" placeholder="填写隐藏原因" :aria-label="`隐藏${item.studentName}评价的原因`" /><button class="button secondary" :disabled="busy || !hideReasons[item.id]?.trim()" @click="hideFeedback(item)">隐藏评价</button></div></article></section>
    <section class="admin-audit"><h2>活动操作记录</h2><p class="admin-scope-note">新版本启用后，每次审核意见随操作保存；此前未记录的历史意见不能补造。</p><p v-if="!audit.length" class="muted-text">暂无操作记录</p><div v-for="(event, index) in audit" :key="index" class="admin-audit-row"><span>{{ auditLabels[event.action] || event.action }}<small v-if="event.detail" class="cell-note">{{ event.detail }}</small></span><strong>{{ event.actorName }}</strong><time>{{ dateTime(event.createdAt) }}</time></div></section>
    <section v-if="mutationAudits.length" class="admin-audit"><h2>发布后变更与取消记录</h2><div v-for="(event, index) in mutationAudits" :key="index" class="admin-audit-row"><span>{{ { CANCEL_EMERGENCY: '紧急取消', CANCEL_APPROVED: '审核确认取消', CHANGE_APPROVED: '审核通过变更', CHANGE_DIRECT: '管理员直接修改' }[event.action] || event.action }}<small class="cell-note">{{ event.reason }}</small></span><strong>{{ event.actorName }}</strong><time>{{ dateTime(event.createdAt) }}</time></div></section>
  </template>
</template>
