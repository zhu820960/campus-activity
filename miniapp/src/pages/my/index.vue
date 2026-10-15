<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { api, downloadAvatarImage, downloadPersonalParticipation, isSignedIn, setToken, uploadAvatarImage } from '../../common/api'
import { formatTime } from '../../common/format'
import { searchCheckedRecords } from '../../common/participation'

const user = ref(null)
const records = ref([])
const activities = ref([])
const loading = ref(false)
const error = ref('')
const webPassword = ref('')
const activating = ref(false)
const activationError = ref('')
const webSetupOpen = ref(false)
const unreadCount = ref(0)
const appealAvailable = ref(false)
const avatarPath = ref('')
const avatarBusy = ref(false)
const checkedQuery = ref('')
const exportingPdf = ref(false)
const schedule = computed(() => records.value.filter(r => r.status === 'ACTIVE' &&
  !r.checkedAt && activities.value.some(a => a.id === r.activityId && Date.parse(a.endsAt) > Date.now())))
const checked = computed(() => records.value.filter(r => r.checkedAt))
const visibleChecked = computed(() => searchCheckedRecords(records.value, checkedQuery.value))
const activeCount = computed(() => records.value.filter(r => r.status === 'ACTIVE').length)
const initials = computed(() => (user.value?.name || '同学').slice(-1))
const hasStudentNumber = computed(() => /^[0-9]{14}$/.test(user.value?.studentNumber || ''))
function recordStatus(record) {
  if (record.status !== 'ACTIVE') return '已取消'
  if (record.checkedAt) return '已签到'
  const item = activities.value.find(a => a.id === record.activityId)
  return item?.endsAt && Date.parse(item.endsAt) <= Date.now() ? '已结束' : '已报名'
}

