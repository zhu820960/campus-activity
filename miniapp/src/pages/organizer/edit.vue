<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api, assetUrl, downloadOrganizerCover, uploadOrganizerCover } from '../../common/api'
import { chinaDateTime, createOrganizerForm, organizerPayload, organizerQuickSchedule, organizerQuickSettings } from '../../common/organizerForm'

const id = ref(null)
const status = ref('DRAFT')
const originalStartsAt = ref('')
const originalEndsAt = ref('')
const originalTimes = ref({})
const changeReason = ref('')
const changePending = ref(false)
const form = ref(createOrganizerForm())
const coverPreview = ref('')
const busy = ref(false)
const loading = ref(false)
const aiBusy = ref(false)
const error = ref('')
const loadFailed = ref(false)
const aiError = ref('')
const aiExtra = ref('')
const aiDraft = ref('')
const aiKind = ref(0)
const aiStyle = ref(0)
const quickSchedule = ref(true)
const checkinBeforeMinutes = ref('30')
const registrationBeforeMinutes = ref('30')
const schedulePreview = computed(() => {
  if (!quickSchedule.value) return ''
  try {
    const plan = organizerQuickSchedule(form.value, checkinBeforeMinutes.value, checkinAfterMinutes.value, new Date(), registrationBeforeMinutes.value)
    const show = value => `${value.date} ${value.time}`
    return `报名截止：${show(plan.registrationClosesAt)}；签到：${show(plan.checkinOpensAt)} 至 ${show(plan.checkinClosesAt)}`
  } catch (cause) { return cause.message }
})
const checkinAfterMinutes = ref('30')
const kinds = [
  { label: '推文导语', value: 'LEAD' }, { label: '活动简介', value: 'INTRO' },
  { label: '报名须知', value: 'NOTICE' }, { label: '活动总结', value: 'SUMMARY' }
]
const styles = [
  { label: '自然真诚', value: 'NATURAL' }, { label: '温暖亲切', value: 'WARM' },
  { label: '简洁正式', value: 'FORMAL' }
]
const primaryTimeFields = [
  { key: 'startsAt', label: '活动开始' },
  { key: 'endsAt', label: '活动结束' }
]
const extraTimeFields = [
  { key: 'registrationOpensAt', label: '报名开始' },
  { key: 'registrationClosesAt', label: '报名截止' },
  { key: 'checkinOpensAt', label: '签到开始' },
  { key: 'checkinClosesAt', label: '签到截止' }
]
const canEdit = computed(() => ['DRAFT', 'REJECTED'].includes(status.value)
  || (status.value === 'PUBLISHED' && !changePending.value))

onLoad(async (options = {}) => {
  if (!options.id) {
    loading.value = true
    try {
      const user = await api.me()
      if (!user.roles?.includes('ORGANIZER') || user.roles?.includes('ADMIN')) {
        throw new Error('仅学生组织者可新建活动')
      }
    } catch (e) { error.value = e.message || '无法验证组织者权限'; loadFailed.value = true }
    finally { loading.value = false }
    return
  }
  const parsed = Number(options.id)
  if (!Number.isSafeInteger(parsed) || parsed < 1) { error.value = '活动编号无效'; loadFailed.value = true; return }
  id.value = parsed
  loading.value = true
  try {
    const item = await api.organizerActivity(parsed)
    status.value = item.status
    originalStartsAt.value = item.startsAt
    originalEndsAt.value = item.endsAt
    originalTimes.value = Object.fromEntries([
      'startsAt', 'endsAt', 'registrationOpensAt', 'registrationClosesAt',
      'checkinOpensAt', 'checkinClosesAt'
    ].map(key => [key, item[key]]))
    if (item.status === 'PUBLISHED') {
      changePending.value = Boolean(await api.organizerPendingChange(parsed)
        .catch(e => { if (e.status === 404) return null; throw e }))
    }
    form.value = createOrganizerForm(item)
    const settings = organizerQuickSettings(item)
    quickSchedule.value = Boolean(settings)
    if (settings) {
      registrationBeforeMinutes.value = String(settings.registrationBefore)
      checkinBeforeMinutes.value = String(settings.before)
      checkinAfterMinutes.value = String(settings.after)
    }
    coverPreview.value = item.coverImageUrl?.startsWith('/images/')
      ? await downloadOrganizerCover(item.coverImageUrl).catch(() => '')
      : assetUrl(item.coverImageUrl)
  } catch (e) { error.value = e.message || '加载活动失败'; loadFailed.value = true }
  finally { loading.value = false }
})

