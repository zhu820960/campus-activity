<script setup>
import { ref } from 'vue'
import { api, setToken } from '../../common/api'
import { validStudentNumber, cleanRealName } from '../../common/studentIdentity'

const studentNumber = ref('')
const name = ref('')
const needsBinding = ref(false)
const needsAppeal = ref(false)
const appealAvailable = ref(false)
const busy = ref(false)
const error = ref('')

function wxCode() {
  return new Promise((resolve, reject) => {
    uni.login({ provider: 'weixin', success: result => result.code ? resolve(result.code) : reject(new Error('微信登录未返回凭证')),
      fail: () => reject(new Error('无法获取微信登录凭证')) })
  })
}

async function checkAppealAvailability() {
  try { appealAvailable.value = Boolean((await api.appealAvailability()).enabled) }
  catch { appealAvailable.value = false }
}

function openAppeal() {
  uni.navigateTo({ url: '/pages/appeal/index' })
}

async function handleConflict(e) {
  if (e.status !== 409) return
  if (String(e.message).includes('解绑记录')) {
    needsAppeal.value = true
    needsBinding.value = false
  } else if (String(e.message).includes('尚未绑定')) {
    needsBinding.value = true
  }
  await checkAppealAvailability()
}

async function login() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const code = await wxCode()
    const result = await api.login(code)
    if (!result?.token) throw new Error('认证服务未返回登录凭证')
    setToken(result.token)
    uni.switchTab({ url: '/pages/index/index' })
  } catch (e) {
    if (!(e.status === 409 && e.message.includes('尚未绑定'))) error.value = e.message || '微信登录失败'
    await handleConflict(e)
  }
  finally { busy.value = false }
}

async function bindStudent() {
  if (busy.value || needsAppeal.value) return
  if (!validStudentNumber(studentNumber.value)) { error.value = '学号须为14位数字'; return }
  const realName = cleanRealName(name.value)
  if (!realName) { error.value = '请填写有效的真实姓名'; return }
  busy.value = true; error.value = ''
  try {
    // Each code2Session exchange consumes a fresh code; the login attempt's code is never reused.
    const result = await api.bindStudent(await wxCode(), studentNumber.value, realName)
    if (!result?.token) throw new Error('认证服务未返回登录凭证')
    setToken(result.token)
    uni.switchTab({ url: '/pages/index/index' })
  } catch (e) {
    error.value = e.message || '绑定失败，请重试'
    await handleConflict(e)
  }
  finally { busy.value = false }
}
</script>

<template>
  <view class="ui-page login-page">
    <view class="top-symbol"><image src="/static/icons/shield.png" mode="aspectFit" /></view>
    <text class="ui-title">学生身份认证</text>
    <text class="ui-subtitle intro">使用微信登录。首次使用时登记本人学号和姓名。</text>
    <view v-if="error" class="ui-feedback ui-feedback--error login-error">{{ error }}</view>
    <view v-if="needsAppeal" class="bind-panel">
      <text class="panel-title">解绑后需核对身份</text>
      <text class="binding-rule">此微信曾被解除绑定，请提交账号申诉，由管理员核对后恢复绑定。原报名与签到记录保留。</text>
      <button v-if="appealAvailable" class="ui-button ui-button--primary bind-button" :disabled="busy" @tap="openAppeal">提交账号申诉 / 查询进度</button>
      <text v-else class="binding-rule">申诉入口暂不可用，请联系管理员核对。</text>
      <button class="ui-button ui-button--secondary bind-button" :disabled="busy" @tap="login">{{ busy ? '请稍候...' : '审核通过后重新登录' }}</button>
    </view>
    <view v-else-if="!needsBinding" class="returning"><button class="ui-button ui-button--primary" :disabled="busy" @tap="login">{{ busy ? '请稍候...' : '微信登录' }}</button></view>
    <view v-else class="bind-panel">
      <text class="panel-title">登记学生身份</text>
      <view class="field"><text>14位学号</text><input v-model="studentNumber" type="number" maxlength="14" placeholder="请输入本人学号" /></view>
      <view class="field"><text>真实姓名</text><input v-model="name" type="text" maxlength="80" placeholder="请输入本人姓名" /></view>
      <text class="binding-rule">一个微信账号只能绑定一个学号，同一学号也只能绑定一个微信账号。请填写本人真实信息，绑定后如需更正，请联系管理员。</text>
      <button class="ui-button ui-button--primary bind-button" :disabled="busy" @tap="bindStudent">{{ busy ? '正在绑定...' : '确认绑定' }}</button>
    </view>
    <button v-if="appealAvailable && !needsAppeal" class="appeal-link" type="default" @tap="openAppeal">学号被占用或绑定有误？提交申诉</button>
    <text class="privacy-note">学号和姓名由本人填写，尚未经过学校学籍核验</text>
  </view>
</template>

<style scoped>
.login-page { padding-top: 40px; }
.top-symbol { display: flex; align-items: center; justify-content: center; width: 57px; height: 57px; margin-bottom: 24px; border-radius: 8px; background: #dcefeb; }.top-symbol image { width: 30px; height: 30px; }
.intro { max-width: 310px; margin-top: 10px; line-height: 1.65; }
.returning { margin-top: 35px; }.section-label { display: block; margin-bottom: 12px; color: #576c64; font-size: 13px; font-weight: 600; }.returning button { width: 100%; }
.divider { display: flex; align-items: center; gap: 12px; margin: 29px 0 22px; }.divider view { flex: 1; height: 1px; background: #dfe7e2; }.divider text { color: #84918c; font-size: 12px; }
.bind-panel { padding: 19px; border-radius: 8px; background: #fff; }.panel-title { display: block; font-size: 17px; font-weight: 700; }.panel-hint { display: block; margin-top: 4px; color: #778680; font-size: 12px; }
.field { margin-top: 19px; }.field text { display: block; margin-bottom: 8px; color: #4c6258; font-size: 12px; }.field input { height: 43px; padding: 0 11px; border: 1px solid #dfe7e2; border-radius: 6px; color: #1d302e; font-size: 14px; }
.bind-button { width: 100%; margin-top: 23px; }.privacy-note { display: block; margin-top: 20px; color: #899690; font-size: 11px; text-align: center; }.login-error { margin-top: 22px; }
.binding-rule { display: block; margin-top: 19px; color: #64766e; font-size: 12px; line-height: 1.6; }
.appeal-link { margin-top: 18px; padding: 0; border: 0; background: transparent; color: #147f74; font-size: 13px; text-align: left; }.appeal-link::after { border: 0; }
</style>
