<script setup>
import { ref } from 'vue'
import { api, requireSignIn } from '../../common/api'
import { parseCheckinCode } from '../../common/scan'
import { formatTime } from '../../common/format'

const parsed = ref(null)
const activity = ref(null)
const busy = ref(false)
const error = ref('')
const success = ref(false)

async function scan() {
  if (!requireSignIn() || busy.value) return
  parsed.value = null; activity.value = null; error.value = ''; success.value = false
  try {
    const result = await uni.scanCode({ onlyFromCamera: true, scanType: ['qrCode'] })
    const code = parseCheckinCode(result.result)
    busy.value = true
    const detail = await api.activity(code.activityId)
    parsed.value = code
    activity.value = detail
  } catch (e) {
    if (!/cancel/i.test(e.errMsg || '')) error.value = e.message || '扫码失败，请检查摄像头权限'
  } finally { busy.value = false }
}

async function confirm() {
  if (!parsed.value || !activity.value || busy.value || !requireSignIn()) return
  busy.value = true; error.value = ''
  try {
    await api.checkin(parsed.value.activityId, parsed.value.token)
    success.value = true
    parsed.value = null
  } catch (e) {
    error.value = e.message
    parsed.value = null
  } finally { busy.value = false }
}
</script>

<template>
  <view class="ui-page scan-page">
    <text class="eyebrow">现场签到</text>
    <text class="ui-title">动态签到</text>
    <text class="ui-subtitle intro">请在活动现场扫描组织者或管理员展示的二维码</text>
    <view class="scan-visual"><view class="scan-frame"><image src="/static/icons/scan.png" mode="aspectFit" /></view><text>将现场二维码对准摄像头</text></view>
    <button class="ui-button ui-button--primary scan-button" :disabled="busy" @tap="scan">{{ busy ? '处理中...' : '扫描活动二维码' }}</button>
    <view v-if="error" class="ui-feedback ui-feedback--error scan-result">{{ error }}</view>
    <view v-if="success" class="ui-feedback ui-feedback--success scan-result"><image src="/static/icons/check.png" mode="aspectFit" /><text>签到成功，可在“我的”查看记录</text></view>
    <view v-if="activity && parsed" class="confirm">
      <text class="confirm-eyebrow">请核对活动</text>
      <text class="confirm-title">{{ activity.title }}</text>
      <view class="confirm-row"><image src="/static/icons/calendar.png" mode="aspectFit" /><text>{{ formatTime(activity.startsAt) }}</text></view>
      <view class="confirm-row"><image src="/static/icons/pin.png" mode="aspectFit" /><text>{{ activity.location || '地点待定' }}</text></view>
      <text class="warning">签到码仅在服务器规定时间内有效</text>
      <button class="ui-button ui-button--primary confirm-button" :disabled="busy" @tap="confirm">{{ busy ? '处理中...' : '确认签到' }}</button>
    </view>
  </view>
</template>

<style scoped>
.scan-page { padding-top: 29px; }
.eyebrow { display: block; margin-bottom: 6px; color: #147f74; font-size: 12px; font-weight: 700; }.intro { margin-top: 9px; }
.scan-visual { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 230px; margin-top: 27px; border: 1px solid #deebe4; border-radius: 8px; background: #eaf3ed; color: #667a6f; font-size: 13px; }
.scan-frame { display: flex; align-items: center; justify-content: center; width: 103px; height: 103px; margin-bottom: 16px; border: 2px solid #4c9b8c; border-radius: 8px; background: #fff; }.scan-frame image { width: 59px; height: 59px; }
.scan-button { width: 100%; margin-top: 22px; }.scan-result { display: flex; align-items: center; margin-top: 18px; }.scan-result image { width: 21px; height: 21px; margin-right: 9px; }
.confirm { margin-top: 18px; padding: 19px; border-radius: 8px; background: #fff; }.confirm-eyebrow { display: block; color: #147f74; font-size: 12px; font-weight: 700; }.confirm-title { display: block; margin: 8px 0 18px; font-size: 18px; font-weight: 700; line-height: 1.4; word-break: break-all; }
.confirm-row { display: flex; align-items: flex-start; gap: 9px; margin-top: 10px; color: #5b6f64; font-size: 13px; line-height: 1.5; }.confirm-row image { width: 17px; height: 17px; flex-shrink: 0; }
.warning { display: block; margin-top: 18px; color: #8a5c24; font-size: 12px; }.confirm-button { width: 100%; margin-top: 17px; }
</style>