function changeTime(key, part, event) {
  form.value[key][part] = event.detail.value
  if (key === 'startsAt' && form.value.startsAt.date && form.value.startsAt.time
      && !form.value.endsAt.date && !form.value.endsAt.time) {
    const start = new Date(`${form.value.startsAt.date}T${form.value.startsAt.time}:00+08:00`)
    form.value.endsAt = chinaDateTime(new Date(start.getTime() + 2 * 60 * 60 * 1000).toISOString())
  }
}
function manualLocation(event) {
  form.value.location = event.detail.value
  form.value.longitude = null
  form.value.latitude = null
}
function chooseLocation() {
  uni.chooseLocation({
    success: place => {
      form.value.location = place.name || place.address || ''
      form.value.longitude = Number(place.longitude)
      form.value.latitude = Number(place.latitude)
    },
    fail: e => { if (!String(e.errMsg || '').includes('cancel')) error.value = '地图选点失败，请检查定位权限后重试' }
  })
}
function chooseCover() {
  if (busy.value || !canEdit.value) return
  uni.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'],
    success: async result => {
      const path = result.tempFiles?.[0]?.tempFilePath
      if (!path) return
      busy.value = true; error.value = ''
      try {
        const prepared = await prepareCover(path)
        const url = await uploadOrganizerCover(prepared)
        form.value.coverImageUrl = url
        coverPreview.value = prepared
      } catch (e) { error.value = e.message || '上传失败' }
      finally { busy.value = false }
    },
    fail: e => { if (!String(e.errMsg || '').includes('cancel')) error.value = '选择图片失败' }
  })
}
function coverSize(path) {
  return new Promise((resolve, reject) => uni.getFileInfo({
    filePath: path, success: info => resolve(info.size),
    fail: () => reject(new Error('无法读取图片，请重试'))
  }))
}
async function prepareCover(path) {
  const originalSize = await coverSize(path)
  try {
    const compressed = await new Promise((resolve, reject) => uni.compressImage({
      src: path, quality: 78, compressedWidth: 1600,
      success: result => resolve(result.tempFilePath),
      fail: () => reject(new Error('图片压缩失败'))
    }))
    if (await coverSize(compressed) <= 3 * 1024 * 1024) return compressed
  } catch { /* A small original can still be uploaded if compression is unavailable. */ }
  if (originalSize <= 3 * 1024 * 1024) return path
  throw new Error('图片超过3MB且无法压缩，请换一张图片')
}
function removeCover() { form.value.coverImageUrl = ''; coverPreview.value = '' }

function possibleInstant(value) {
  return value?.date && value?.time ? new Date(`${value.date}T${value.time}:00+08:00`).toISOString() : null
}
async function generateDraft() {
  if (aiBusy.value) return
  aiBusy.value = true; aiError.value = ''
  try {
    const result = await api.organizerDraft({
      activityId: id.value, kind: kinds[aiKind.value].value, style: styles[aiStyle.value].value,
      title: form.value.title, location: form.value.location,
      startsAt: possibleInstant(form.value.startsAt), endsAt: possibleInstant(form.value.endsAt),
      description: form.value.description, extra: aiExtra.value
    })
    aiDraft.value = result.draft || ''
  } catch (e) { aiError.value = e.message || '生成失败' }
  finally { aiBusy.value = false }
}
function fillDraft() {
  if (!aiDraft.value.trim()) return
  const field = kinds[aiKind.value].value === 'LEAD' ? 'articleSummary' : 'description'
  const apply = () => { form.value[field] = aiDraft.value.trim(); uni.showToast({ title: '已填入表单' }) }
  if (!form.value[field]) { apply(); return }
  uni.showModal({ title: '替换现有文案？', content: '当前字段已有内容，替换后仍需手动保存活动。',
    success: result => { if (result.confirm) apply() } })
}
function copyDraft() { uni.setClipboardData({ data: aiDraft.value.trim() }) }

