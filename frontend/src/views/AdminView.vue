<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ArrowRight, CalendarDays, CalendarPlus, ClipboardCheck, MapPin, QrCode, RefreshCw, ShieldCheck, TicketCheck, Unlink2, UsersRound } from '@lucide/vue'
import { api } from '../api'
import { activityPhase, phaseLabel } from '../activityPhase'
import { dateTime, personInitial } from '../format'
import { user } from '../session'
import { adminSectionForPath } from '../adminSections'

const route = useRoute()
const currentSection = computed(() => adminSectionForPath(route.path))
const section = computed(() => currentSection.value.key)
const overview = ref([])
const pending = ref([])
const pendingCancellations = ref([])
const pendingChanges = ref([])
const users = ref([])
const userQuery = ref('')
const usersPanel = ref('accounts')
const userRole = ref('STUDENT')
const userGroups = { STUDENT: '普通学生', ORGANIZER: '组织者', ADMIN: '管理员' }
const accountGroup = account => account.roles.includes('ADMIN') ? 'ADMIN' : account.roles.includes('ORGANIZER') ? 'ORGANIZER' : 'STUDENT'
const auditKind = ref('activity')
const noticeEditorOpen = ref(false)
const unbindTarget = ref(null)
const unbindReason = ref('')
const unbindReasonInput = ref(null)
const deleteTarget = ref(null)
const deleteImpact = ref(null)
const deleteImpactLoading = ref(false)
const deleteConfirmId = ref('')
const deleteError = ref('')
let deletePreviewVersion = 0
const appealsEnabled = ref(false)
const bindingAppeals = ref([])
const appealPage = ref({ total: 0, page: 0, size: 20 })
const appealQueue = ref('PENDING')
const appealStudentNumber = ref('')
const appealListError = ref('')
const appealListLoading = ref(false)
const appealEvidenceFailed = ref(false)
const appealStatusLabels = { PENDING: '待处理', APPROVED: '已通过', REJECTED: '已驳回', DISPUTED: '存在争议' }
const appealActivityStatusLabels = { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', CANCELLED: '已取消' }
let appealListVersion = 0
const appealTarget = ref(null)
const appealActionError = ref('')
const appealRejectReason = ref('')
const appealDisputeReason = ref('')
const appealVerifiedName = ref('')
const appealApproveReason = ref('')
const appealReplaceTargetBinding = ref(false)
const appealDecisions = ref({})
const appealDecisionReasons = ref({})
const appealReviewRows = ref([])
const appealImpact = ref(null)
const appealImpactError = ref('')
const appealTargetBoundElsewhere = computed(() => appealImpact.value?.accounts.some(
  account => account.studentId === appealImpact.value.targetStudentId && account.boundToOtherIdentity) || false)
const appealHasOrganizerPrivilege = computed(() => appealImpact.value?.accounts.some(account => account.organizer) || false)
const appealPriorNoteLength = computed(() => appealTarget.value?.status === 'DISPUTED'
  ? (appealTarget.value.reviewNote || '').length + '\n此前争议待核事项：'.length : 0)
const canApproveAppeal = computed(() => {
  const impact = appealImpact.value
  if (!impact || busy.value || appealEvidenceFailed.value || impact.requiresActivityOwnershipReview
    || appealHasOrganizerPrivilege.value
    || !appealVerifiedName.value.trim() || !appealApproveReason.value.trim()
    || appealApproveReason.value.trim().length + appealPriorNoteLength.value > 1000
    || appealTargetBoundElsewhere.value && !appealReplaceTargetBinding.value) return false
  return impact.rows.every(row => appealDecisions.value[row.registrationId]
    && appealDecisionReasons.value[row.registrationId]?.trim()
    && !(appealDecisions.value[row.registrationId] === 'TRANSFER'
      && (row.targetHasRegistration || row.sourceCheckinConflict)))
})
const announcements = ref([])
const audit = ref([])
const securityAudit = ref([])
const exportAudit = ref([])
const auditPage = ref({ total: 0, page: 0, size: 20 })
const securityAuditPage = ref({ total: 0, page: 0, size: 20 })
const exportAuditPage = ref({ total: 0, page: 0, size: 20 })
const exportAuditError = ref('')
const auditFilter = reactive({ actor: '', action: '', from: '', to: '' })
const securityAuditFilter = reactive({ actor: '', action: '', from: '', to: '' })
const exportAuditFilter = reactive({ activityId: '', actor: '', from: '', to: '' })
const participationFilter = reactive({ keyword: '', status: '', attendance: '', from: '', to: '' })
const participationPage = ref({ items: [], total: 0, page: 0, size: 20 })
const participationError = ref('')
const participationLegacy = ref(false)
const dailyMetrics = ref([])
const noticeTitle = ref('')
const noticeBody = ref('')
const editingNoticeId = ref(null)
const noticePreview = ref(false)
const noticeSearch = ref('')
const noticeStatus = ref('ALL')
const query = ref('')
const status = ref('ALL')
const phase = ref('ALL')
const dateFrom = ref('')
const dateTo = ref('')
const clock = ref(Date.now())
let clockTimer
const notes = ref({})
const busy = ref(false)
const initialLoading = ref(true)
const error = ref('')
const message = ref('')
let loadVersion = 0

async function load() {
  const version = ++loadVersion
  initialLoading.value = true
  error.value = ''
  try {
    const current = section.value
    if (current === 'overview') {
      const [activities, daily] = await Promise.all([api('/admin/activities'), api('/admin/metrics/daily?days=7')])
      if (version === loadVersion) { overview.value = activities; dailyMetrics.value = daily }
    } else if (['activities', 'checkins'].includes(current)) {
      const activities = await api('/admin/activities')
      if (version === loadVersion) overview.value = activities
    } else if (current === 'participation') {
      try {
        const result = await fetchParticipationPage(participationPage.value.page)
        if (version === loadVersion) {
          participationPage.value = result; participationError.value = ''; participationLegacy.value = false
        }
      } catch (cause) {
        if (cause.status !== 404) throw cause
        const activities = await api('/admin/activities')
        if (version === loadVersion) { overview.value = activities; participationLegacy.value = true }
      }
    } else if (current === 'reviews') {
      const [activities, cancellations, changes] = await Promise.all([
        api('/admin/activities/pending'),
        api('/admin/activity-cancellations/pending').catch(cause => { if (cause.status === 404) return []; throw cause }),
        api('/admin/activity-changes/pending').catch(cause => { if (cause.status === 404) return []; throw cause }),
      ])
      if (version === loadVersion) { pending.value = activities; pendingCancellations.value = cancellations; pendingChanges.value = changes }
    } else if (current === 'users') {
      const accounts = await api('/admin/users')
      if (version === loadVersion) users.value = accounts
      try {
        const availability = await api('/admin/binding-appeals/availability').catch(cause => {
          if (cause.status === 404) return { enabled: false }
          throw cause
        })
        const appeals = availability.enabled ? await fetchAppealsPage(0) : { items: [], total: 0, page: 0, size: 20 }
        if (version === loadVersion) {
          users.value = accounts
          appealsEnabled.value = availability.enabled
          bindingAppeals.value = appeals.items
          appealPage.value = appeals
          appealListError.value = ''
        }
        if (version === loadVersion && /^[1-9][0-9]*$/.test(String(route.query.appeal || ''))) {
          try { await openAppeal(await api(`/admin/binding-appeals/${route.query.appeal}`)) }
          catch (cause) { appealListError.value = `通知对应申诉无法打开：${cause.message}` }
        }
      } catch (cause) {
        if (version === loadVersion) {
          appealsEnabled.value = true
          bindingAppeals.value = []
          appealListError.value = `申诉服务加载失败：${cause.message}`
        }
      }
    } else if (current === 'announcements') {
      const notices = await api('/admin/announcements')
      if (version === loadVersion) announcements.value = notices
    } else if (current === 'audit') {
      const [activityResult, userResult, exportResult] = await Promise.all([
        fetchAuditPage('activity', auditPage.value.page), fetchAuditPage('user', securityAuditPage.value.page),
        fetchExportAuditPage(exportAuditPage.value.page).catch(cause => {
          if (cause.status === 404) return null
          throw cause
        }),
      ])
      if (version === loadVersion) {
        audit.value = activityResult.items; auditPage.value = activityResult
        securityAudit.value = userResult.items; securityAuditPage.value = userResult
        exportAudit.value = exportResult?.items || []
        exportAuditPage.value = exportResult || { total: 0, page: 0, size: 20 }
        exportAuditError.value = exportResult ? '' : '当前服务尚不支持导出审计查询'
      }
    }
  } catch (cause) { if (version === loadVersion) error.value = cause.message }
  finally { if (version === loadVersion) initialLoading.value = false }
}

function fetchAppealsPage(page) {
  const params = new URLSearchParams({ queue: appealQueue.value, studentNumber: appealStudentNumber.value.trim(), page: String(page), size: '20' })
  return api(`/admin/binding-appeals?${params}`)
}

async function searchAppeals(page = 0) {
  const version = ++appealListVersion
  appealListLoading.value = true
  appealListError.value = ''
  try {
    const result = await fetchAppealsPage(page)
    if (version === appealListVersion) { bindingAppeals.value = result.items; appealPage.value = result }
  } catch (cause) { if (version === appealListVersion) appealListError.value = cause.message }
  finally { if (version === appealListVersion) appealListLoading.value = false }
}

function changeAppealQueue(queue) {
  appealQueue.value = queue
  searchAppeals()
}

function fetchAuditPage(kind, page) {
  const filter = kind === 'activity' ? auditFilter : securityAuditFilter
  const params = new URLSearchParams({ page: String(page), size: '20' })
  for (const key of ['actor', 'action', 'from', 'to']) {
    if (filter[key]?.trim()) params.set(key, filter[key].trim())
  }
  return api(`/admin/${kind === 'activity' ? 'activity-audits' : 'user-audits'}/search?${params}`)
}

function fetchExportAuditPage(page) {
  const params = new URLSearchParams({ page: String(page), size: '20' })
  for (const key of ['activityId', 'actor', 'from', 'to']) {
    const value = String(exportAuditFilter[key] ?? '').trim()
    if (value) params.set(key, value)
  }
  return api(`/admin/participation-export-audits?${params}`)
}

async function searchExportAudits(page = 0) {
  try {
    const result = await fetchExportAuditPage(page)
    exportAudit.value = result.items
    exportAuditPage.value = result
    exportAuditError.value = ''
  } catch (cause) { exportAuditError.value = cause.message }
}

function fetchParticipationPage(page) {
  const params = new URLSearchParams({ page: String(page), size: '20' })
  for (const key of ['keyword', 'status', 'attendance', 'from', 'to']) {
    if (participationFilter[key]?.trim()) params.set(key, participationFilter[key].trim())
  }
  return api(`/admin/participation?${params}`)
}

async function searchParticipation(page = 0) {
  try {
    participationPage.value = await fetchParticipationPage(page)
    participationError.value = ''
  } catch (cause) { participationError.value = cause.message }
}

async function searchAudits(kind, page = 0) {
  try {
    const result = await fetchAuditPage(kind, page)
    if (kind === 'activity') { audit.value = result.items; auditPage.value = result }
    else { securityAudit.value = result.items; securityAuditPage.value = result }
    error.value = ''
  } catch (cause) { error.value = cause.message }
}

const filtered = computed(() => overview.value.filter(({ activity }) => {
  const day = new Date(activity.startsAt).toLocaleDateString('sv-SE')
  return (status.value === 'ALL' || activity.status === status.value)
    && (phase.value === 'ALL' || activityPhase(activity, clock.value) === phase.value)
    && (!dateFrom.value || day >= dateFrom.value)
    && (!dateTo.value || day <= dateTo.value)
    && `${activity.title} ${activity.organizerName} ${activity.location}`.toLowerCase()
      .includes(query.value.trim().toLowerCase())
}))
const matchedUsers = computed(() => users.value.filter(account =>
  `${account.name} ${account.studentNumber || ''} ${account.email || ''}`.toLowerCase()
    .includes(userQuery.value.trim().toLowerCase())))
const filteredUsers = computed(() => matchedUsers.value.filter(account => accountGroup(account) === userRole.value))
const filteredNotices = computed(() => announcements.value.filter(notice =>
  (noticeStatus.value === 'ALL' || (noticeStatus.value === 'ACTIVE') === notice.active)
  && notice.title.toLowerCase().includes(noticeSearch.value.trim().toLowerCase())))
const totalRegistered = computed(() => overview.value.reduce((sum, { stats }) => sum + stats.registeredCount, 0))
const totalChecked = computed(() => overview.value.reduce((sum, { stats }) => sum + stats.checkedCount, 0))
const dailyMaximum = computed(() => Math.max(1, ...dailyMetrics.value.flatMap(row => [row.registered, row.checked])))
const barHeight = value => `${Math.max(value ? 6 : 0, value / dailyMaximum.value * 100)}%`
const labels = { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', CANCELLED: '已取消' }
const pendingCount = computed(() => overview.value.filter(({ activity }) => activity.status === 'PENDING').length)
const welcomePeriod = computed(() => {
  const hour = new Date(clock.value).getHours()
  return hour < 11 ? '早上好' : hour < 18 ? '下午好' : '晚上好'
})
const welcomeDate = computed(() => {
  const date = new Date(clock.value)
  const day = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(date)
  const weekday = new Intl.DateTimeFormat('zh-CN', { weekday: 'long' }).format(date)
  return `${day} · ${weekday}`
})
const phaseCount = (value) => overview.value.filter(({ activity }) => activityPhase(activity, clock.value) === value).length
const nextActivity = computed(() => overview.value
  .filter(({ activity }) => activity.status === 'PUBLISHED' && Date.parse(activity.startsAt) > clock.value)
  .sort((a, b) => Date.parse(a.activity.startsAt) - Date.parse(b.activity.startsAt))[0]?.activity)
const statusCounts = computed(() => Object.entries(labels).map(([key, label]) => ({
  key, label, count: overview.value.filter(({ activity }) => activity.status === key).length,
  percent: overview.value.length ? Math.round(100 * overview.value.filter(({ activity }) => activity.status === key).length / overview.value.length) : 0,
})))
const participationRows = computed(() => overview.value.filter(({ activity }) => activity.status === 'PUBLISHED')
  .sort((a, b) => b.stats.registeredCount - a.stats.registeredCount).slice(0, 6)
  .map(({ activity, stats }) => ({ id: activity.id, title: activity.title,
    registered: stats.registeredCount, checked: stats.checkedCount,
    registrationPercent: stats.capacity ? Math.min(100, 100 * stats.registeredCount / stats.capacity) : 0,
    checkinPercent: stats.capacity ? Math.min(100, 100 * stats.checkedCount / stats.capacity) : 0,
  })))
const auditLabels = { CREATED: '创建活动', EDITED: '编辑活动', DIRECT_PUBLISHED: '直接发布', REVIEW_APPROVED: '审核通过', REVIEW_REJECTED: '审核驳回', QR_ISSUED: '生成现场二维码', FEEDBACK_HIDDEN: '隐藏活动评价' }
const canCheckin = (activity) => activity.status === 'PUBLISHED'
  && clock.value >= Date.parse(activity.checkinOpensAt)
  && clock.value <= Date.parse(activity.checkinClosesAt)

function fallbackCover(event) {
  if (!event.target.src.endsWith('/images/campus-gathering.png')) event.target.src = '/images/campus-gathering.png'
}

watch(() => route.fullPath, () => {
  const pick = (key, allowed, fallback) => allowed.includes(route.query[key]) ? route.query[key] : fallback
  if (section.value === 'activities') {
    status.value = pick('status', ['DRAFT', 'PENDING', 'PUBLISHED', 'REJECTED', 'CANCELLED'], 'ALL')
    phase.value = pick('phase', ['UPCOMING', 'ONGOING', 'ENDED'], 'ALL')
    query.value = ''; dateFrom.value = ''; dateTo.value = ''
  }
  if (section.value === 'participation') {
    Object.assign(participationFilter, {
      keyword: '', from: '', to: '',
      status: pick('status', ['ACTIVE', 'CANCELLED'], ''),
      attendance: pick('attendance', ['CHECKED', 'NOT_CHECKED'], ''),
    })
    participationPage.value.page = 0
  }
  load()
}, { immediate: true })

async function publish(activity) {
  if (!window.confirm(`确认直接发布“${activity.title}”？发布后学生即可看到这场活动。`)) return
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/activities/${activity.id}/publish`, { method: 'POST' })
    message.value = '活动已发布'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function review(activity, approved) {
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/activities/${activity.id}/review`, { method: 'POST', body: {
      approved, note: approved ? '' : notes.value[activity.id],
    } })
    message.value = approved ? '活动已通过审核' : '活动已驳回'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function changeRole(account, enabled) {
  if (!window.confirm(`确认${enabled ? '授予' : '撤销'}“${account.name}”的组织者权限？`)) {
    await load()
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/users/${account.id}/organizer`, { method: 'PATCH', body: { enabled } })
    message.value = enabled ? '已授予组织者权限' : '已撤销组织者权限'
    await load()
  } catch (cause) { error.value = cause.message; await load() }
  finally { busy.value = false }
}
async function openUnbind(account) {
  if (busy.value || !account.wechatBound || account.bindingManagementEnabled !== true
    || !account.roles.includes('STUDENT') || account.roles.includes('ADMIN')) return
  unbindTarget.value = account
  unbindReason.value = ''
  await nextTick()
  unbindReasonInput.value?.focus()
}

async function openDelete(account) {
  if (busy.value || account.roles.includes('ADMIN')) return
  const version = ++deletePreviewVersion
  deleteTarget.value = account; deleteImpact.value = null; deleteConfirmId.value = ''; deleteError.value = ''
  deleteImpactLoading.value = true
  try {
    const impact = await api(`/admin/users/${account.id}/deletion-impact`)
    if (version === deletePreviewVersion) deleteImpact.value = impact
  } catch (cause) { if (version === deletePreviewVersion) deleteError.value = cause.message }
  finally { if (version === deletePreviewVersion) deleteImpactLoading.value = false }
}
function closeDelete() {
  if (busy.value) return
  ++deletePreviewVersion
  deleteTarget.value = null; deleteImpact.value = null; deleteConfirmId.value = ''; deleteError.value = ''
}
async function confirmDelete() {
  const target = deleteTarget.value
  if (busy.value || !target || !deleteImpact.value || deleteConfirmId.value.trim() !== String(target.id)) return
  busy.value = true; deleteError.value = ''
  try {
    await api(`/admin/users/${target.id}`, { method: 'DELETE', body: { confirmUserId: target.id } })
    deleteTarget.value = null; deleteImpact.value = null
    message.value = `用户 #${target.id} 已永久删除，历史记录已匿名保留`
    await load()
  } catch (cause) { deleteError.value = cause.message }
  finally { busy.value = false }
}
function closeUnbind() {
  if (busy.value) return
  unbindTarget.value = null
  unbindReason.value = ''
}
async function openAppeal(appeal) {
  usersPanel.value = 'appeals'
  appealTarget.value = appeal
  appealActionError.value = ''
  appealRejectReason.value = ''
  appealDisputeReason.value = ''
  appealVerifiedName.value = ''
  appealApproveReason.value = ''
  appealReplaceTargetBinding.value = false
  appealDecisions.value = {}
  appealDecisionReasons.value = {}
  appealReviewRows.value = []
  appealImpact.value = null
  appealImpactError.value = ''
  appealEvidenceFailed.value = false
  if (appeal.status !== 'PENDING' && appeal.status !== 'DISPUTED') {
    if (appeal.status === 'APPROVED') {
      try { appealReviewRows.value = await api(`/admin/binding-appeals/${appeal.id}/attribution-reviews`) }
      catch (cause) { appealImpactError.value = cause.message }
    }
    return
  }
  try {
    const impact = await api(`/admin/binding-appeals/${appeal.id}/impact`)
    if (appealTarget.value?.id === appeal.id) {
      appealImpact.value = impact
      appealVerifiedName.value = impact.accounts.find(account => account.studentId === impact.targetStudentId)?.name || ''
    }
  } catch (cause) { if (appealTarget.value?.id === appeal.id) appealImpactError.value = cause.message }
}
function closeAppeal() {
  if (busy.value) return
  appealTarget.value = null
  appealActionError.value = ''
  appealRejectReason.value = ''
  appealDisputeReason.value = ''
  appealImpact.value = null
  appealReviewRows.value = []
}
async function approveAppeal() {
  if (!appealTarget.value || !canApproveAppeal.value) return
  const rows = appealImpact.value.rows
  const transferCount = rows.filter(row => appealDecisions.value[row.registrationId] === 'TRANSFER').length
  if (!window.confirm(`确认批准换绑？将转移 ${transferCount} 条参与记录、保留 ${rows.length - transferCount} 条；原登录凭据立即失效。`)) return
  busy.value = true
  appealActionError.value = ''
  try {
    await api(`/admin/binding-appeals/${appealTarget.value.id}/approve`, {
      method: 'POST', body: {
        verifiedName: appealVerifiedName.value.trim(), reason: appealApproveReason.value.trim(),
        replaceTargetBinding: appealReplaceTargetBinding.value,
        decisions: rows.map(row => ({ registrationId: row.registrationId,
          decision: appealDecisions.value[row.registrationId], reason: appealDecisionReasons.value[row.registrationId].trim() })),
      },
    })
    appealTarget.value = null
    message.value = '申诉已批准，微信绑定和参与记录已按逐条审核结果处理'
    await load()
  } catch (cause) { appealActionError.value = cause.message }
  finally { busy.value = false }
}
async function rejectAppeal() {
  if (!appealTarget.value || !appealRejectReason.value.trim() || busy.value) return
  busy.value = true
  appealActionError.value = ''
  try {
    await api(`/admin/binding-appeals/${appealTarget.value.id}/reject`, {
      method: 'POST', body: { reason: appealRejectReason.value.trim() },
    })
    appealTarget.value = null
    appealRejectReason.value = ''
    message.value = '申诉已驳回'
    await load()
  } catch (cause) { appealActionError.value = cause.message }
  finally { busy.value = false }
}
async function disputeAppeal() {
  if (!appealTarget.value || appealTarget.value.status !== 'PENDING' || !appealDisputeReason.value.trim() || busy.value) return
  busy.value = true
  appealActionError.value = ''
  try {
    await api(`/admin/binding-appeals/${appealTarget.value.id}/dispute`, {
      method: 'POST', body: { reason: appealDisputeReason.value.trim() },
    })
    appealTarget.value = null
    appealDisputeReason.value = ''
    message.value = '申诉已转入争议待核，现有绑定和参与记录未变更'
    await load()
  } catch (cause) { appealActionError.value = cause.message }
  finally { busy.value = false }
}
async function confirmUnbind() {
  const account = unbindTarget.value
  const reason = unbindReason.value.trim()
  if (!account || !reason || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/users/${account.id}/wechat-binding`, { method: 'PATCH', body: { reason } })
    unbindTarget.value = null
    unbindReason.value = ''
    message.value = `已解除“${account.name}”的微信绑定`
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
async function publishNotice() {
  busy.value = true
  error.value = ''
  try {
    await api(editingNoticeId.value ? `/admin/announcements/${editingNoticeId.value}` : '/admin/announcements', {
      method: editingNoticeId.value ? 'PUT' : 'POST', body: {
      title: noticeTitle.value.trim(), body: noticeBody.value.trim(),
    } })
    const edited = Boolean(editingNoticeId.value)
    editingNoticeId.value = null
    noticePreview.value = false
    noticeTitle.value = ''
    noticeBody.value = ''
    noticeEditorOpen.value = false
    message.value = edited ? '公告已保存' : '公告已发布'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
function editNotice(notice) {
  error.value = ''; noticeEditorOpen.value = true
  editingNoticeId.value = notice.id
  noticeTitle.value = notice.title
  noticeBody.value = notice.body
  noticePreview.value = false
}
function cancelNoticeEdit() {
  noticeEditorOpen.value = false
  editingNoticeId.value = null
  noticeTitle.value = ''
  noticeBody.value = ''
  noticePreview.value = false
}
async function withdrawNotice(id) {
  if (!window.confirm('确认撤回这条公告？撤回后学生端将不再展示。')) return
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/announcements/${id}`, { method: 'DELETE' })
    message.value = '公告已撤回'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
async function republishNotice(id) {
  if (!window.confirm('确认重新发布这条公告？学生端将再次看到。')) return
  busy.value = true
  error.value = ''
  try {
    await api(`/admin/announcements/${id}/publish`, { method: 'POST' })
    message.value = '公告已重新发布'
    await load()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
onMounted(() => { clockTimer = setInterval(() => { clock.value = Date.now() }, 15000) })
onUnmounted(() => clearInterval(clockTimer))
</script>

<template>
  <section class="page-heading"><div><p class="eyebrow">校园活动 / {{ currentSection.label }}</p><h1>{{ section === 'overview' ? '活动运营驾驶舱' : currentSection.label }}</h1><p>{{ currentSection.description }}</p></div><div class="row-actions"><button class="button secondary" @click="load"><RefreshCw :size="16" />刷新</button><RouterLink v-if="section === 'overview' || section === 'activities'" class="button primary" to="/admin/activities/new"><CalendarPlus :size="16" />新建活动</RouterLink></div></section>
  <div v-if="initialLoading" class="admin-loading" role="status">正在加载{{ currentSection.label }}…</div>
  <p v-if="error" class="message error">{{ error }}</p><p v-if="message" class="message success">{{ message }}</p>
  <div v-if="initialLoading" class="admin-loading-content" aria-hidden="true"><div></div><div></div><div></div></div>
  <template v-if="!initialLoading && !error">
  <template v-if="section === 'overview'">
    <div class="dashboard-overview">
    <section class="dashboard-welcome" aria-label="今日运营概况">
      <div class="dashboard-welcome-copy"><span class="dashboard-welcome-date">今日运营 · {{ welcomeDate }}</span><h2>{{ welcomePeriod }}，{{ user?.name || '管理员' }}</h2><p v-if="pendingCount">有 {{ pendingCount }} 场活动等待审核，及时处理让精彩如期发生。</p><p v-else>待审核事项已清空，继续关注活动报名与到场情况。</p></div>
      <img src="/images/dashboard-campus-panorama.png" alt="" />
    </section>
    <div class="dashboard-metrics" aria-label="平台关键数据">
      <RouterLink class="dashboard-metric dashboard-link" to="/admin/activities" aria-label="查看活动总数"><span class="dashboard-metric-icon teal"><CalendarDays :size="22" /></span><span>活动总数</span><strong>{{ overview.length }}</strong><small>全平台活动</small></RouterLink>
      <RouterLink class="dashboard-metric dashboard-link" to="/admin/participation?status=ACTIVE" aria-label="查看有效报名"><span class="dashboard-metric-icon blue"><UsersRound :size="22" /></span><span>有效报名</span><strong>{{ totalRegistered }}</strong><small>当前有效记录</small></RouterLink>
      <RouterLink class="dashboard-metric dashboard-link" to="/admin/participation?attendance=CHECKED" aria-label="查看已签到"><span class="dashboard-metric-icon mint"><TicketCheck :size="22" /></span><span>已签到</span><strong>{{ totalChecked }}</strong><small>实际到场记录</small></RouterLink>
      <RouterLink class="dashboard-metric dashboard-link" to="/admin/reviews" aria-label="查看待审核"><span class="dashboard-metric-icon orange"><ClipboardCheck :size="22" /></span><span>待审核</span><strong>{{ pendingCount }}</strong><small>待处理活动</small></RouterLink>
    </div>
    <div class="dashboard-grid dashboard-grid-primary">
      <RouterLink to="/admin/participation" class="dashboard-panel admin-trend dashboard-link" aria-label="查看近七日报名与签到的参与记录"><div class="admin-trend-heading"><div><p class="dashboard-kicker">参与趋势</p><h2>近七日报名与签到</h2><p>按北京时间统计有效报名与实际签到，取消报名不计入。</p></div><div class="admin-trend-legend"><span><i class="registered" />报名</span><span><i class="checked" />签到</span></div></div><div class="admin-trend-plot"><div v-for="row in dailyMetrics" :key="row.day" class="admin-trend-day"><div class="admin-trend-bars"><div class="registered" :style="{ height: barHeight(row.registered) }" :title="`${row.day} 报名 ${row.registered}`" /><div class="checked" :style="{ height: barHeight(row.checked) }" :title="`${row.day} 签到 ${row.checked}`" /></div><span>{{ row.day.slice(5) }}</span><small>{{ row.registered }} / {{ row.checked }}</small></div></div></RouterLink>
      <section class="dashboard-panel dashboard-attention"><p class="dashboard-kicker">运营待办</p><h2>当前需要关注</h2><RouterLink to="/admin/reviews" class="dashboard-attention-main"><span>待审核活动</span><strong>{{ pendingCount }}</strong><ArrowRight :size="18" /></RouterLink><div class="dashboard-phase"><RouterLink v-for="item in ['UPCOMING', 'ONGOING', 'ENDED']" :key="item" :to="{ path: '/admin/activities', query: { phase: item } }" class="dashboard-phase-link" :aria-label="`查看${phaseLabel[item]}活动`">{{ phaseLabel[item] }}<strong>{{ phaseCount(item) }}</strong></RouterLink></div><div class="dashboard-next"><span>下一场已发布活动</span><RouterLink v-if="nextActivity" :to="`/admin/activities/${nextActivity.id}`"><strong>{{ nextActivity.title }}</strong><small>{{ dateTime(nextActivity.startsAt) }}</small></RouterLink><p v-else>暂无即将开始的活动</p></div></section>
    </div>
    <div class="dashboard-grid dashboard-grid-secondary"><section class="dashboard-panel admin-insight"><p class="dashboard-kicker">活动概况</p><h2>发布状态</h2><p>全平台 {{ overview.length }} 场活动</p><RouterLink v-for="row in statusCounts" :key="row.key" :to="{ path: '/admin/activities', query: { status: row.key } }" class="insight-row dashboard-link" :aria-label="`查看${row.label}活动`"><span>{{ row.label }}</span><div class="insight-track"><i :style="{ width: `${row.percent}%` }" /></div><strong>{{ row.count }}</strong></RouterLink></section><section class="dashboard-panel admin-insight"><p class="dashboard-kicker">参与表现</p><h2>热门活动</h2><p>已发布活动，按有效报名人数排序</p><div v-if="!participationRows.length" class="muted-text">暂无已发布活动</div><RouterLink v-for="row in participationRows" :key="row.id" :to="`/admin/activities/${row.id}`" class="participation-row dashboard-link"><strong>#{{ row.id }} {{ row.title }}</strong><span>{{ row.registered }} 报名 · {{ row.checked }} 签到</span><div class="participation-track"><i :style="{ width: `${row.registrationPercent}%` }" /><b :style="{ width: `${row.checkinPercent}%` }" /></div></RouterLink></section></div>
    <div class="admin-quick-links"><RouterLink to="/admin/activities">全部活动<ArrowRight :size="16" /></RouterLink><RouterLink to="/admin/participation">参与记录<ArrowRight :size="16" /></RouterLink><RouterLink to="/admin/audit">操作审计<ArrowRight :size="16" /></RouterLink></div>
    </div>
  </template>
  <div v-else-if="section === 'activities' && !error">
    <div class="admin-filters"><label class="search-field"><input v-model="query" placeholder="搜索活动、组织者或地点" aria-label="搜索活动、组织者或地点" /></label><label><span class="visually-hidden">筛选审核状态</span><select v-model="status" aria-label="筛选审核状态"><option value="ALL">全部审核状态</option><option value="DRAFT">草稿</option><option value="PENDING">待审核</option><option value="PUBLISHED">已发布</option><option value="REJECTED">已驳回</option><option value="CANCELLED">已取消</option></select></label><label><span class="visually-hidden">筛选时间阶段</span><select v-model="phase" aria-label="筛选时间阶段"><option value="ALL">全部时间阶段</option><option value="UPCOMING">未开始</option><option value="ONGOING">进行中</option><option value="ENDED">已结束</option></select></label><label class="admin-date-field">开始<input v-model="dateFrom" type="date" aria-label="开始日期" /></label><span class="admin-date-separator">至</span><label class="admin-date-field">结束<input v-model="dateTo" type="date" aria-label="结束日期" /></label></div>
    <p class="admin-scope-note admin-results-count">当前列表 <strong>{{ filtered.length }}</strong> 场活动</p>
    <div v-if="!filtered.length" class="empty-state">暂无符合条件的活动</div>
    <div v-else class="admin-event-list">
      <article v-for="item in filtered" :key="item.activity.id" class="admin-event" :class="`event-${item.activity.status.toLowerCase()}`">
        <img class="admin-event-cover" :src="item.activity.coverImageUrl || '/images/campus-gathering.png'" :alt="`${item.activity.title}封面`" @error="fallbackCover" />
        <div class="admin-event-body">
          <div class="admin-event-top">
            <div class="admin-event-main"><div class="admin-event-heading"><h2>{{ item.activity.title }}</h2><span class="status" :class="item.activity.status.toLowerCase()">{{ labels[item.activity.status] }}</span><span class="admin-event-phase">{{ phaseLabel[activityPhase(item.activity, clock)] }}</span></div><p>{{ dateTime(item.activity.startsAt) }} · {{ item.activity.organizerName }}</p><p class="admin-event-location"><MapPin :size="14" />{{ item.activity.location }}</p><p v-if="item.activity.articleSummary" class="admin-event-summary">{{ item.activity.articleSummary }}</p></div>
            <div class="admin-event-numbers"><div><strong>{{ item.stats.registeredCount }}<small> / {{ item.stats.capacity }}</small></strong><span>报名 / 名额</span></div><div><strong>{{ item.stats.checkedCount }}</strong><span>已签到</span></div></div>
          </div>
          <div class="admin-event-actions"><RouterLink class="text-button" :to="`/admin/activities/${item.activity.id}`">查看详情<ArrowRight :size="15" /></RouterLink><RouterLink v-if="['DRAFT', 'REJECTED'].includes(item.activity.status) && item.activity.organizerId === user?.id" class="text-button" :to="`/admin/activities/${item.activity.id}/edit`">编辑</RouterLink><button v-if="['DRAFT', 'REJECTED', 'PENDING'].includes(item.activity.status) && item.activity.organizerId === user?.id" class="text-button" :disabled="busy" @click="publish(item.activity)">直接发布</button><RouterLink v-if="item.activity.status === 'PENDING' && item.activity.organizerId !== user?.id" class="text-button" to="/admin/reviews">审核</RouterLink><RouterLink v-if="item.activity.status === 'PUBLISHED' && canCheckin(item.activity)" class="text-button" :to="`/admin/activities/${item.activity.id}/checkin`" title="展示现场二维码"><QrCode :size="15" />现场二维码</RouterLink></div>
        </div>
      </article>
    </div>
  </div>
  <section v-else-if="section === 'checkins' && !error" class="checkin-board">
    <div class="record-section-heading"><div><span class="record-overline">现场管理</span><h2>签到场次</h2></div><span class="record-total">{{ overview.filter(item => item.activity.status === 'PUBLISHED').length }} 场已发布活动</span></div>
    <p class="admin-scope-note">仅签到窗口内可展示现场二维码；二维码仍按 90 秒规则更新。</p>
    <div v-if="!overview.some(item => item.activity.status === 'PUBLISHED')" class="empty-state">暂无已发布活动</div>
    <div v-else class="checkin-list"><article v-for="item in overview.filter(row => row.activity.status === 'PUBLISHED')" :key="item.activity.id" class="checkin-session" :class="{ live: canCheckin(item.activity) }">
      <div class="checkin-session-main"><span class="record-overline">活动 #{{ item.activity.id }}</span><h3>{{ item.activity.title }}</h3><p><CalendarDays :size="15" />{{ dateTime(item.activity.checkinOpensAt) }} 至 {{ dateTime(item.activity.checkinClosesAt) }}</p><p><MapPin :size="15" />{{ item.activity.location }}</p></div>
      <div class="checkin-session-progress"><div><span>签到进度</span><strong>{{ item.stats.checkedCount }}<small> / {{ item.stats.registeredCount }}</small></strong></div><div class="record-progress"><i :style="{ width: `${item.stats.registeredCount ? Math.min(100, item.stats.checkedCount / item.stats.registeredCount * 100) : 0}%` }" /></div><small>{{ canCheckin(item.activity) ? '签到窗口开放中' : '当前非签到时间' }}</small></div>
      <RouterLink v-if="canCheckin(item.activity)" class="button primary" :to="`/admin/activities/${item.activity.id}/checkin`"><QrCode :size="16" />展示二维码</RouterLink><RouterLink v-else class="button secondary" :to="`/admin/activities/${item.activity.id}`">查看活动<ArrowRight :size="16" /></RouterLink>
    </article></div>
  </section>
  <section v-else-if="section === 'participation' && !error" class="admin-participation">
    <template v-if="participationLegacy"><p class="admin-scope-note">当前服务尚未更新跨活动检索，暂按活动查看报名与签到明细。</p><div v-if="!overview.some(item => item.stats.registeredCount || item.stats.checkedCount)" class="empty-state">暂无参与记录</div><div v-else class="admin-operations-list"><article v-for="item in overview.filter(row => row.stats.registeredCount || row.stats.checkedCount)" :key="item.activity.id" class="admin-operations-row"><div><strong>{{ item.activity.title }}</strong><p>{{ dateTime(item.activity.startsAt) }} · {{ item.activity.location }}</p></div><span>{{ item.stats.registeredCount }} 报名 · {{ item.stats.checkedCount }} 签到</span><RouterLink class="button secondary" :to="`/admin/activities/${item.activity.id}`">查看名单<ArrowRight :size="16" /></RouterLink></article></div></template>
    <template v-else>
    <p class="admin-scope-note">共 {{ participationPage.total }} 条报名记录。学号显示账号当前值，不作为争议历史归属证明；单场名单可在活动详情导出。</p>
    <form class="audit-filters" @submit.prevent="searchParticipation()"><input v-model.trim="participationFilter.keyword" maxlength="80" placeholder="活动、姓名、学号或邮箱" aria-label="搜索参与记录" /><select v-model="participationFilter.status" aria-label="报名状态"><option value="">全部报名状态</option><option value="ACTIVE">有效报名</option><option value="CANCELLED">已取消</option></select><select v-model="participationFilter.attendance" aria-label="签到状态"><option value="">全部签到状态</option><option value="CHECKED">已签到</option><option value="NOT_CHECKED">未签到</option></select><input v-model="participationFilter.from" type="date" aria-label="报名起始日期" /><input v-model="participationFilter.to" type="date" aria-label="报名结束日期" /><button class="button secondary" type="submit">筛选</button></form>
    <p v-if="participationError" class="message error">{{ participationError }}</p>
    <div v-if="!participationPage.items.length && !participationError" class="empty-state">暂无符合条件的参与记录</div>
    <div v-else-if="!participationError && participationPage.items.length" class="record-list participation-list"><article v-for="row in participationPage.items" :key="row.id" class="participation-item">
      <div class="record-avatar">{{ personInitial(row.studentName) }}</div>
      <div class="participation-person"><strong>{{ row.studentName }}</strong><span>学号 {{ row.studentNumber || '待补录' }}</span></div>
      <div class="participation-activity"><span class="record-overline">参与活动</span><RouterLink :to="`/admin/activities/${row.activityId}`">{{ row.activityTitle }}<ArrowRight :size="15" /></RouterLink><small>报名 {{ dateTime(row.registeredAt) }}</small></div>
      <div class="participation-state"><span class="status" :class="row.status === 'ACTIVE' ? 'published' : 'muted'">{{ row.status === 'ACTIVE' ? '有效报名' : '已取消' }}</span><small>{{ row.checkedAt ? `签到 ${dateTime(row.checkedAt)}` : '未签到' }}</small></div>
    </article></div>
    <div v-if="!participationError && participationPage.total > 0" class="audit-pagination"><button class="button secondary" :disabled="participationPage.page === 0" @click="searchParticipation(participationPage.page - 1)">上一页</button><span>第 {{ participationPage.page + 1 }} 页</span><button class="button secondary" :disabled="(participationPage.page + 1) * participationPage.size >= participationPage.total" @click="searchParticipation(participationPage.page + 1)">下一页</button></div>
    </template>
  </section>
  <div v-else-if="section === 'reviews'"><div v-if="!pending.length && !pendingCancellations.length && !pendingChanges.length" class="review-empty"><ClipboardCheck :size="28" /><div><strong>当前审核队列已清空</strong><p>活动、变更与取消申请均无待处理事项。</p></div><RouterLink class="text-button" to="/admin/activities">查看活动<ArrowRight :size="16" /></RouterLink></div><div v-else class="review-list"><article v-for="activity in pending" :key="activity.id" class="review-item"><div class="review-main"><div><h2>{{ activity.title }}</h2><p>{{ activity.organizerName }} · {{ dateTime(activity.startsAt) }} · {{ activity.location }}</p></div><span class="status pending">待审核</span></div><img v-if="activity.coverImageUrl" class="review-cover" :src="activity.coverImageUrl" alt="活动封面" /><p v-if="activity.articleSummary" class="review-summary">{{ activity.articleSummary }}</p><p class="review-description">{{ activity.description }}</p><div class="row-actions"><RouterLink class="text-button" :to="`/admin/activities/${activity.id}`">查看推文与地图<ArrowRight :size="15" /></RouterLink></div><div v-if="activity.organizerId === user?.id" class="review-actions"><button class="button primary" :disabled="busy" @click="publish(activity)">直接发布</button></div><div v-else class="review-actions"><label>驳回原因<input v-model.trim="notes[activity.id]" maxlength="500" placeholder="驳回时填写" /></label><button class="button secondary" :disabled="busy" @click="review(activity, false)">驳回</button><button class="button primary" :disabled="busy" @click="review(activity, true)">通过审核</button></div></article></div><section v-if="pendingCancellations.length" class="review-list"><h2>待处理取消申请</h2><article v-for="request in pendingCancellations" :key="request.id" class="review-item"><div class="review-main"><div><h2>{{ request.activityTitle }}</h2><p>{{ dateTime(request.createdAt) }} · 组织者申请取消</p></div><span class="status pending">待确认</span></div><p class="review-description">{{ request.reason }}</p><RouterLink class="text-button" :to="`/admin/activities/${request.activityId}`">查看并处理<ArrowRight :size="15" /></RouterLink></article></section><section v-if="pendingChanges.length" class="review-list"><h2>待审核活动变更</h2><article v-for="request in pendingChanges" :key="request.id" class="review-item"><div class="review-main"><div><h2>{{ request.activityTitle }}</h2><p>{{ dateTime(request.createdAt) }} · 修改原因：{{ request.reason }}</p></div><span class="status pending">待审核</span></div><RouterLink class="text-button" :to="`/admin/activities/${request.activityId}`">对照并审核<ArrowRight :size="15" /></RouterLink></article></section></div>
  <section v-else-if="section === 'announcements'" class="management-panel">
    <div class="management-toolbar"><div><h2>公告列表</h2><span class="muted-text">共 {{ filteredNotices.length }} 条</span></div><button class="button primary" @click="cancelNoticeEdit(); error = ''; noticeEditorOpen = true">发布公告</button></div>
    <div class="notice-filters"><input v-model.trim="noticeSearch" placeholder="搜索标题" aria-label="搜索公告标题" /><select v-model="noticeStatus" aria-label="筛选公告状态"><option value="ALL">全部状态</option><option value="ACTIVE">展示中</option><option value="WITHDRAWN">已撤回</option></select></div>
    <p v-if="!filteredNotices.length" class="empty-state">暂无符合条件的公告</p>
    <div v-else class="management-table-wrap"><table class="management-table notice-table"><thead><tr><th>公告标题与摘要</th><th>状态</th><th>发布时间</th><th>操作</th></tr></thead><tbody><tr v-for="notice in filteredNotices" :key="notice.id"><td><strong>{{ notice.title }}</strong><p class="table-excerpt">{{ notice.body }}</p></td><td><span class="status" :class="notice.active ? 'published' : 'muted'">{{ notice.active ? '展示中' : '已撤回' }}</span></td><td class="table-time">{{ dateTime(notice.publishedAt) }}</td><td><div class="table-actions"><button class="text-button" :disabled="busy" @click="editNotice(notice)">查看 / 编辑</button><button v-if="notice.active" class="text-button danger-action" :disabled="busy" @click="withdrawNotice(notice.id)">撤回</button><button v-else class="text-button" :disabled="busy" @click="republishNotice(notice.id)">重新发布</button></div></td></tr></tbody></table></div>
  </section>
  <div v-else-if="section === 'users'" class="management-panel">
    <nav class="management-tabs" aria-label="用户管理分类"><button :aria-pressed="usersPanel === 'accounts'" @click="usersPanel = 'accounts'">用户账号</button><button v-if="appealsEnabled" :aria-pressed="usersPanel === 'appeals'" @click="usersPanel = 'appeals'">账号申诉</button></nav>
    <section v-if="appealsEnabled && usersPanel === 'appeals'" class="binding-appeals">
      <div class="binding-appeals-heading"><h2>账号申诉</h2><span>共 {{ appealPage.total }} 条</span></div>
      <form class="appeal-filters" @submit.prevent="searchAppeals()">
        <div class="appeal-queue-tabs" aria-label="申诉队列"><button v-for="queue in ['PENDING', 'DISPUTED', 'REVIEWED']" :key="queue" type="button" :aria-pressed="appealQueue === queue" :disabled="busy || appealListLoading" @click="changeAppealQueue(queue)">{{ queue === 'PENDING' ? '待处理' : queue === 'DISPUTED' ? '争议待核' : '已处理' }}</button></div>
        <input v-model.trim="appealStudentNumber" inputmode="numeric" maxlength="14" pattern="[0-9]{0,14}" placeholder="搜索拟绑定学号" aria-label="搜索拟绑定学号" />
        <button class="button secondary" type="submit" :disabled="busy || appealListLoading">搜索</button>
      </form>
      <p v-if="appealListError" class="message error">{{ appealListError }}</p>
      <p v-else-if="appealListLoading" class="muted-text" role="status">正在查询...</p>
      <template v-else>
        <div v-if="!bindingAppeals.length" class="muted-text">暂无符合条件的申诉</div>
        <div v-for="appeal in bindingAppeals" :key="appeal.id" class="binding-appeal-row">
          <div><strong>{{ appeal.type === 'WRONG_STUDENT_NUMBER' ? '微信绑错学号' : '学号被占用' }}</strong><span>拟绑定学号 {{ appeal.proposedStudentNumber }}</span><small>{{ dateTime(appeal.createdAt) }} · {{ appealStatusLabels[appeal.status] }}</small></div>
          <p>{{ appeal.reason }}</p><button class="button secondary" type="button" @click="openAppeal(appeal)">{{ appeal.status === 'PENDING' ? '核对材料' : appeal.status === 'DISPUTED' ? '继续核对' : '查看结论' }}</button>
        </div>
        <div v-if="appealPage.total > appealPage.size" class="audit-pagination"><button class="button secondary" :disabled="appealPage.page === 0" @click="searchAppeals(appealPage.page - 1)">上一页</button><span>第 {{ appealPage.page + 1 }} 页</span><button class="button secondary" :disabled="(appealPage.page + 1) * appealPage.size >= appealPage.total" @click="searchAppeals(appealPage.page + 1)">下一页</button></div>
      </template>
    </section>
    <section v-if="usersPanel === 'accounts'">
      <p v-if="appealListError" class="message error">{{ appealListError }}</p>
      <div class="management-toolbar"><nav class="management-tabs identity-tabs" aria-label="用户身份分组"><button v-for="(label, role) in userGroups" :key="role" :aria-pressed="userRole === role" @click="userRole = role">{{ label }} <span>{{ matchedUsers.filter(account => accountGroup(account) === role).length }}</span></button></nav><label class="user-search">搜索用户<input v-model.trim="userQuery" placeholder="姓名、学号或邮箱" /></label></div>
      <p class="admin-scope-note">当前分组：{{ userGroups[userRole] }}，共 {{ filteredUsers.length }} 人。组织者归入组织者分组。</p>
      <div v-if="!filteredUsers.length" class="empty-state compact-empty">暂无符合条件的用户</div>
      <div v-else class="management-table-wrap"><table class="management-table users-table"><thead><tr><th>用户信息</th><th>身份</th><th>微信绑定</th><th>Web 登录</th><th>负责活动</th><th>权限与操作</th></tr></thead><tbody><tr v-for="account in filteredUsers" :key="account.id" class="user-table-row"><td><strong>{{ account.name }}</strong><small class="cell-note">学号 {{ account.studentNumber || '待补录' }}</small><small class="cell-note">{{ account.email || '未设置邮箱' }}</small></td><td><span class="status" :class="userRole === 'ADMIN' ? 'published' : userRole === 'ORGANIZER' ? 'pending' : 'muted'">{{ userGroups[userRole] }}</span></td><td>{{ account.wechatBound ? '已绑定' : '未绑定' }}</td><td>{{ account.roles.includes('ADMIN') ? '管理员账号' : account.webCredentialsActive ? '已设置' : '未设置' }}</td><td>{{ account.activityCount ?? 0 }} 场</td><td><div class="table-user-actions"><label v-if="!account.roles.includes('ADMIN') && account.roles.includes('STUDENT')" class="switch"><input type="checkbox" :checked="account.roles.includes('ORGANIZER')" :disabled="busy" @change="changeRole(account, $event.target.checked)" /><span class="switch-track"></span><span>组织者{{ account.roles.includes('ORGANIZER') ? '已授权' : '未授权' }}</span></label><span v-else class="muted-text"><ShieldCheck :size="15" />{{ account.roles.includes('ADMIN') ? '管理员' : '不可授权' }}</span><template v-if="account.roles.includes('STUDENT') && !account.roles.includes('ADMIN')"><button class="text-button" type="button" :disabled="busy || !account.wechatBound || account.bindingManagementEnabled !== true" @click="openUnbind(account)"><Unlink2 :size="15" />解除微信绑定</button><small v-if="!account.wechatBound" class="muted-text">当前未绑定微信</small><small v-else-if="account.bindingManagementEnabled !== true" class="muted-text" role="status">绑定管理未开启，暂不可解绑</small></template><button v-if="!account.roles.includes('ADMIN')" class="text-button danger-action" type="button" :disabled="busy" @click="openDelete(account)">删除用户</button></div>
    </td></tr></tbody></table></div>
    </section>
  </div>
  <section v-else-if="section === 'audit' && !error" class="management-panel audit-management">
    <nav class="management-tabs" aria-label="审计记录分类"><button v-for="(label, key) in { activity: '活动操作', user: '用户与权限', export: '名单导出' }" :key="key" :aria-pressed="auditKind === key" @click="auditKind = key">{{ label }}</button></nav>
    <section v-if="auditKind === 'activity'">
    <h2>活动操作记录</h2>
    <p class="admin-scope-note">共 {{ auditPage.total }} 条已记录操作；新审核意见逐次保存，旧记录不能从当前意见补造。</p>
    <form class="audit-filters" @submit.prevent="searchAudits('activity')"><input v-model.trim="auditFilter.actor" placeholder="操作人姓名" aria-label="活动操作人" /><select v-model="auditFilter.action" aria-label="活动操作类型"><option value="">全部操作</option><option v-for="(label, value) in auditLabels" :key="value" :value="value">{{ label }}</option></select><input v-model="auditFilter.from" type="date" aria-label="活动操作起始日期" /><input v-model="auditFilter.to" type="date" aria-label="活动操作截止日期" /><button class="button secondary" type="submit">筛选</button></form>
    <p v-if="!audit.length" class="muted-text">暂无符合条件的活动操作</p>
    <div v-if="audit.length" class="management-table-wrap"><table class="management-table"><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>活动</th><th>说明</th></tr></thead><tbody><tr v-for="(event, index) in audit" :key="index"><td class="table-time">{{ dateTime(event.createdAt) }}</td><td>{{ event.actorName }}</td><td>{{ auditLabels[event.action] || event.action }}</td><td><RouterLink :to="`/admin/activities/${event.activityId}`">#{{ event.activityId }} {{ event.activityTitle }}</RouterLink></td><td class="table-detail">{{ event.detail || '—' }}</td></tr></tbody></table></div>
    <div class="audit-pagination"><button class="button secondary" :disabled="auditPage.page === 0" @click="searchAudits('activity', auditPage.page - 1)">上一页</button><span>第 {{ auditPage.page + 1 }} 页</span><button class="button secondary" :disabled="(auditPage.page + 1) * auditPage.size >= auditPage.total" @click="searchAudits('activity', auditPage.page + 1)">下一页</button></div>
    </section><section v-if="auditKind === 'user'"><h2>用户权限记录</h2>
    <p class="admin-scope-note">共 {{ securityAuditPage.total }} 条；仅含审计功能启用后的记录，历史无法补造。</p>
    <form class="audit-filters" @submit.prevent="searchAudits('user')"><input v-model.trim="securityAuditFilter.actor" placeholder="操作人姓名" aria-label="权限操作人" /><select v-model="securityAuditFilter.action" aria-label="权限操作类型"><option value="">全部操作</option><option value="ORGANIZER_GRANTED">授予组织者</option><option value="ORGANIZER_REVOKED">撤销组织者</option><option value="WECHAT_UNBOUND">解绑微信</option><option value="BINDING_APPEAL_APPROVED">批准换绑申诉</option><option value="USER_DELETED">删除用户</option></select><input v-model="securityAuditFilter.from" type="date" aria-label="权限操作起始日期" /><input v-model="securityAuditFilter.to" type="date" aria-label="权限操作截止日期" /><button class="button secondary" type="submit">筛选</button></form>
    <p v-if="!securityAudit.length" class="muted-text">暂无符合条件的权限操作</p>
    <div v-if="securityAudit.length" class="management-table-wrap"><table class="management-table"><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>目标用户</th></tr></thead><tbody><tr v-for="(event, index) in securityAudit" :key="index"><td class="table-time">{{ dateTime(event.createdAt) }}</td><td>{{ event.actorName }}</td><td>{{ ({ ORGANIZER_GRANTED: '授予组织者', ORGANIZER_REVOKED: '撤销组织者', WECHAT_UNBOUND: '解绑微信', BINDING_APPEAL_APPROVED: '批准换绑申诉', USER_DELETED: '删除用户' })[event.action] || event.action }}</td><td>{{ event.targetName }} #{{ event.targetId }}</td></tr></tbody></table></div>
    <div class="audit-pagination"><button class="button secondary" :disabled="securityAuditPage.page === 0" @click="searchAudits('user', securityAuditPage.page - 1)">上一页</button><span>第 {{ securityAuditPage.page + 1 }} 页</span><button class="button secondary" :disabled="(securityAuditPage.page + 1) * securityAuditPage.size >= securityAuditPage.total" @click="searchAudits('user', securityAuditPage.page + 1)">下一页</button></div>
    </section><section v-if="auditKind === 'export'"><h2>名单导出记录</h2>
    <p class="admin-scope-note">共 {{ exportAuditPage.total }} 条；记录导出人、活动、名单类型与行数，文件本身不保存在服务端。</p>
    <form class="audit-filters" @submit.prevent="searchExportAudits()"><input v-model.trim="exportAuditFilter.activityId" type="number" min="1" placeholder="活动 ID" aria-label="导出活动 ID" /><input v-model.trim="exportAuditFilter.actor" maxlength="80" placeholder="导出人姓名" aria-label="导出人姓名" /><input v-model="exportAuditFilter.from" type="date" aria-label="导出起始日期" /><input v-model="exportAuditFilter.to" type="date" aria-label="导出截止日期" /><button class="button secondary" type="submit">筛选</button></form>
    <p v-if="exportAuditError" class="message error">{{ exportAuditError }}</p>
    <p v-else-if="!exportAudit.length" class="muted-text">暂无符合条件的名单导出</p>
    <div v-if="exportAudit.length" class="management-table-wrap"><table class="management-table"><thead><tr><th>时间</th><th>导出人</th><th>身份</th><th>活动 / 范围</th><th>类型</th><th>数量</th></tr></thead><tbody><tr v-for="event in exportAudit" :key="event.id"><td class="table-time">{{ dateTime(event.createdAt) }}</td><td>{{ event.actorName }}</td><td>{{ event.scope === 'ADMIN' ? '管理员' : event.scope === 'STUDENT' ? '学生' : '组织者' }}</td><td><strong v-if="event.listType === 'PERSONAL_CHECKINS'">个人活动记录</strong><RouterLink v-else-if="event.activityId" :to="`/admin/activities/${event.activityId}`">#{{ event.activityId }} {{ event.activityTitle }}</RouterLink><span v-else>活动已移除</span></td><td>{{ event.listType === 'PERSONAL_CHECKINS' ? '个人 PDF' : event.listType === 'CHECKINS' ? '签到名单' : '报名名单' }}</td><td>{{ event.rowCount }} {{ event.listType === 'PERSONAL_CHECKINS' ? '场' : '行' }}</td></tr></tbody></table></div>
    <div v-if="exportAuditPage.total > exportAuditPage.size" class="audit-pagination"><button class="button secondary" :disabled="exportAuditPage.page === 0" @click="searchExportAudits(exportAuditPage.page - 1)">上一页</button><span>第 {{ exportAuditPage.page + 1 }} 页</span><button class="button secondary" :disabled="(exportAuditPage.page + 1) * exportAuditPage.size >= exportAuditPage.total" @click="searchExportAudits(exportAuditPage.page + 1)">下一页</button></div>
  </section>
  </section>
  </template>
  <div v-if="noticeEditorOpen" class="admin-modal-backdrop" @keydown.esc="!busy && cancelNoticeEdit()"><form class="admin-modal notice-compose notice-dialog" role="dialog" aria-modal="true" aria-labelledby="notice-dialog-title" @submit.prevent="publishNotice"><h2 id="notice-dialog-title">{{ editingNoticeId ? '编辑公告' : '发布公告' }}</h2><label>标题<input v-model.trim="noticeTitle" required maxlength="160" /></label><label>正文<textarea v-model.trim="noticeBody" required maxlength="3000" rows="5" /></label><p v-if="error" class="message error" role="alert">{{ error }}</p><div class="row-actions"><button class="button primary" type="submit" :disabled="busy">{{ editingNoticeId ? '保存修改' : '发布公告' }}</button><button class="button secondary" type="button" @click="noticePreview = !noticePreview">预览</button><button class="button secondary" type="button" @click="cancelNoticeEdit">取消</button></div><div v-if="noticePreview" class="notice-preview"><strong>{{ noticeTitle || '公告标题' }}</strong><p>{{ noticeBody || '公告正文' }}</p></div></form></div>
  <div v-if="deleteTarget" class="admin-modal-backdrop" role="presentation" @click.self="closeDelete" @keydown.esc="closeDelete"><form class="admin-modal" role="dialog" aria-modal="true" aria-labelledby="delete-user-title" @submit.prevent="confirmDelete"><h2 id="delete-user-title">永久删除用户</h2><p><strong>{{ deleteTarget.name }}</strong> · 用户 #{{ deleteTarget.id }}</p><p>学号 {{ deleteTarget.studentNumber || '未设置' }} · {{ deleteTarget.email || '未设置邮箱' }}</p><p v-if="deleteImpactLoading" role="status">正在核对删除影响…</p><template v-if="deleteImpact"><p class="message error">原账号、微信绑定、登录令牌、头像及 {{ deleteImpact.messages }} 条通知将删除，无法通过系统恢复。</p><p>{{ deleteImpact.activities }} 场负责活动将移交当前管理员；{{ deleteImpact.registrations }} 条报名、{{ deleteImpact.checkins }} 条签到及审计保留匿名记录。未来尚未签到的有效报名将取消并释放名额，待审修改/取消申请终止。</p><p>原用户不能再使用旧登录态；历史匿名记录不关联其重新注册的账号。</p><label>输入用户ID {{ deleteTarget.id }} 确认删除<input v-model.trim="deleteConfirmId" inputmode="numeric" autocomplete="off" :disabled="busy" /></label></template><p v-if="deleteError" class="message error" role="alert">{{ deleteError }}</p><div class="row-actions"><button class="button secondary" type="button" :disabled="busy" @click="closeDelete">取消</button><button class="button danger" type="submit" :disabled="busy || !deleteImpact || deleteImpactLoading || deleteConfirmId !== String(deleteTarget.id)">{{ busy ? '删除中…' : '永久删除' }}</button></div></form></div>
  <div v-if="unbindTarget" class="admin-modal-backdrop" role="presentation" @click.self="closeUnbind" @keydown.esc="closeUnbind"><form class="admin-modal" role="dialog" aria-modal="true" aria-labelledby="unbind-title" @submit.prevent="confirmUnbind"><h2 id="unbind-title">解除微信绑定</h2><p><strong>{{ unbindTarget.name }}</strong> · 学号 {{ unbindTarget.studentNumber || '待补录' }}</p><p>解绑后，小程序登录令牌和该组织者的 Web 登录立即失效。报名、签到及原绑定记录保留；重新绑定须走账号申诉审核。</p><label>解绑原因<textarea ref="unbindReasonInput" v-model.trim="unbindReason" required maxlength="500" rows="3" placeholder="写明核验依据和处理原因" /></label><div class="row-actions"><button class="button secondary" type="button" :disabled="busy" @click="closeUnbind">返回</button><button class="button primary" type="submit" :disabled="busy || !unbindReason.trim()">确认解绑</button></div></form></div>
  <div v-if="appealTarget" class="admin-modal-backdrop" role="presentation" @click.self="closeAppeal" @keydown.esc="closeAppeal">
    <div class="admin-modal appeal-review" role="dialog" aria-modal="true" aria-labelledby="appeal-title">
      <h2 id="appeal-title">{{ appealTarget.status === 'PENDING' || appealTarget.status === 'DISPUTED' ? '核对账号申诉' : '申诉处理结果' }}</h2>
      <p>{{ appealTarget.type === 'WRONG_STUDENT_NUMBER' ? '微信绑错学号' : '学号被占用' }} · 拟绑定 {{ appealTarget.proposedStudentNumber }}</p>
      <p>{{ appealTarget.reason }}</p>
      <p v-if="!appealTarget.evidenceAvailable" class="muted-text">学生证照片已按保留期限清理，审核结论仍保留。</p>
      <p v-else-if="appealEvidenceFailed" class="message error">学生证照片暂时无法加载，请稍后重新打开。</p>
      <img v-else :key="appealTarget.id" :src="`/api/admin/binding-appeals/${appealTarget.id}/evidence`" alt="申请人提交的学生证正面" @error="appealEvidenceFailed = true" />
      <template v-if="appealTarget.status === 'PENDING' || appealTarget.status === 'DISPUTED'">
        <p v-if="appealTarget.status === 'DISPUTED'" class="message error">争议待核：{{ appealTarget.reviewNote }}。目前未更改微信绑定或参与记录；核对清楚后可逐条裁决并批准，或填写理由驳回。</p>
        <section class="appeal-impact">
          <h3>参与记录影响</h3>
          <p v-if="appealImpactError" class="error">{{ appealImpactError }}</p>
          <p v-else-if="!appealImpact" class="muted-text">正在核对记录...</p>
          <template v-else>
            <p>目标账号：{{ appealImpact.targetStudentId ? `#${appealImpact.targetStudentId}` : '尚无对应学生账号' }}。历史来源不明的记录不能自动归属。</p>
            <p v-if="appealImpact.requiresActivityOwnershipReview" class="message error">相关账号负责活动，须先核对活动管理权；不能随学号自动转移组织者权限。</p>
            <p v-else-if="appealHasOrganizerPrivilege" class="message error">相关账号拥有组织者权限，须先单独裁决；不能随学号自动继承。</p>
            <div v-for="account in appealImpact.accounts" :key="account.studentId" class="appeal-impact-row">
              <strong>{{ account.studentId === appealImpact.targetStudentId ? '拟绑定账号' : '历史关联账号' }} #{{ account.studentId }} · {{ account.name }}</strong>
              <span>学号 {{ account.studentNumber || '待补录' }} · {{ account.organizer ? '组织者' : '学生' }} · 负责 {{ account.activityCount }} 场活动 · Web 登录{{ account.webCredentialsActive ? '已启用' : '未启用' }}</span>
              <em v-if="account.boundToOtherIdentity">当前绑定于另一个微信账号，处理前须核对其归属</em>
              <ul v-if="account.ownedActivities?.length" class="appeal-owned-list"><li v-for="activity in account.ownedActivities" :key="activity.id">#{{ activity.id }} {{ activity.title }} · {{ appealActivityStatusLabels[activity.status] || activity.status }}</li></ul>
              <small v-if="account.activityCount > account.ownedActivities?.length">仅显示最近 50 场；其余活动仍需逐场核对。</small>
            </div>
            <p v-if="!appealImpact.rows.length" class="muted-text">此微信暂无可核对的关联报名。</p>
            <div v-for="row in appealImpact.rows" :key="row.registrationId" class="appeal-impact-row">
              <strong>#{{ row.activityId }} {{ row.activityTitle }}</strong>
              <span>{{ row.source === 'CONFIRMED_IDENTITY' ? '本微信来源' : '历史来源待核实' }} · 原账号 #{{ row.sourceStudentId }}{{ row.checkinId ? ' · 已签到' : '' }}</span>
              <em v-if="row.targetHasRegistration">目标账号已有同活动记录，不能直接转移</em>
              <em v-if="row.sourceCheckinConflict">签到来源与报名不一致，不能直接转移</em>
              <label>记录处理
                <select v-model="appealDecisions[row.registrationId]" :aria-label="`活动 ${row.activityTitle} 的归属决定`">
                  <option value="">请选择</option>
                  <option value="TRANSFER" :disabled="row.targetHasRegistration || row.sourceCheckinConflict">转移到目标账号</option>
                  <option value="KEEP">保留在原账号</option>
                </select>
              </label>
              <label>处理依据<input v-model.trim="appealDecisionReasons[row.registrationId]" :aria-label="`活动 ${row.activityTitle} 的处理依据`" maxlength="1000" placeholder="写明核验依据" /></label>
            </div>
            <label>学生证核对姓名<input v-model.trim="appealVerifiedName" maxlength="80" placeholder="以管理员核对的学生证姓名为准" /></label>
            <p class="admin-scope-note">批准后，目标账号姓名将更新为此处核对的姓名，原登记姓名与核对结果保留在审核结论中。请同时核对证件上的学号和本人身份。</p>
            <label v-if="appealTargetBoundElsewhere" class="appeal-replace"><input v-model="appealReplaceTargetBinding" type="checkbox" />确认解除目标学号现有微信绑定，并撤销其登录凭据</label>
            <label>批准依据<textarea v-model.trim="appealApproveReason" maxlength="1000" rows="3" placeholder="写明证件核对与账号归属结论" /></label>
          </template>
        </section>
        <label v-if="appealTarget.status === 'PENDING'">争议待核事项<textarea v-model.trim="appealDisputeReason" maxlength="500" rows="3" placeholder="写明冲突记录和下一步需要核实的事实" /></label>
        <p v-if="appealTarget.status === 'PENDING'" class="muted-text">标记争议不会改变当前绑定、活动管理权或报名签到。</p>
        <label>驳回原因<textarea v-model.trim="appealRejectReason" maxlength="1000" rows="3" placeholder="证据不足时说明需补充什么" /></label>
        <p v-if="appealTarget.status === 'DISPUTED'" class="muted-text">最终依据会与之前的争议说明一并保留；两部分合计不超过 1000 字。</p>
      </template>
      <section v-else class="appeal-impact">
        <h3>{{ appealStatusLabels[appealTarget.status] }}</h3>
        <p>{{ appealTarget.reviewNote || '未填写审核意见' }}</p>
        <p class="muted-text">{{ appealTarget.reviewerName || '未记录审核人' }} · {{ dateTime(appealTarget.reviewedAt) }}</p>
        <p v-if="appealImpactError" class="message error">{{ appealImpactError }}</p>
        <div v-for="row in appealReviewRows" :key="row.registrationId" class="appeal-impact-row"><strong>报名 #{{ row.registrationId }}</strong><span>{{ row.decision === 'TRANSFER' ? '转移到目标账号' : '保留原账号' }} · {{ row.reason }}</span><small>{{ row.reviewerName }} · {{ dateTime(row.reviewedAt) }}</small></div>
      </section>
      <p v-if="appealActionError" class="message error" role="alert">{{ appealActionError }}</p>
      <div class="row-actions"><button class="button secondary" type="button" :disabled="busy" @click="closeAppeal">返回</button><button v-if="appealTarget.status === 'PENDING'" class="button secondary" type="button" :disabled="busy || !appealDisputeReason.trim()" @click="disputeAppeal">标记争议</button><button v-if="appealTarget.status === 'PENDING' || appealTarget.status === 'DISPUTED'" class="button secondary" type="button" :disabled="busy || !appealRejectReason.trim() || appealRejectReason.trim().length + appealPriorNoteLength > 1000" @click="rejectAppeal">驳回申诉</button><button v-if="appealTarget.status === 'PENDING' || appealTarget.status === 'DISPUTED'" class="button primary" type="button" :disabled="!canApproveAppeal" @click="approveAppeal">批准换绑</button></div>
    </div>
  </div>
</template>