async function load() {
  if (!isSignedIn()) { user.value = null; avatarPath.value = ''; records.value = []; appealAvailable.value = false; error.value = ''; uni.stopPullDownRefresh(); return }
  loading.value = true; error.value = ''
  try {
    const [profile, mine, listed, appeals] = await Promise.all([
      api.me(), api.mine(), api.activities(),
      api.appealAvailability().catch(() => ({ enabled: false }))
    ])
    user.value = profile; records.value = mine; activities.value = listed
    appealAvailable.value = Boolean(appeals.enabled)
    avatarPath.value = user.value.hasAvatar ? await downloadAvatarImage().catch(() => '') : ''
    unreadCount.value = (await api.unreadMessages().catch(() => ({ count: 0 }))).count || 0
  }
  catch (e) { error.value = isSignedIn() ? e.message : ''; user.value = null; records.value = []; appealAvailable.value = false }
  finally { loading.value = false; uni.stopPullDownRefresh() }
}
async function logout() {
  let revoked = true
  try { await api.logout() } catch { revoked = false }
  setToken(''); user.value = null; avatarPath.value = ''; records.value = []; activities.value = []
  unreadCount.value = 0; appealAvailable.value = false
  if (!revoked) uni.showModal({ title: '已退出本机', content: '服务端撤销未确认。请检查网络后重新登录并退出。', showCancel: false })
}
function freshWechatCode() {
  return new Promise((resolve, reject) => uni.login({ provider: 'weixin',
    success: result => result.code ? resolve(result.code) : reject(new Error('微信身份确认失败')),
    fail: () => reject(new Error('微信身份确认失败')) }))
}
async function activateOrganizer() {
  if (activating.value) return
  if (!hasStudentNumber.value) {
    activationError.value = '当前学生账号缺少14位学号，请联系管理员核对'
    return
  }
  if (!/^[\x21-\x7E]{8,64}$/.test(webPassword.value) ||
      !/[a-z]/i.test(webPassword.value) || !/[0-9]/.test(webPassword.value)) {
    activationError.value = '密码须为8至64位英文字母、数字或符号，且包含字母和数字'
    return
  }
  activating.value = true; activationError.value = ''
  try {
    user.value = await api.activateWebCredentials(await freshWechatCode(), webPassword.value)
    webPassword.value = ''
    uni.showToast({ title: '组织者账号已启用', icon: 'success' })
  } catch (e) { activationError.value = e.message || '启用失败，请重试' }
  finally { activating.value = false }
}
function openRecord(record) { uni.navigateTo({ url: '/pages/detail/index?id=' + record.activityId }) }
function openLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
function openMessages() { uni.navigateTo({ url: '/pages/messages/index' }) }
function openAppeal() { uni.navigateTo({ url: '/pages/appeal/index' }) }
function openOrganizer() { uni.navigateTo({ url: '/pages/organizer/index' }) }
function exportPersonalPdf() {
  if (exportingPdf.value) return
  uni.showModal({ title: '导出个人记录', content: '文件仅供本人查阅，不作为学校或活动组织方出具的证明。',
    success: async result => {
      if (!result.confirm) return
      exportingPdf.value = true
      try {
        const filePath = await downloadPersonalParticipation(checkedQuery.value)
        uni.openDocument({ filePath, fileType: 'pdf', showMenu: true,
          fail: () => uni.showToast({ title: '文件已下载，但无法打开', icon: 'none' }) })
      } catch (cause) { uni.showToast({ title: cause.message || '导出失败', icon: 'none' }) }
      finally { exportingPdf.value = false }
    }
  })
}
function toggleWebSetup() {
  webSetupOpen.value = !webSetupOpen.value
  activationError.value = ''
  if (!webSetupOpen.value) webPassword.value = ''
}
async function saveAvatar(path) {
  if (!path || avatarBusy.value) return
  avatarBusy.value = true
  try {
    user.value = await uploadAvatarImage(path)
    avatarPath.value = await downloadAvatarImage()
    uni.showToast({ title: '头像已更新', icon: 'success' })
  } catch (e) { uni.showToast({ title: e.message || '头像更新失败', icon: 'none' }) }
  finally { avatarBusy.value = false }
}
function chooseWechatAvatar(event) { saveAvatar(event.detail?.avatarUrl) }
function chooseAlbumAvatar() {
  if (avatarBusy.value) return
  uni.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album'],
    success: result => saveAvatar(result.tempFiles?.[0]?.tempFilePath),
    fail: error => { if (!String(error?.errMsg || '').includes('cancel')) uni.showToast({ title: '选择图片失败', icon: 'none' }) }
  })
}
function removeAvatar() {
  if (avatarBusy.value || !user.value?.hasAvatar) return
  uni.showModal({ title: '恢复默认头像', content: '移除当前头像？',
    success: async result => {
      if (!result.confirm) return
      avatarBusy.value = true
      try {
        await api.removeAvatar()
        user.value = { ...user.value, hasAvatar: false }
        avatarPath.value = ''
      } catch (e) { uni.showToast({ title: e.message || '移除失败', icon: 'none' }) }
      finally { avatarBusy.value = false }
    }
  })
}
onShow(load)
onPullDownRefresh(load)
</script>