async function save(submit = false) {
  if (busy.value || !canEdit.value) return
  let draftSaved = false
  busy.value = true; error.value = ''
  try {
    const sameMinute = (field, original) => {
      const initial = chinaDateTime(original)
      return field.date === initial.date && field.time === initial.time
    }
    const timesChanged = status.value === 'PUBLISHED'
      && (!sameMinute(form.value.startsAt, originalStartsAt.value)
        || !sameMinute(form.value.endsAt, originalEndsAt.value)
        || Number(checkinBeforeMinutes.value) !== organizerQuickSettings(originalTimes.value)?.before
        || Number(checkinAfterMinutes.value) !== organizerQuickSettings(originalTimes.value)?.after
        || Number(registrationBeforeMinutes.value) !== organizerQuickSettings(originalTimes.value)?.registrationBefore)
    const scheduled = quickSchedule.value && (status.value !== 'PUBLISHED' || timesChanged)
      ? organizerQuickSchedule(form.value, checkinBeforeMinutes.value, checkinAfterMinutes.value, new Date(), registrationBeforeMinutes.value)
      : form.value
    if (status.value === 'PUBLISHED' && scheduled !== form.value) {
      scheduled.registrationOpensAt = form.value.registrationOpensAt
    }
    const payload = organizerPayload(scheduled)
    if (status.value === 'PUBLISHED') {
      for (const [key, original] of Object.entries(originalTimes.value)) {
        if (scheduled === form.value && sameMinute(form.value[key], original)) payload[key] = original
      }
      if (!changeReason.value.trim()) throw new Error('请填写修改原因')
      await api.organizerRequestChange(id.value, payload, changeReason.value.trim())
      uni.showToast({ title: '变更已提交审核', icon: 'success' })
      uni.redirectTo({ url: `/pages/organizer/detail?id=${id.value}` })
      return
    }
    if (submit && (payload.longitude == null || payload.latitude == null)) {
      throw new Error('提交审核前请在地图中选择活动地点')
    }
    const item = id.value ? await api.organizerEdit(id.value, payload) : await api.organizerCreate(payload)
    id.value = item.id
    draftSaved = true
    if (submit) await api.organizerSubmit(item.id)
    uni.showToast({ title: submit ? '已提交审核' : '草稿已保存', icon: 'success' })
    uni.redirectTo({ url: `/pages/organizer/detail?id=${item.id}` })
  } catch (e) { error.value = draftSaved && submit ? `草稿已保存，提交审核失败：${e.message || '请稍后重试'}` : e.message || '保存失败' }
  finally { busy.value = false }
}
function confirmSubmit() {
  uni.showModal({ title: '提交管理员审核', content: '提交后需等待管理员审核，期间不能编辑。',
    success: result => { if (result.confirm) save(true) } })
}
</script>

<template>
  <view class="ui-page editor-page">
    <text class="heading">{{ status === 'PUBLISHED' ? '申请修改活动' : id ? '编辑活动' : '新建活动' }}</text>
    <view v-if="loading" class="ui-skeleton loading" />
    <view v-if="error" class="ui-feedback ui-feedback--error">{{ error }}</view>
    <view v-if="!loading && !loadFailed && !canEdit" class="ui-feedback">{{ changePending ? '该活动已有待审核变更' : '该活动当前不能编辑' }}</view>
    <view v-if="!loading && !loadFailed && canEdit">
        <text class="section-title">基本信息</text>
        <text class="label">活动名称</text><input v-model="form.title" class="field" maxlength="160" placeholder="填写活动名称" />
        <text class="label">活动地点</text><input :value="form.location" class="field" maxlength="200" placeholder="地点名称" @input="manualLocation" />
        <button class="ui-button ui-button--secondary choose" @tap="chooseLocation">在地图中选点</button>
        <text v-if="form.longitude != null" class="coordinates">已选位置：{{ form.longitude.toFixed(6) }}, {{ form.latitude.toFixed(6) }}</text>
        <text class="label">人数上限</text><input v-model="form.capacity" class="field" type="number" placeholder="至少 1 人" />

        <text class="section-title">时间安排</text>
        <view v-for="item in primaryTimeFields" :key="item.key" class="time-row">
          <text class="label">{{ item.label }}</text>
          <view class="pickers"><picker mode="date" :value="form[item.key].date" @change="changeTime(item.key, 'date', $event)"><view class="picker-value">{{ form[item.key].date || '选择日期' }}</view></picker><picker mode="time" :value="form[item.key].time" @change="changeTime(item.key, 'time', $event)"><view class="picker-value">{{ form[item.key].time || '选择时间' }}</view></picker></view>
        </view>
        <view v-if="quickSchedule" class="quick-schedule">
          <text class="schedule-rule">审核通过并发布后即可报名</text>
          <view class="relative-fields">
            <view><text class="label">报名提前截止（分钟）</text><input v-model="registrationBeforeMinutes" class="field" type="number" placeholder="30" /></view>
            <view><text class="label">签到提前（分钟）</text><input v-model="checkinBeforeMinutes" class="field" type="number" placeholder="30" /></view>
            <view><text class="label">开始后截止（分钟）</text><input v-model="checkinAfterMinutes" class="field" type="number" placeholder="30" /></view>
          </view>
          <text class="schedule-rule">{{ schedulePreview }}</text>
        </view>
        <view v-if="!quickSchedule && id">
          <view v-for="item in extraTimeFields" :key="item.key" class="time-row">
            <text class="label">{{ item.label }}</text>
            <view class="pickers"><picker mode="date" :value="form[item.key].date" @change="changeTime(item.key, 'date', $event)"><view class="picker-value">{{ form[item.key].date || '选择日期' }}</view></picker><picker mode="time" :value="form[item.key].time" @change="changeTime(item.key, 'time', $event)"><view class="picker-value">{{ form[item.key].time || '选择时间' }}</view></picker></view>
          </view>
        </view>

        <text class="section-title">活动内容</text>
        <text class="label">推文导语</text><textarea v-model="form.articleSummary" class="textarea short" maxlength="300" placeholder="可选，填写活动亮点" />
        <text class="label">推文正文</text><textarea v-model="form.description" class="textarea" maxlength="5000" placeholder="介绍活动内容和参与方式" />
        <text class="label">封面图片</text>
        <image v-if="coverPreview" class="cover" :src="coverPreview" mode="aspectFill" />
        <view class="cover-actions"><button class="ui-button ui-button--secondary" :disabled="busy" @tap="chooseCover">{{ coverPreview ? '替换封面' : '上传封面' }}</button><button v-if="form.coverImageUrl" class="ui-button ui-button--secondary" :disabled="busy" @tap="removeCover">移除</button></view>

        <view class="ai-panel">
          <text class="section-title">文案辅助</text>
          <view class="ai-selects"><picker :range="kinds.map(item => item.label)" :value="aiKind" @change="aiKind = Number($event.detail.value)"><view class="picker-value">{{ kinds[aiKind].label }}</view></picker><picker :range="styles.map(item => item.label)" :value="aiStyle" @change="aiStyle = Number($event.detail.value)"><view class="picker-value">{{ styles[aiStyle].label }}</view></picker></view>
          <textarea v-model="aiExtra" class="textarea short" maxlength="500" placeholder="补充已确认的形式、亮点或要求" />
          <button class="ui-button ui-button--secondary choose" :disabled="aiBusy" @tap="generateDraft">{{ aiBusy ? '生成中...' : '生成草稿' }}</button>
          <text v-if="aiError" class="ai-error">{{ aiError }}</text>
          <template v-if="aiDraft"><text class="label">可编辑草稿</text><textarea v-model="aiDraft" class="textarea" maxlength="5000" /><button v-if="aiKind < 2" class="ui-button ui-button--secondary choose" @tap="fillDraft">填入表单</button><button v-else class="ui-button ui-button--secondary choose" @tap="copyDraft">复制草稿</button></template>
        </view>

        <view v-if="status === 'PUBLISHED'"><text class="label">修改原因</text><textarea v-model="changeReason" class="textarea short" maxlength="500" placeholder="说明修改原因，管理员批准前学生仍看到原活动内容" /></view>
        <view class="actions"><button v-if="status !== 'PUBLISHED'" class="ui-button ui-button--secondary" :disabled="busy" @tap="save(false)">保存草稿</button><button class="ui-button ui-button--primary" :disabled="busy" @tap="status === 'PUBLISHED' ? save(false) : confirmSubmit()">{{ status === 'PUBLISHED' ? '提交变更审核' : '提交审核' }}</button></view>
    </view>
  </view>
