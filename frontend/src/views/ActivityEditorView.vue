<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Bot, ChevronDown, Copy, ImagePlus, MapPin, Save, Trash2 } from '@lucide/vue'
import { api } from '../api'
import ActivityMap from '../components/ActivityMap.vue'
import { chinaDateTime, organizerQuickSchedule, organizerQuickSettings } from '../organizerSchedule'

const route = useRoute()
const router = useRouter()
const adminMode = route.path.startsWith('/admin/')
const id = ref(route.params.id ? Number(route.params.id) : null)
const activityStatus = ref('DRAFT')
const originalTimes = ref({})
const changeReason = ref('')
const pendingRequest = ref(null)
const activityLoaded = ref(!id.value)
const registrationBefore = ref(30)
const checkinBefore = ref(30)
const checkinAfter = ref(30)
const scheduleChanged = ref(false)
const legacySchedule = ref(false)
const scheduleNow = ref(Date.now())
let scheduleTimer
const form = reactive({ title: '', description: '', articleSummary: '', coverImageUrl: '', location: '', longitude: null, latitude: null, capacity: 30, startsAt: '', endsAt: '', registrationOpensAt: '', registrationClosesAt: '', checkinOpensAt: '', checkinClosesAt: '' })
const timeFields = ['startsAt', 'endsAt', 'registrationOpensAt', 'registrationClosesAt', 'checkinOpensAt', 'checkinClosesAt']
const busy = ref(false)
const aiBusy = ref(false)
const aiKind = ref('INTRO')
const aiStyle = ref('NATURAL')
const aiAudience = ref('')
const aiFormat = ref('')
const aiHighlight = ref('')
const aiExtra = ref('')
const aiDraft = ref('')
const aiDraftKind = ref('')
const aiRevision = ref('换一种表达，不重复原句')
const imageBusy = ref(false)
const imagePreview = ref('')
const mapExpanded = ref(false)
const message = ref('')
const error = ref('')

watch(aiKind, () => { aiDraft.value = ''; aiDraftKind.value = '' })

function localDate(value) {
  const parts = chinaDateTime(value)
  return `${parts.date}T${parts.time}`
}

function schedulePayload() {
  const parts = value => ({ date: value?.slice(0, 10), time: value?.slice(11, 16) })
  const scheduled = organizerQuickSchedule({ startsAt: parts(form.startsAt), endsAt: parts(form.endsAt) },
    checkinBefore.value, checkinAfter.value, new Date(), registrationBefore.value)
  return Object.fromEntries(timeFields.map(key => [key,
    new Date(`${scheduled[key].date}T${scheduled[key].time}:00+08:00`).toISOString()]))
}
function displayTime(value) {
  return Number.isFinite(Date.parse(value)) ? localDate(value).replace('T', ' ') : '—'
}
const scheduleTimes = computed(() => {
  if (id.value && !scheduleChanged.value) return originalTimes.value
  const start = Date.parse(`${form.startsAt}:00+08:00`)
  const offset = (minutes, direction) => String(minutes).trim() !== '' && Number.isInteger(Number(minutes))
    && Number(minutes) >= 0 && Number(minutes) <= 10080 && Number.isFinite(start) ? new Date(start + direction * Number(minutes) * 60000).toISOString() : null
  return { registrationClosesAt: offset(registrationBefore.value, -1),
    checkinOpensAt: offset(checkinBefore.value, -1), checkinClosesAt: offset(checkinAfter.value, 1) }
})
const scheduleWarning = computed(() => {
  if (!form.startsAt || !form.endsAt || !activityLoaded.value) return ''
  if (id.value && !scheduleChanged.value) return ''
  scheduleNow.value
  try { schedulePayload(); return '' } catch (cause) { return cause.message }
})
const registrationExpired = computed(() => Date.parse(scheduleTimes.value.registrationClosesAt) <= scheduleNow.value)
const nowLabel = computed(() => displayTime(new Date(scheduleNow.value).toISOString()))
onMounted(() => { scheduleTimer = setInterval(() => { scheduleNow.value = Date.now() }, 15000) })
onUnmounted(() => clearInterval(scheduleTimer))