<template>
  <view class="ui-page my-page">
    <view class="identity">
      <view class="identity-copy"><text class="identity-eyebrow">{{ user?.roles?.includes('ORGANIZER') ? '学生 · 活动组织者' : '学生' }}</text><text class="identity-name">{{ user?.name || '你好，同学' }}</text><text class="identity-email">{{ user?.studentNumber ? `学号 ${user.studentNumber}` : (user ? '学号待补录' : '登录后查看你的活动记录') }}</text></view>
      <view class="avatar"><image v-if="avatarPath" :src="avatarPath" mode="aspectFill" /><text v-else>{{ initials }}</text></view>
    </view>

    <view v-if="loading" class="loading-state"><view class="ui-skeleton loading-strip" /><view class="ui-skeleton loading-card" /><view class="ui-skeleton loading-card" /></view>
    <view v-else-if="error" class="ui-feedback ui-feedback--error error-state" @tap="load">{{ error }}，点击重试</view>
    <template v-else-if="!user">
      <view class="ui-empty guest-message"><text class="ui-empty-title">登录后查看个人记录</text><text>报名、日程和签到记录都在这里</text></view>
      <button class="ui-button ui-button--primary login-button" @tap="openLogin">微信登录并绑定账号</button>
    </template>
    <template v-else>
      <view class="avatar-actions">
        <button class="avatar-action" open-type="chooseAvatar" :disabled="avatarBusy" @chooseavatar="chooseWechatAvatar">微信头像</button>
        <button class="avatar-action" :disabled="avatarBusy" @tap="chooseAlbumAvatar">从相册选取</button>
        <button v-if="user.hasAvatar" class="avatar-action avatar-action--muted" :disabled="avatarBusy" @tap="removeAvatar">恢复默认</button>
      </view>
      <view class="stat-strip"><view><text class="stat-num">{{ activeCount }}</text><text class="stat-label">有效报名</text></view><view><text class="stat-num">{{ schedule.length }}</text><text class="stat-label">待参加</text></view><view><text class="stat-num">{{ checked.length }}</text><text class="stat-label">已签到</text></view></view>
      <button class="message-entry" @tap="openMessages"><image src="/static/icons/bell.png" mode="aspectFit" /><text>消息中心</text><text v-if="unreadCount" class="message-badge">{{ unreadCount }} 条未读</text><image src="/static/icons/next.png" mode="aspectFit" /></button>
      <button v-if="appealAvailable" class="message-entry" @tap="openAppeal"><image src="/static/icons/shield.png" mode="aspectFit" /><text>账号申诉</text><image src="/static/icons/next.png" mode="aspectFit" /></button>

      <button v-if="user.roles?.includes('ORGANIZER') && !user.roles?.includes('ADMIN')" class="message-entry" @tap="openOrganizer"><image src="/static/icons/calendar.png" mode="aspectFit" /><text>我组织的活动</text><image src="/static/icons/next.png" mode="aspectFit" /></button>
      <button v-if="user.roles?.includes('ORGANIZER') && !user.roles?.includes('ADMIN')" class="message-entry" @tap="toggleWebSetup"><image src="/static/icons/shield.png" mode="aspectFit" /><text>Web 登录设置</text><text class="web-state">{{ !hasStudentNumber ? '学号待补录' : (user.webCredentialsActive ? '已启用' : '未启用') }}</text><image src="/static/icons/next.png" mode="aspectFit" /></button>
      <view v-if="user.roles?.includes('ORGANIZER') && !user.roles?.includes('ADMIN') && webSetupOpen" class="organizer-access">
        <text class="ui-section-title">组织者 Web 工作台</text>
        <text class="organizer-note">登录学号：{{ user.studentNumber || '待补录' }}</text>
        <template v-if="!user.webCredentialsActive && hasStudentNumber">
          <input v-model="webPassword" type="password" maxlength="64" placeholder="设置 Web 登录密码（至少8位）" />
          <text v-if="activationError" class="activation-error">{{ activationError }}</text>
          <button class="ui-button ui-button--primary" :disabled="activating" @tap="activateOrganizer">{{ activating ? '正在确认...' : '设置密码' }}</button>
        </template>
        <text v-else-if="!hasStudentNumber" class="activation-error">当前账号缺少14位学号，暂不能使用学号登录。请联系管理员核对；原活动记录不受影响。</text>
      </view>

      <view class="record-section">
        <view class="section-head"><text class="ui-section-title">我的报名</text><text class="section-count">{{ records.length }} 条记录</text></view>
        <view v-if="!records.length" class="ui-empty"><text class="ui-empty-title">暂无报名记录</text><text>去活动列表看看吧</text></view>
        <view v-for="record in records" :key="record.id" class="record" @tap="openRecord(record)">
          <text class="record-name">{{ record.activityTitle }}</text>
          <view class="record-bottom"><text>{{ formatTime(record.registeredAt) }}</text><view class="record-end"><text class="record-status" :class="{ cancelled: recordStatus(record) === '已取消' || recordStatus(record) === '已结束' }">{{ recordStatus(record) }}</text><image src="/static/icons/next.png" mode="aspectFit" /></view></view>
        </view>
      </view>

      <view class="record-section">
        <view class="section-head"><text class="ui-section-title">我的活动日程</text><text class="section-count">{{ schedule.length }} 场待参加</text></view>
        <view v-if="!schedule.length" class="ui-empty"><text class="ui-empty-title">暂无待参加活动</text></view>
        <view v-for="record in schedule" :key="record.id" class="record compact-record" @tap="openRecord(record)"><image src="/static/icons/calendar.png" mode="aspectFit" /><text class="record-name">{{ record.activityTitle }}</text><image src="/static/icons/next.png" mode="aspectFit" /></view>
      </view>

      <view class="record-section">
        <view class="section-head"><text class="ui-section-title">签到记录</text><text class="section-count">{{ checked.length }} 条</text></view>
        <view v-if="checked.length" class="participation-search"><image src="/static/icons/search.png" mode="aspectFit" /><input v-model="checkedQuery" type="text" placeholder="搜索已签到活动" confirm-type="search" /><button v-if="checkedQuery" aria-label="清除搜索" @tap="checkedQuery = '''"><image class="clear-search-icon" src="/static/icons/close.png" mode="aspectFit" /></button></view>
        <button v-if="checked.length" class="avatar-action participation-export" :disabled="exportingPdf || !visibleChecked.length" @tap="exportPersonalPdf">{{ exportingPdf ? '正在生成...' : '导出个人记录 PDF' }}</button>
        <view v-if="!checked.length" class="ui-empty"><text class="ui-empty-title">暂无签到记录</text></view>
        <view v-else-if="!visibleChecked.length" class="ui-empty"><text class="ui-empty-title">没有找到已签到活动</text></view>
        <view v-for="record in visibleChecked" :key="record.id" class="record" @tap="openRecord(record)"><text class="record-name">{{ record.activityTitle }}</text><text class="checkin-time">签到于 {{ formatTime(record.checkedAt) }}</text></view>
      </view>
      <button class="logout" @tap="logout"><image src="/static/icons/logout.png" mode="aspectFit" />退出登录</button>
    </template>
  </view>
