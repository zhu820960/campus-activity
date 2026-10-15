<script setup>
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import ActivityCover from '../../components/ActivityCover.vue'
import { api, isSignedIn, requireSignIn } from '../../common/api'
import { activityBadge, formatTime, locationReady, registrationLabel } from '../../common/format'

const id = ref(0)
const activity = ref(null)
const registration = ref(null)
const loading = ref(false)
const pending = ref(false)
const error = ref('')
const feedback = ref(null)
const rating = ref(5)
const comment = ref('')
const feedbackBusy = ref(false)
const feedbackError = ref('')
const label = computed(() => activity.value ? registrationLabel(activity.value) : '')
const badge = computed(() => activity.value ? activityBadge(activity.value) : null)
const activeRegistration = computed(() => registration.value?.status === 'ACTIVE')
const canFeedback = computed(() => activity.value?.status === 'PUBLISHED' && registration.value?.checkedAt
  && Date.now() >= Date.parse(activity.value.endsAt))
onLoad(options => { id.value = Number(options.id) })
onShow(load)

async function load() {
  if (!Number.isSafeInteger(id.value) || id.value <= 0) { error.value = '活动编号无效'; return }
  if (!isSignedIn()) { activity.value = null; error.value = '请先登录后查看活动'; return }
  loading.value = true; error.value = ''
  try {
    activity.value = await api.activity(id.value)
    registration.value = null
    if (isSignedIn()) {
      try { registration.value = (await api.mine()).find(r => r.activityId === id.value) || null }
      catch { /* The student detail is still available if personal records fail to load. */ }
    }
    feedback.value = registration.value?.checkedAt
      ? await api.myFeedback(id.value).catch(() => null) : null
    if (feedback.value) { rating.value = feedback.value.rating; comment.value = feedback.value.comment }
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}

async function saveFeedback() {
  if (feedbackBusy.value || !canFeedback.value) return
  if (!comment.value.trim()) { feedbackError.value = '请填写评价内容'; return }
  feedbackBusy.value = true; feedbackError.value = ''
  try {
    feedback.value = await api.saveFeedback(id.value, rating.value, comment.value.trim())
    uni.showToast({ title: '评价已保存', icon: 'success' })
  } catch (e) { feedbackError.value = e.message || '保存失败' }
  finally { feedbackBusy.value = false }
}

async function changeRegistration() {
  if (!requireSignIn() || pending.value) return
  pending.value = true
  const wasRegistered = activeRegistration.value
  try {
    if (wasRegistered) await api.cancel(id.value)
    else await api.register(id.value)
    uni.showToast({ title: wasRegistered ? '已取消报名' : '报名成功' })
    await load()
  } catch (e) { uni.showToast({ title: e.message, icon: 'none' }) }
  finally { pending.value = false }
}

function openMap() {
  if (!locationReady(activity.value)) {
    uni.showToast({ title: '该活动暂无地图坐标', icon: 'none' }); return
  }
  uni.openLocation({
    latitude: Number(activity.value.latitude),
    longitude: Number(activity.value.longitude),
    name: activity.value.location || activity.value.title,
    address: activity.value.location || '',
    fail: () => uni.showToast({ title: '地图暂不可用', icon: 'none' })
  })
}
function openLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
</script>

<template>
  <view class="detail-page">
    <view v-if="loading" class="loading-state"><view class="ui-skeleton cover-skeleton" /><view class="ui-skeleton text-skeleton" /><view class="ui-skeleton text-skeleton short" /></view>
    <view v-else-if="error" class="ui-page"><view class="ui-feedback ui-feedback--error" @tap="load">{{ error }}</view><button v-if="!isSignedIn()" class="ui-button ui-button--primary" @tap="openLogin">微信登录</button><button v-else class="ui-button ui-button--secondary" @tap="load">重试</button></view>
    <template v-else-if="activity">
      <view class="cover"><ActivityCover :cover-url="activity.coverImageUrl" /></view>
      <view class="content">
        <view class="heading">
          <text class="ui-status" :class="'ui-status--' + badge.tone">{{ badge.text }}</text>
          <text class="title">{{ activity.title }}</text>
          <text v-if="activity.status === 'CANCELLED'" class="cancellation-note">取消原因：{{ activity.cancelReason || '请联系活动组织者' }}</text>
          <text v-if="activity.articleSummary" class="summary">{{ activity.articleSummary }}</text>
        </view>

        <view class="facts">
          <view class="fact"><image src="/static/icons/calendar.png" mode="aspectFit" /><view><text class="fact-key">活动时间</text><text class="fact-value">{{ formatTime(activity.startsAt) }} 至 {{ formatTime(activity.endsAt) }}</text></view></view>
          <view class="fact"><image src="/static/icons/pin.png" mode="aspectFit" /><view><text class="fact-key">活动地点</text><text class="fact-value">{{ activity.location || '地点待定' }}</text></view></view>
          <view class="fact"><image src="/static/icons/users.png" mode="aspectFit" /><view><text class="fact-key">报名人数</text><text class="fact-value">{{ activity.registeredCount }}/{{ activity.capacity }} 人</text></view></view>
          <view class="fact"><image src="/static/icons/ticket.png" mode="aspectFit" /><view><text class="fact-key">我的状态</text><text class="fact-value">{{ activeRegistration ? (registration.checkedAt ? '已签到' : '已报名') : label }}</text></view></view>
        </view>

        <view class="section-heading">活动地点</view>
        <view v-if="locationReady(activity)" class="map-panel">
          <map class="map" :latitude="Number(activity.latitude)" :longitude="Number(activity.longitude)"
            :markers="[{ id: 1, latitude: Number(activity.latitude), longitude: Number(activity.longitude), title: activity.location }]"
            :enable-scroll="false" :enable-zoom="false" />
          <button class="map-button" @tap="openMap"><image src="/static/icons/pin.png" mode="aspectFit" />查看地图及导航</button>
        </view>
        <view v-else class="ui-feedback">暂无地图坐标，可按上方地点文字查找</view>

        <view class="section-heading">活动详情</view>
        <text class="article">{{ activity.description || '暂无详细介绍' }}</text>
        <view v-if="canFeedback" class="feedback-panel">
          <view class="section-heading">活动评价</view>
          <text v-if="feedback?.hiddenAt" class="feedback-note">该评价已由管理员隐藏，不能继续修改。</text>
          <template v-else>
            <text class="feedback-note">仅本人可提交；完成签到后每场活动保留一条评价。</text>
            <view class="rating-choices"><button v-for="score in 5" :key="score" :class="{ selected: rating === score }" @tap="rating = score">{{ score }}</button></view>
            <textarea v-model="comment" maxlength="1000" placeholder="写下参加活动后的感受" />
            <text v-if="feedbackError" class="feedback-error">{{ feedbackError }}</text>
            <button class="ui-button ui-button--primary" :disabled="feedbackBusy" @tap="saveFeedback">{{ feedbackBusy ? '保存中...' : feedback ? '更新评价' : '提交评价' }}</button>
          </template>
        </view>
      </view>
      <view class="actions">
        <button v-if="activeRegistration && activity.status === 'PUBLISHED' && !registration.checkedAt && Date.now() < Date.parse(activity.startsAt)" class="ui-button ui-button--secondary" :disabled="pending" @tap="changeRegistration">{{ pending ? '处理中...' : '取消报名' }}</button>
        <button v-else-if="activeRegistration" class="ui-button ui-button--secondary" disabled>{{ registration.checkedAt ? '已签到' : '已报名' }}</button>
        <button v-else class="ui-button ui-button--primary" :disabled="pending || label !== '可报名'" @tap="changeRegistration">{{ pending ? '处理中...' : label === '可报名' ? '报名活动' : label }}</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.detail-page { min-height: 100vh; padding-bottom: 105px; background: #f3f6f4; color: #1d302e; }
.cover { width: 100%; height: 225px; background: #e4eae6; }
.content { padding: 0 18px 28px; }
.heading { margin: 0 -18px; padding: 22px 18px 25px; background: #fff; }
.title { display: block; margin-top: 13px; font-size: 24px; font-weight: 700; line-height: 1.36; word-break: break-all; }
.summary { display: block; margin-top: 10px; color: #61716e; font-size: 14px; line-height: 1.6; }
.cancellation-note { display: block; margin-top: 12px; padding: 11px; border-left: 3px solid #ad5747; border-radius: 4px; background: #fff3f0; color: #934539; font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.facts { margin-top: 17px; padding: 4px 16px; border-radius: 8px; background: #fff; }
.fact { display: flex; align-items: flex-start; padding: 14px 0; border-bottom: 1px solid #edf1ee; }
.fact:last-child { border-bottom: 0; }
.fact image { width: 20px; height: 20px; margin: 2px 13px 0 0; flex-shrink: 0; }
.fact>view { flex: 1; min-width: 0; }
.fact-key { display: block; margin-bottom: 4px; color: #7a8983; font-size: 12px; }
.fact-value { display: block; color: #233a34; font-size: 14px; line-height: 1.5; word-break: break-all; }
.section-heading { margin: 28px 0 13px; font-size: 18px; font-weight: 700; }
.map-panel { overflow: hidden; border-radius: 8px; background: #fff; }
.map { display: block; width: 100%; height: 195px; }
.map-button { display: flex; align-items: center; justify-content: center; height: 44px; margin: 0; border-radius: 0; background: #fff; color: #147f74; font-size: 14px; font-weight: 600; }
.map-button image { width: 17px; height: 17px; margin-right: 6px; }
.article { display: block; padding: 18px; border-radius: 8px; background: #fff; color: #40534b; font-size: 14px; line-height: 1.8; white-space: pre-wrap; word-break: break-all; }
.actions { position: fixed; right: 0; bottom: 0; left: 0; z-index: 3; padding: 11px 18px calc(11px + env(safe-area-inset-bottom)); border-top: 1px solid #e0e9e3; background: #fff; }
.actions button { width: 100%; }
.loading-state { padding: 18px; }.cover-skeleton { height: 215px; margin: -18px -18px 22px; }.text-skeleton { height: 22px; margin-bottom: 12px; }.short { width: 63%; }
.feedback-panel { margin-top: 24px; }.feedback-panel .section-heading { margin-bottom: 8px; }.feedback-note { display: block; margin-bottom: 12px; color: #6f8078; font-size: 12px; line-height: 1.5; }
.rating-choices { display: flex; gap: 8px; margin-bottom: 12px; }.rating-choices button { flex: 1; height: 38px; padding: 0; border-radius: 6px; background: #e4ece8; color: #315b50; font-size: 14px; }.rating-choices button.selected { background: #147f74; color: #fff; }
.feedback-panel textarea { box-sizing: border-box; width: 100%; height: 120px; margin-bottom: 12px; padding: 12px; border-radius: 8px; background: #fff; font-size: 13px; }.feedback-panel .ui-button { width: 100%; }.feedback-error { display: block; margin-bottom: 10px; color: #a34538; font-size: 12px; }
</style>