onMounted(async () => {
  if (!id.value) return
  try {
    const activity = await api(adminMode ? `/admin/activities/${id.value}` : `/activities/${id.value}`)
    activityStatus.value = activity.status
    originalTimes.value = Object.fromEntries(timeFields.map(key => [key, activity[key]]))
    Object.keys(form).forEach((key) => { form[key] = timeFields.includes(key) ? localDate(activity[key]) : (activity[key] ?? '') })
    const settings = organizerQuickSettings(activity)
    if (settings) {
      registrationBefore.value = settings.registrationBefore
      checkinBefore.value = settings.before
      checkinAfter.value = settings.after
    } else legacySchedule.value = true
    if (!adminMode && activity.status === 'PUBLISHED') {
      const [change, cancellation] = await Promise.all([
        api(`/organizer/activities/${id.value}/changes`), api(`/organizer/activities/${id.value}/cancellation`),
      ])
      pendingRequest.value = change?.id ? change : cancellation?.id ? cancellation : null
    }
    activityLoaded.value = true
  } catch (cause) { error.value = cause.message }
})

async function save(redirect = true) {
  if (busy.value || imageBusy.value || !activityLoaded.value || pendingRequest.value) return null
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const payload = { ...form }
    if (id.value && !scheduleChanged.value) Object.assign(payload, originalTimes.value)
    else Object.assign(payload, schedulePayload())
    if (activityStatus.value === 'PUBLISHED') payload.registrationOpensAt = originalTimes.value.registrationOpensAt
    payload.capacity = Number(payload.capacity)
    if (activityStatus.value === 'PUBLISHED') {
      if (!changeReason.value.trim()) { error.value = '请填写修改原因'; return null }
      const updated = await api(adminMode ? `/admin/activities/${id.value}/published-change` : `/organizer/activities/${id.value}/changes`, {
        method: 'POST', body: { proposed: payload, reason: changeReason.value.trim() },
      })
      if (adminMode) router.replace(`/admin/activities/${id.value}`)
      else { pendingRequest.value = updated; message.value = '修改申请已提交，审核通过前沿用当前发布内容。' }
      return updated
    }
    const base = adminMode ? '/admin/activities' : '/organizer/activities'
    const activity = await api(id.value ? `${base}/${id.value}` : base, {
      method: id.value ? 'PUT' : 'POST', body: payload,
    })
    if (!id.value) {
      id.value = activity.id
      if (redirect) router.replace(adminMode ? `/admin/activities/${activity.id}/edit` : `/organizer/${activity.id}/edit`)
    }
    activityStatus.value = activity.status
    originalTimes.value = Object.fromEntries(timeFields.map(key => [key, payload[key]]))
    scheduleChanged.value = false
    message.value = '草稿已保存'
    return activity
  } catch (cause) { error.value = cause.message; return null }
  finally { busy.value = false }
}