</template>

<style scoped>
.editor-page { padding-top: 20px; padding-bottom: 80px; }
.heading { display: block; color: #203734; font-size: 23px; font-weight: 700; }
.loading { height: 180px; margin-top: 20px; }
.ui-feedback { margin-top: 14px; }
.section-title { display: block; margin: 30px 0 15px; color: #203734; font-size: 17px; font-weight: 700; }
.label { display: block; margin: 17px 0 7px; color: #52675e; font-size: 12px; font-weight: 600; }
.field, .textarea, .picker-value { box-sizing: border-box; width: 100%; border: 1px solid #dce6df; border-radius: 6px; background: #fff; color: #203734; font-size: 14px; }
.field { height: 44px; padding: 0 12px; }
.textarea { height: 190px; padding: 12px; line-height: 1.5; }
.textarea.short { height: 90px; }
.choose { width: 100%; margin: 10px 0 0; }
.coordinates { display: block; margin-top: 9px; color: #147f74; font-size: 12px; }
.pickers, .ai-selects { display: flex; gap: 9px; }
.quick-schedule { margin-top: 20px; }
.schedule-rule { display: block; color: #52675e; font-size: 12px; line-height: 1.5; }
.relative-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; }
.relative-fields .label { line-height: 1.35; }
.pickers picker, .ai-selects picker { flex: 1; min-width: 0; }
.picker-value { height: 43px; padding: 0 10px; line-height: 42px; }
.cover { display: block; width: 100%; height: 180px; border-radius: 6px; }
.cover-actions, .actions { display: flex; gap: 9px; margin-top: 10px; }
.cover-actions button, .actions button { flex: 1; min-width: 0; margin: 0; }
.ai-panel { margin-top: 26px; padding: 1px 15px 18px; border-radius: 8px; background: #fff; }
.ai-panel .section-title { margin-top: 18px; }
.ai-panel .picker-value, .ai-panel .textarea { background: #f8faf8; }
.ai-panel .textarea { margin-top: 12px; }
.ai-error { display: block; margin-top: 10px; color: #a34538; font-size: 12px; }
.actions { margin-top: 27px; }
</style>
