<script setup>
import { ref } from 'vue'
import { api, uploadAppealEvidence } from '../../common/api'
import { validStudentNumber } from '../../common/studentIdentity'

const type = ref('STUDENT_NUMBER_OCCUPIED')
const studentNumber = ref('')
const reason = ref('')
const photo = ref('')
const busy = ref(false)
const submitted = ref(false)
const latest = ref(null)
const error = ref('')

function wxCode() {
  return new Promise((resolve, reject) => uni.login({ provider: 'weixin',
    success: result => result.code ? resolve(result.code) : reject(new Error('微信登录未返回凭证')),
    fail: () => reject(new Error('无法获取微信登录凭证')) }))
}

function choosePhoto() {
  uni.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'],
    success: result => { photo.value = result.tempFilePaths?.[0] || ''; error.value = '' },
    fail: result => { if (!String(result.errMsg || '').includes('cancel')) error.value = '无法选择照片，请重试' }
  })
}

function goBack() {
  uni.navigateBack()
}

async function submit() {
  if (busy.value) return
  if (!validStudentNumber(studentNumber.value)) { error.value = '目标学号须为14位数字'; return }
  if (!reason.value.trim()) { error.value = '请填写申诉原因'; return }
  if (!photo.value) { error.value = '请上传学生证正面照片'; return }
  busy.value = true; error.value = ''
  try {
    const challenge = await api.appealChallenge(await wxCode())
    await uploadAppealEvidence(challenge.token, photo.value)
    await api.submitAppeal(challenge.token, type.value, studentNumber.value, reason.value.trim())
    submitted.value = true
    latest.value = null
    photo.value = ''
  } catch (cause) { error.value = cause.message || '提交失败，请稍后再试' }
  finally { busy.value = false }
}

async function checkStatus() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { latest.value = await api.appealStatus(await wxCode()) }
  catch (cause) { error.value = cause.message || '暂时无法查询申诉进度' }
  finally { busy.value = false }
}
</script>

<template>
  <view class="ui-page appeal-page">
    <text class="ui-title">账号申诉</text>
    <button class="status-link" :disabled="busy" @tap="checkStatus">查看最近一次申诉进度</button>
    <view v-if="latest" class="status-result">
      <text>{{ latest.status === 'PENDING' ? '待管理员审核' : latest.status === 'REJECTED' ? '已驳回' : latest.status === 'APPROVED' ? '已通过' : '归属待核实' }}</text>
      <text>目标学号 {{ latest.proposedStudentNumber }}</text>
      <text v-if="latest.reviewNote">审核意见：{{ latest.reviewNote }}</text>
    </view>
    <view v-if="submitted" class="result">
      <text class="result-title">申诉已提交</text>
      <text class="result-copy">管理员将核对学生证与学号。审核前不会改动现有绑定或参与记录。</text>
      <button class="ui-button ui-button--primary" @tap="goBack">返回</button>
    </view>
    <template v-else>
      <view v-if="error" class="ui-feedback ui-feedback--error appeal-error">{{ error }}</view>
      <view class="type-switch" aria-label="申诉类型">
        <button :class="{ active: type === 'STUDENT_NUMBER_OCCUPIED' }" :disabled="busy" @tap="type = 'STUDENT_NUMBER_OCCUPIED'">学号被占用</button>
        <button :class="{ active: type === 'WRONG_STUDENT_NUMBER' }" :disabled="busy" @tap="type = 'WRONG_STUDENT_NUMBER'">绑错学号</button>
      </view>
      <view class="appeal-form">
        <label class="field"><text>需要绑定的学号</text><input v-model="studentNumber" type="number" maxlength="14" placeholder="本人14位学号" /></label>
        <label class="field"><text>申诉原因</text><textarea v-model="reason" maxlength="1000" placeholder="说明当前情况，供管理员核对" /></label>
        <view class="field"><text>学生证正面</text><button class="photo-button" :disabled="busy" @tap="choosePhoto">{{ photo ? '更换照片' : '选择照片' }}</button><image v-if="photo" class="photo-preview" :src="photo" mode="aspectFit" /></view>
        <text class="privacy">照片仅供管理员审核，不会公开展示；结案30天后删除。请确保证件上姓名和学号清晰可见。</text>
        <button class="ui-button ui-button--primary submit-button" :disabled="busy" @tap="submit">{{ busy ? '正在提交...' : '提交申诉' }}</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.appeal-page { padding-top: 30px; padding-bottom: 42px; }
.appeal-error { margin: 18px 0; }
.status-link { width: auto; margin: 12px 0 0; padding: 0; border: 0; background: transparent; color: #147f74; font-size: 13px; text-align: left; line-height: 1.5; }
.status-link::after { border: 0; }
.status-result { display: grid; gap: 5px; margin-top: 16px; padding: 12px; border-left: 3px solid #17867a; background: #fff; color: #496359; font-size: 12px; line-height: 1.55; }
.type-switch { display: flex; gap: 0; margin: 28px 0 20px; padding: 3px; border-radius: 7px; background: #e7eeeb; }
.type-switch button { flex: 1; min-width: 0; height: 39px; padding: 0 5px; border: 0; border-radius: 5px; background: transparent; color: #536962; font-size: 13px; line-height: 39px; }
.type-switch button::after { border: 0; }
.type-switch button.active { background: #fff; color: #173e3a; font-weight: 700; }
.appeal-form { padding: 18px; border-radius: 8px; background: #fff; }
.field { display: block; margin-bottom: 20px; }
.field text { display: block; margin-bottom: 8px; color: #4c6258; font-size: 13px; font-weight: 600; }
.field input, .field textarea { box-sizing: border-box; width: 100%; padding: 11px; border: 1px solid #dce6e0; border-radius: 6px; color: #1d302e; font-size: 14px; }
.field input { height: 44px; }
.field textarea { height: 104px; }
.photo-button { width: 100%; height: 44px; border: 1px solid #a9ccc2; border-radius: 6px; background: #eef7f4; color: #166f66; font-size: 14px; line-height: 42px; }
.photo-button::after { border: 0; }
.photo-preview { width: 100%; height: 150px; margin-top: 10px; border-radius: 6px; background: #f3f6f4; }
.privacy { display: block; color: #667a72; font-size: 12px; line-height: 1.6; }
.submit-button { width: 100%; margin-top: 24px; }
.result { margin-top: 28px; padding: 22px; border-radius: 8px; background: #fff; }
.result-title, .result-copy { display: block; }
.result-title { color: #183d37; font-size: 19px; font-weight: 700; }
.result-copy { margin: 12px 0 22px; color: #566b62; font-size: 13px; line-height: 1.7; }
</style>