</template>

<style scoped>
.my-page { padding-top: 18px; padding-bottom: 60px; }
.identity { display: flex; align-items: center; justify-content: space-between; min-height: 125px; padding: 23px 19px; border-radius: 8px; background: #173f3b; color: #fff; box-sizing: border-box; }
.identity-copy { flex: 1; min-width: 0; padding-right: 14px; }
.identity-eyebrow { display: block; margin-bottom: 11px; color: #b4dbcd; font-size: 12px; }
.identity-name { display: block; font-size: 21px; font-weight: 700; line-height: 1.35; word-break: break-all; }
.identity-email { display: block; margin-top: 6px; color: #d1e5dc; font-size: 12px; word-break: break-all; }
.avatar { display: flex; align-items: center; justify-content: center; width: 52px; height: 52px; flex-shrink: 0; border: 1px solid #97c8b7; border-radius: 50%; background: #285e55; color: #fff; font-size: 20px; font-weight: 700; }
.avatar image { width: 100%; height: 100%; border-radius: 50%; }
.avatar-actions { display: flex; gap: 8px; margin-top: 10px; }
.avatar-action { flex: 1; min-width: 0; height: 35px; margin: 0; padding: 0 4px; border: 1px solid #dce6df; border-radius: 6px; background: #fff; color: #176550; font-size: 11px; line-height: 33px; }
.avatar-action--muted { color: #73827a; }
.avatar-action::after { border: 0; }
.stat-strip { display: flex; margin-top: 16px; padding: 15px 0; border-radius: 8px; background: #fff; }
.stat-strip>view { display: flex; flex: 1; flex-direction: column; align-items: center; border-right: 1px solid #edf1ee; }.stat-strip>view:last-child { border: 0; }
.stat-num { color: #173f3b; font-size: 22px; font-weight: 700; }.stat-label { margin-top: 3px; color: #75837c; font-size: 11px; }
.record-section { margin-top: 28px; }.section-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 13px; }.section-count { color: #778680; font-size: 12px; }
.record { margin-bottom: 9px; padding: 15px 16px; border-radius: 8px; background: #fff; }
.record-name { display: block; flex: 1; color: #233a34; font-size: 14px; font-weight: 600; line-height: 1.45; word-break: normal; }
.record-status { flex-shrink: 0; padding: 4px 7px; border-radius: 4px; background: #e5f2ed; color: #176550; font-size: 11px; }.record-status.cancelled { background: #eef0ee; color: #74817a; }
.record-bottom { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; color: #85918c; font-size: 11px; }.record-end { display: flex; align-items: center; gap: 7px; }.record-bottom image { width: 16px; height: 16px; }
.compact-record { display: flex; align-items: center; gap: 11px; }.compact-record image { width: 18px; height: 18px; flex-shrink: 0; }.compact-record image:last-child { width: 16px; height: 16px; }
.checkin-time { display: block; margin-top: 8px; color: #778680; font-size: 12px; }
.participation-search { display: flex; align-items: center; height: 44px; margin-bottom: 12px; padding: 0 11px; border: 1px solid #dce6df; border-radius: 6px; background: #fff; }
.participation-search image { width: 17px; height: 17px; margin-right: 9px; }
.participation-search input { flex: 1; min-width: 0; height: 100%; color: #233a34; font-size: 13px; }
.participation-search button { width: 28px; height: 28px; margin: 0; padding: 0; background: transparent; color: #64766e; font-size: 22px; line-height: 26px; }
.participation-search button::after { border: 0; }
.participation-export { width: 100%; height: 40px; margin: 0 0 12px; font-size: 12px; line-height: 38px; }
.participation-export[disabled] { color: #91a29a; }
.logout { display: flex; align-items: center; justify-content: center; height: 47px; margin-top: 28px; border: 1px solid #dce6df; border-radius: 8px; background: #fff; color: #a34538; font-size: 14px; }.logout image { width: 17px; height: 17px; margin-right: 7px; }
.guest-message { margin-top: 30px; }.login-button { width: 100%; margin-top: 12px; }.error-state { margin-top: 20px; }
.loading-state { margin-top: 17px; }.loading-strip { height: 75px; margin-bottom: 24px; }.loading-card { height: 80px; margin-bottom: 10px; }
.message-entry { display: flex; align-items: center; width: 100%; height: 54px; margin-top: 16px; padding: 0 15px; border-radius: 8px; background: #fff; color: #233a34; font-size: 14px; text-align: left; }
.message-entry image { width: 18px; height: 18px; margin-right: 11px; }.message-entry image:last-child { width: 15px; height: 15px; margin: 0 0 0 8px; }
.message-entry text:nth-child(2) { flex: 1; }.message-badge { color: #147f74; font-size: 11px; }
.web-state { color: #73827a; font-size: 11px; }
.organizer-access { margin-top: 22px; padding: 18px; border-radius: 8px; background: #fff; }
.organizer-note { display: block; margin: 10px 0 14px; color: #64766e; font-size: 12px; line-height: 1.5; }
.organizer-access input { box-sizing: border-box; height: 43px; margin-top: 10px; padding: 0 11px; border: 1px solid #dfe7e2; border-radius: 6px; font-size: 14px; }
.organizer-access button { width: 100%; margin-top: 14px; }
.activation-error { display: block; margin-top: 10px; color: #a34538; font-size: 12px; }
.participation-search .clear-search-icon { width:16px; height:16px; margin:0; }
</style>