async function publish() {
  if (busy.value || imageBusy.value) return
  if (!Number.isFinite(form.longitude) || !Number.isFinite(form.latitude)) {
    error.value = '直接发布前请在地图上选择活动地点'
    return
  }
  const saved = await save(false)
  if (!saved) return
  busy.value = true
  try {
    await api(`/admin/activities/${saved.id}/publish`, { method: 'POST' })
    router.replace(`/admin/activities/${saved.id}`)
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function submitForReview() {
  if (busy.value || imageBusy.value) return
  if (!Number.isFinite(form.longitude) || !Number.isFinite(form.latitude)) {
    error.value = '提交审核前请在地图中选择活动地点'; return
  }
  const saved = await save(false)
  if (!saved) return
  busy.value = true
  try {
    await api(`/organizer/activities/${saved.id}/submit`, { method: 'POST' })
    activityStatus.value = 'PENDING'
    router.replace('/organizer')
  } catch (cause) {
    message.value = '草稿已保存'
    error.value = `提交审核失败：${cause.message}，可重试提交`
  } finally { busy.value = false }
}

function selectLocation(point) {
  form.longitude = point.longitude
  form.latitude = point.latitude
  if (point.name) form.location = point.name
  message.value = point.address && point.address !== point.name ? `已选择：${point.name} · ${point.address}` : '活动地点已更新'
}

async function generate(previousDraft = '') {
  if (!form.title.trim()) { error.value = '请先填写活动名称'; return }
  if (aiBusy.value) return
  aiBusy.value = true
  error.value = ''
  try {
    const instant = (value) => value && Number.isFinite(Date.parse(value)) ? new Date(value).toISOString() : null
    const extra = [
      aiAudience.value.trim() && `面向人群：${aiAudience.value.trim()}`,
      aiFormat.value.trim() && `活动形式：${aiFormat.value.trim()}`,
      aiHighlight.value.trim() && `已确认的亮点或收获：${aiHighlight.value.trim()}`,
      aiExtra.value.trim() && `其他要求：${aiExtra.value.trim()}`,
    ].filter(Boolean).join('\n')
    const result = await api('/organizer/ai/draft', { method: 'POST', body: {
      activityId: id.value, kind: aiKind.value, extra, style: aiStyle.value,
      title: form.title, location: form.location, description: form.description,
      startsAt: instant(form.startsAt), endsAt: instant(form.endsAt),
      previousDraft: previousDraft || null,
      revision: previousDraft ? aiRevision.value : null,
    } })
    aiDraft.value = result.draft
    aiDraftKind.value = aiKind.value
    message.value = '草稿已生成，请检查并手动采用'
  } catch (cause) { error.value = cause.message }
  finally { aiBusy.value = false }
}

async function copyDraft() {
  try {
    await navigator.clipboard.writeText(aiDraft.value)
    message.value = '已复制文案草稿'
  } catch { error.value = '复制失败，请直接选中草稿文本复制' }
}

function applyDraft() {
  const field = aiDraftKind.value === 'LEAD' ? 'articleSummary' : 'description'
  const limit = field === 'articleSummary' ? 300 : 5000
  const draft = aiDraft.value.trim()
  if (!draft || !['LEAD', 'INTRO'].includes(aiDraftKind.value)) return
  if (draft.length > limit) { error.value = `草稿超过${limit}字，请先精简后再填入`; return }
  if (form[field].trim() && !window.confirm('现有文字会被草稿替换，确定继续吗？')) return
  form[field] = draft
  error.value = ''
  message.value = '已填入表单，请检查并手动保存'
}

async function uploadCover(event) {
  const file = event.target.files?.[0]
  if (!file) return
  error.value = ''
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 3 * 1024 * 1024) {
    error.value = '请选择小于3MB的 JPEG 或 PNG 图片'
    event.target.value = ''
    return
  }
  imageBusy.value = true
  const preview = URL.createObjectURL(file)
  try {
    const data = new FormData()
    data.append('file', file)
    const result = await api('/organizer/images', { method: 'POST', body: data })
    if (imagePreview.value.startsWith('blob:')) URL.revokeObjectURL(imagePreview.value)
    imagePreview.value = preview
    form.coverImageUrl = result.url
    message.value = '封面已上传，请保存活动以使用此图片'
  } catch (cause) { URL.revokeObjectURL(preview); error.value = cause.message }
  finally { imageBusy.value = false; event.target.value = '' }
}
function clearImagePreview() {
  if (imagePreview.value.startsWith('blob:')) URL.revokeObjectURL(imagePreview.value)
  imagePreview.value = ''
}
function removeCover() {
  clearImagePreview()
  form.coverImageUrl = ''
  message.value = '封面已从草稿移除，保存后生效'
}
onUnmounted(clearImagePreview)
</script>

<template>
  <RouterLink :to="adminMode ? '/admin' : '/organizer'" class="back-link"><ArrowLeft :size="16" />返回{{ adminMode ? '管理工作台' : '活动管理' }}</RouterLink>
  <section class="page-heading"><div><p class="eyebrow">活动编辑</p><h1>{{ id ? '编辑活动' : '新建活动' }}</h1><p v-if="adminMode && activityStatus === 'PUBLISHED'">修改后立即生效，已报名学生将收到通知</p><p v-else-if="activityStatus === 'PUBLISHED'">修改需提交管理员审核，审核通过后通知已报名学生。</p><p v-else>{{ adminMode ? '可保存草稿，或检查信息后直接发布' : '可保存草稿，或保存并提交管理员审核' }}</p></div></section>
  <p v-if="error" class="message error">{{ error }}</p><p v-if="message" class="message success">{{ message }}</p>
  <p v-if="pendingRequest" class="message success">已有修改或下架申请待审核，暂不可重复提交。</p>
  <div class="editor-layout">
    <form class="editor-form" @submit.prevent="save()">
      <section class="editor-section"><h2>基本信息</h2><label>活动名称<input v-model.trim="form.title" maxlength="160" required placeholder="请输入活动名称" /></label></section>
      <section class="editor-section"><h2>活动地点</h2><label>地点名称<input v-model.trim="form.location" maxlength="200" required placeholder="请输入活动地点" /></label><button class="map-toggle" type="button" :aria-expanded="mapExpanded" @click="mapExpanded = !mapExpanded"><MapPin :size="17" />{{ Number.isFinite(form.longitude) ? '查看或调整地图位置' : '在地图中选点' }}<ChevronDown :size="16" :class="{ 'is-open': mapExpanded }" /></button><div v-if="mapExpanded" class="editor-map"><ActivityMap :longitude="form.longitude" :latitude="form.latitude" interactive @select="selectLocation" /><p class="map-hint">可搜索建筑或点击地图选点；草稿允许暂不选点。</p></div><p v-if="Number.isFinite(form.longitude)" class="map-selection">已选位置：{{ form.longitude.toFixed(6) }}, {{ form.latitude.toFixed(6) }}</p></section>
      <section class="editor-section schedule-editor"><h2>活动安排</h2>
        <div class="schedule-block"><h3>活动时间 <small>北京时间</small></h3><div class="form-grid"><label>开始<input aria-label="活动开始（北京时间）" v-model="form.startsAt" type="datetime-local" required @input="scheduleChanged = true" /></label><label>结束<input aria-label="活动结束（北京时间）" v-model="form.endsAt" type="datetime-local" required @input="scheduleChanged = true" /></label></div><label class="capacity-field">名额上限<input v-model.number="form.capacity" type="number" min="1" required /></label></div>
        <div class="schedule-block"><h3>报名 <small>{{ activityStatus === 'PUBLISHED' ? '已发布' : '发布即开放' }}</small></h3><div class="schedule-sentence"><span>活动开始前</span><input v-model.number="registrationBefore" aria-label="报名提前截止（分钟）" type="number" min="0" max="10080" required @input="scheduleChanged = true" /><span>分钟截止</span></div><div class="schedule-presets" aria-label="报名截止快捷设置"><button v-for="minutes in [0, 5, 15, 30, 60]" :key="minutes" type="button" :aria-pressed="registrationBefore === minutes" @click="registrationBefore = minutes; scheduleChanged = true">{{ minutes === 0 ? '开始时' : `提前 ${minutes} 分钟` }}</button></div><p class="schedule-result" :class="{ expired: registrationExpired }">截止：<strong>{{ displayTime(scheduleTimes.registrationClosesAt) }}</strong><span v-if="registrationExpired">（已过期）</span></p></div>
        <div class="schedule-block"><h3>签到</h3><div class="schedule-sentence"><span>活动开始前</span><input v-model.number="checkinBefore" aria-label="签到提前开放（分钟）" type="number" min="0" max="240" required @input="scheduleChanged = true" /><span>分钟开放</span><span class="schedule-rule-divider">至</span><span>开始后</span><input v-model.number="checkinAfter" aria-label="开始后签到截止（分钟）" type="number" min="0" max="240" required @input="scheduleChanged = true" /><span>分钟截止</span></div><div class="schedule-presets" aria-label="签到快捷设置"><button v-for="minutes in [15, 30, 60]" :key="minutes" type="button" :aria-pressed="checkinBefore === minutes && checkinAfter === minutes" @click="checkinBefore = minutes; checkinAfter = minutes; scheduleChanged = true">前后各 {{ minutes }} 分钟</button></div><p class="schedule-result">时段：<strong>{{ displayTime(scheduleTimes.checkinOpensAt) }}</strong> 至 <strong>{{ displayTime(scheduleTimes.checkinClosesAt) }}</strong></p></div>
        <p v-if="legacySchedule && !scheduleChanged" class="admin-scope-note">当前保留此活动原有时间安排。</p>
        <p v-if="scheduleWarning" class="message error" role="status">{{ scheduleWarning }}。当前北京时间：{{ nowLabel }}</p>
      </section>
      <section class="editor-section"><h2>推文内容</h2><label>推文导语<input v-model.trim="form.articleSummary" maxlength="300" placeholder="简要介绍活动亮点" required /></label><label>推文正文<textarea v-model.trim="form.description" rows="8" maxlength="5000" required /></label></section>
      <section class="editor-section"><h2>封面图片</h2><div class="cover-upload"><img v-if="imagePreview || form.coverImageUrl" class="editor-cover-preview" :src="imagePreview || form.coverImageUrl" alt="活动封面预览" /><span v-else class="cover-upload-placeholder"><ImagePlus :size="26" />暂无封面</span><div class="cover-upload-actions"><label class="button secondary cover-upload-button"><ImagePlus :size="16" />{{ imagePreview || form.coverImageUrl ? '替换图片' : '选择图片' }}<input class="visually-hidden" type="file" accept="image/jpeg,image/png" :disabled="imageBusy" @change="uploadCover" /></label><button v-if="imagePreview || form.coverImageUrl" class="button secondary" type="button" @click="removeCover"><Trash2 :size="15" />移除</button><small>JPEG 或 PNG，最多 3MB</small></div></div><p v-if="imageBusy" class="map-hint">正在上传图片…</p><details class="cover-url"><summary>使用图片地址</summary><label>HTTPS 图片地址<input v-model.trim="form.coverImageUrl" type="text" maxlength="1000" placeholder="选填 HTTPS 图片地址" @input="clearImagePreview" /></label></details></section>
      <section v-if="activityStatus === 'PUBLISHED'" class="editor-section"><h2>修改说明</h2><label>修改原因<textarea v-model.trim="changeReason" maxlength="500" rows="3" required :placeholder="adminMode ? '填写修改原因；保存后立即生效并留操作记录' : '填写修改原因，提交管理员审核'" /></label></section>
      <div class="editor-actions"><button class="button secondary" type="submit" :disabled="busy || imageBusy || !activityLoaded || !!pendingRequest"><Save :size="17" />{{ busy ? '保存中…' : activityStatus === 'PUBLISHED' ? (adminMode ? '保存并立即生效' : '提交修改审核') : '保存草稿' }}</button><button v-if="adminMode && ['DRAFT', 'REJECTED', 'PENDING'].includes(activityStatus)" class="button primary" type="button" :disabled="busy || imageBusy" @click="publish">保存并直接发布</button><button v-if="!adminMode && ['DRAFT', 'REJECTED'].includes(activityStatus)" class="button primary" type="button" :disabled="busy || imageBusy" @click="submitForReview">保存并提交审核</button></div>
    </form>
    <section class="ai-panel">
      <div class="section-title"><Bot :size="19" /><h2>文案辅助</h2></div>
      <p class="ai-stage">内容设置</p>
      <label>文案类型<select v-model="aiKind" :disabled="aiBusy"><option value="LEAD">推文导语</option><option value="INTRO">活动简介</option><option value="NOTICE">报名须知</option><option value="SUMMARY">活动总结草稿</option></select></label>
      <label>文案语气<select v-model="aiStyle"><option value="NATURAL">自然真诚</option><option value="WARM">温暖亲切</option><option value="FORMAL">简洁正式</option></select></label>
      <label>面向人群<input v-model.trim="aiAudience" maxlength="80" placeholder="选填，如大一新生" /></label>
      <label>活动形式<input v-model.trim="aiFormat" maxlength="80" placeholder="选填，仅填已确定的形式" /></label>
      <label>亮点或参与收获<input v-model.trim="aiHighlight" maxlength="180" placeholder="选填，仅填已确认的内容" /></label>
      <label>其他要求<textarea v-model="aiExtra" rows="2" maxlength="120" placeholder="选填，如更短、更有感染力" /></label>
      <button class="button primary ai-generate" type="button" :disabled="aiBusy" @click="generate()"><Bot :size="17" />{{ aiBusy ? '生成中…' : '生成草稿' }}</button>
      <template v-if="aiDraft">
        <p class="ai-stage">草稿预览</p>
        <label>生成结果<textarea v-model="aiDraft" rows="11" /></label>
        <label>修改方向<select v-model="aiRevision"><option value="换一种表达，不重复原句">换一种表达</option><option value="更简短，删除重复信息">更简短</option><option value="更亲切，但不添加未经确认的事实">更亲切</option><option value="更正式，但避免公文套话">更正式</option></select></label>
        <button class="button secondary" type="button" :disabled="aiBusy" @click="generate(aiDraft)">再写一版</button>
        <div class="row-actions"><button class="button secondary" type="button" @click="copyDraft"><Copy :size="16" />复制</button><button v-if="['LEAD', 'INTRO'].includes(aiDraftKind)" class="button secondary" type="button" @click="applyDraft">{{ aiDraftKind === 'LEAD' ? '填入推文导语' : '填入推文正文' }}</button></div>
      </template>
    </section>
  </div>
</template>
