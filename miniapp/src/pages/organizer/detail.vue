<script setup>
import { computed, ref } from 'vue'
import { onHide, onLoad, onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import QRCode from 'qrcode'
import { api, downloadOrganizerRoster } from '../../common/api'
import { formatTime } from '../../common/format'
import { buildCheckinCode } from '../../common/scan'

const id = ref(0)
const activity = ref(null)
const stats = ref(null)
const rows = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const manualId = ref(null)
const manualReason = ref('')
const qrVisible = ref(false)
const qrExpiresAt = ref(0)
const clock = ref(Date.now())
const qrBusy = ref(false)
const feedbackOpen = ref(false)
const feedbackBusy = ref(false)
const feedbackError = ref('')
const feedbackSummary = ref(null)
const feedbackRows = ref([])
const reviewsOpen = ref(false)
const reviewsBusy = ref(false)
const reviewsLoaded = ref(false)
const reviewsError = ref('')
const reviewRows = ref([])
const cancellation = ref(null)
const pendingChange = ref(null)
const cancellationReason = ref('')
const cancellationBusy = ref(false)
const exportBusy = ref('')
let timer
const editable = computed(() => ['DRAFT', 'REJECTED'].includes(activity.value?.status))
const canIssue = computed(() => activity.value?.status === 'PUBLISHED'
  && clock.value >= Date.parse(activity.value.checkinOpensAt)
  && clock.value <= Date.parse(activity.value.checkinClosesAt))
const qrSeconds = computed(() => Math.max(0, Math.ceil((qrExpiresAt.value - clock.value) / 1000)))
const statusText = { DRAFT: '草稿', PENDING: '待审核', REJECTED: '已驳回', PUBLISHED: '已发布', CANCELLED: '已取消' }

onLoad(options => {
  id.value = Number(options.id)
  load()
})
onShow(() => {
  if (id.value && !loading.value) load()
  clearInterval(timer)
  timer = setInterval(() => {
    clock.value = Date.now()
    if (qrVisible.value && (!canIssue.value || qrSeconds.value === 0)) {
      qrVisible.value = false
      if (canIssue.value) refreshQr()
    }
  }, 1000)
})
onHide(() => { clearInterval(timer); qrVisible.value = false })
onUnload(() => clearInterval(timer))
onPullDownRefresh(load)

async function load() {
  if (!Number.isSafeInteger(id.value) || id.value < 1) { error.value = '活动编号无效'; return }
  loading.value = true; error.value = ''
  try {
    activity.value = await api.organizerActivity(id.value)
    const [nextStats, nextRows] = await Promise.all([
      api.organizerStats(id.value), api.organizerRegistrations(id.value)
    ])
    stats.value = nextStats; rows.value = nextRows
    if (activity.value.status === 'PUBLISHED') {
      [cancellation.value, pendingChange.value] = await Promise.all([
        api.organizerCancellation(id.value).catch(e => { if (e.status === 404) return null; throw e }),
        api.organizerPendingChange(id.value).catch(e => { if (e.status === 404) return null; throw e })
      ])
    } else { cancellation.value = null; pendingChange.value = null }
    clock.value = Date.now()
  } catch (e) { error.value = e.message || '加载失败' }
  finally { loading.value = false; uni.stopPullDownRefresh() }
}
async function refreshQr() {
  if (!canIssue.value || qrBusy.value) return
  qrBusy.value = true; error.value = ''; qrVisible.value = false
  try {
    const issued = await api.organizerToken(id.value)
    const qr = QRCode.create(buildCheckinCode(id.value, issued.token), { errorCorrectionLevel: 'M' })
    const size = qr.modules.size
    const scale = 252 / (size + 8)
    const context = uni.createCanvasContext('organizerQr')
    context.setFillStyle('#ffffff')
    context.fillRect(0, 0, 252, 252)
    context.setFillStyle('#173f3b')
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (qr.modules.data[y * size + x]) context.fillRect((x + 4) * scale, (y + 4) * scale, scale + 0.2, scale + 0.2)
      }
    }
    context.draw(false, () => { qrVisible.value = true })
    qrExpiresAt.value = Date.parse(issued.expiresAt)
  } catch (e) { error.value = e.message || '签到码生成失败' }
  finally { qrBusy.value = false }
}
function edit() { uni.navigateTo({ url: `/pages/organizer/edit?id=${id.value}` }) }
function submit() {
  if (busy.value) return
  uni.showModal({ title: '提交管理员审核', content: '提交后需等待管理员审核，期间不能编辑。',
    success: async result => {
      if (!result.confirm) return
      busy.value = true; error.value = ''
      try { await api.organizerSubmit(id.value); await load(); uni.showToast({ title: '已提交审核' }) }
      catch (e) { error.value = e.message || '提交失败' }
      finally { busy.value = false }
    } })
}
async function manual(row) {
  if (!manualReason.value.trim() || busy.value) return
  busy.value = true; error.value = ''
  try {
    await api.organizerManualCheckin(id.value, row.studentId, manualReason.value.trim())
    manualId.value = null; manualReason.value = ''
    await load()
    uni.showToast({ title: '补签成功' })
  } catch (e) { error.value = e.message || '补签失败' }
  finally { busy.value = false }
}
async function toggleFeedback() {
  feedbackOpen.value = !feedbackOpen.value
  if (feedbackOpen.value && !feedbackSummary.value) await loadFeedback()
}
async function loadFeedback() {
  if (feedbackBusy.value) return
  feedbackBusy.value = true; feedbackError.value = ''
  try {
    const [summary, items] = await Promise.all([
      api.organizerFeedbackSummary(id.value), api.organizerFeedback(id.value)
    ])
    feedbackSummary.value = summary
    feedbackRows.value = items
  } catch (e) { feedbackError.value = e.message || '反馈加载失败' }
  finally { feedbackBusy.value = false }
}
async function toggleReviews() {
  reviewsOpen.value = !reviewsOpen.value
  if (reviewsOpen.value && !reviewsLoaded.value) await loadReviews()
}
async function loadReviews() {
  if (reviewsBusy.value) return
  reviewsBusy.value = true; reviewsError.value = ''
  try {
    reviewRows.value = await api.organizerReviews(id.value)
    reviewsLoaded.value = true
  } catch (e) { reviewsError.value = e.message || '审核历史加载失败' }
  finally { reviewsBusy.value = false }
}
async function requestCancellation() {
  const reason = cancellationReason.value.trim()
  if (!reason || cancellationBusy.value) return
  uni.showModal({ title: '申请取消活动', content: '管理员确认后才会正式取消，并通知已报名学生。',
    success: async result => {
      if (!result.confirm) return
      cancellationBusy.value = true; error.value = ''
      try {
        cancellation.value = await api.organizerRequestCancellation(id.value, reason)
        cancellationReason.value = ''
        uni.showToast({ title: '已提交申请' })
      } catch (e) { error.value = e.message || '提交取消申请失败' }
      finally { cancellationBusy.value = false }
    } })
}
async function exportRoster(kind) {
  if (exportBusy.value) return
  const confirmed = await new Promise(resolve => uni.showModal({
    title: '导出活动名单',
    content: '文件包含参与者姓名和学号，请仅用于活动管理并妥善保管。',
    success: result => resolve(result.confirm), fail: () => resolve(false)
  }))
  if (!confirmed) return
  exportBusy.value = kind; error.value = ''
  try {
    const filePath = await downloadOrganizerRoster(id.value, kind)
    await new Promise((resolve, reject) => uni.openDocument({
      filePath, fileType: 'xlsx', showMenu: true,
      success: resolve, fail: () => reject(new Error('名单已生成，但无法打开文件'))
    }))
  } catch (e) { error.value = e.message || '名单导出失败' }
  finally { exportBusy.value = '' }
}
</script>

<template>
  <view class="ui-page management-page">
    <view v-if="loading && !activity" class="ui-skeleton loading" />
    <view v-if="error" class="ui-feedback ui-feedback--error" @tap="load">{{ error }}</view>
    <view v-if="activity" class="activity-detail">
      <text class="status">{{ statusText[activity.status] || activity.status }}</text>
      <text class="heading">{{ activity.title }}</text>
      <text class="meta">{{ formatTime(activity.startsAt) }} · {{ activity.location }}</text>
      <text v-if="activity.reviewNote" class="review-note">审核意见：{{ activity.reviewNote }}</text>
      <text v-if="activity.status === 'CANCELLED'" class="review-note">取消原因：{{ activity.cancelReason || '未填写' }}</text>
      <view v-if="editable" class="actions"><button class="ui-button ui-button--secondary" @tap="edit">编辑活动</button><button class="ui-button ui-button--primary" :disabled="busy" @tap="submit">提交审核</button></view>
      <view v-if="activity.status === 'PUBLISHED'" class="cancellation-section">
        <text class="section-title">修改活动</text>
        <view v-if="pendingChange" class="ui-feedback">变更申请待管理员审核。修改原因：{{ pendingChange.reason }}</view>
        <button v-else class="ui-button ui-button--secondary" :disabled="Boolean(cancellation)" @tap="edit">申请修改</button>
      </view>
      <view v-if="activity.status === 'PUBLISHED'" class="cancellation-section">
        <text class="section-title">取消活动</text>
        <view v-if="cancellation" class="ui-feedback">取消申请待管理员确认。申请原因：{{ cancellation.reason }}</view>
        <template v-else-if="!pendingChange">
          <textarea v-model="cancellationReason" maxlength="500" placeholder="填写取消原因，管理员确认后会通知已报名学生" />
          <button class="ui-button ui-button--secondary" :disabled="!cancellationReason.trim() || cancellationBusy" @tap="requestCancellation">{{ cancellationBusy ? '提交中...' : '提交取消申请' }}</button>
        </template>
      </view>
      <view class="reviews-section">
        <view class="section-head"><text>审核历史</text><button @tap="toggleReviews">{{ reviewsOpen ? '收起' : '查看' }}</button></view>
        <view v-if="reviewsOpen">
          <view v-if="reviewsBusy && !reviewsLoaded" class="ui-skeleton feedback-loading" />
          <view v-if="reviewsError" class="ui-feedback ui-feedback--error" @tap="loadReviews">{{ reviewsError }}，点击重试</view>
          <view v-if="reviewsLoaded && !reviewsError">
            <view v-if="!reviewRows.length" class="ui-empty"><text class="ui-empty-title">暂无审核记录</text></view>
            <view v-for="(event, index) in reviewRows" :key="index" class="feedback-row">
              <view class="roster-head"><text class="person">{{ event.action === 'REVIEW_APPROVED' ? '审核通过' : '审核驳回' }}</text><text class="roster-status">{{ formatTime(event.createdAt) }}</text></view>
              <text v-if="event.detail" class="feedback-comment">{{ event.detail }}</text>
              <text class="muted">{{ event.actorName }}</text>
            </view>
          </view>
        </view>
      </view>

      <view v-if="stats" class="stat-strip"><view><text>{{ stats.registeredCount }}</text><text>报名</text></view><view><text>{{ stats.checkedCount }}</text><text>已签到</text></view><view><text>{{ stats.absentCount }}</text><text>未签到</text></view><view><text>{{ stats.capacity }}</text><text>名额</text></view></view>
      <view v-if="activity.status === 'PUBLISHED'" class="qr-section">
        <text class="section-title">现场签到码</text>
        <view class="qr-frame"><canvas canvas-id="organizerQr" class="qr-canvas" /><view v-if="!qrVisible || !canIssue" class="qr-mask"><text>签到二维码</text></view></view>
        <text v-if="qrVisible && canIssue" class="qr-note">{{ qrSeconds }} 秒后更新 · 仅供小程序内扫码</text>
        <text v-else-if="!canIssue" class="qr-note">{{ clock < Date.parse(activity.checkinOpensAt) ? '签到尚未开始' : '签到时段已结束' }}</text>
        <button class="ui-button ui-button--secondary qr-button" :disabled="!canIssue || qrBusy" @tap="refreshQr">{{ qrBusy ? '生成中...' : qrVisible ? '更新二维码' : '生成二维码' }}</button>
      </view>
      <view class="section-head"><text>报名名单</text><button @tap="load">刷新</button></view>
      <view class="export-actions">
        <button class="ui-button ui-button--secondary" :disabled="Boolean(exportBusy)" @tap="exportRoster('registrations')">{{ exportBusy === 'registrations' ? '生成中...' : '导出报名名单' }}</button>
        <button class="ui-button ui-button--secondary" :disabled="Boolean(exportBusy)" @tap="exportRoster('checkins')">{{ exportBusy === 'checkins' ? '生成中...' : '导出签到名单' }}</button>
      </view>
      <view v-if="!rows.length" class="ui-empty"><text class="ui-empty-title">暂无报名记录</text></view>
      <view v-for="row in rows" :key="row.id" class="roster-row">
        <view class="roster-head"><text class="person">{{ row.studentName }}</text><text class="roster-status">{{ row.status !== 'ACTIVE' ? '已取消' : row.checkedAt ? '已签到' : '未签到' }}</text></view>
        <text v-if="row.studentNumber" class="muted">学号：{{ row.studentNumber }}</text>
        <text v-if="row.studentEmail" class="muted">{{ row.studentEmail }}</text>
        <text v-if="row.checkedAt" class="muted">签到时间：{{ formatTime(row.checkedAt) }}</text>
        <text v-if="row.manualCheckinReason" class="muted">补签原因：{{ row.manualCheckinReason }}</text>
        <button v-if="row.status === 'ACTIVE' && !row.checkedAt && manualId !== row.studentId" class="text-button" @tap="manualId = row.studentId; manualReason = ''">手动补签</button>
        <view v-if="manualId === row.studentId" class="manual"><textarea v-model="manualReason" maxlength="500" placeholder="填写现场核验及补签原因" /><view class="manual-actions"><button class="ui-button ui-button--secondary" @tap="manualId = null">取消</button><button class="ui-button ui-button--primary" :disabled="!manualReason.trim() || busy" @tap="manual(row)">确认补签</button></view></view>
      </view>
      <view v-if="activity.status === 'PUBLISHED'" class="feedback-section">
        <view class="section-head"><text>活动反馈</text><button @tap="toggleFeedback">{{ feedbackOpen ? '收起' : '查看' }}</button></view>
        <view v-if="feedbackOpen">
          <view v-if="feedbackBusy && !feedbackSummary" class="ui-skeleton feedback-loading" />
          <view v-if="feedbackError" class="ui-feedback ui-feedback--error" @tap="loadFeedback">{{ feedbackError }}，点击重试</view>
          <view v-if="feedbackSummary && !feedbackError">
            <view class="feedback-overview"><text>{{ feedbackSummary.count }} 条反馈</text><text>平均 {{ Number(feedbackSummary.averageRating || 0).toFixed(1) }} / 5 分</text><button @tap="loadFeedback">刷新</button></view>
            <view v-if="!feedbackRows.length" class="ui-empty"><text class="ui-empty-title">暂无活动反馈</text></view>
            <view v-for="item in feedbackRows" :key="item.id" class="feedback-row">
              <view class="roster-head"><text class="person">{{ item.studentName }}</text><text class="roster-status">{{ item.rating }} / 5 分</text></view>
              <text class="feedback-comment">{{ item.comment }}</text>
              <text class="muted">{{ formatTime(item.updatedAt || item.createdAt) }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.management-page { padding-top: 24px; padding-bottom: 60px; }
.loading { height: 120px; }
.status { display: block; color: #147f74; font-size: 12px; font-weight: 700; }
.heading { display: block; margin-top: 7px; color: #203734; font-size: 22px; font-weight: 700; line-height: 1.4; word-break: break-all; }
.meta, .muted { display: block; margin-top: 8px; color: #71827a; font-size: 12px; line-height: 1.5; }
.review-note { display: block; margin-top: 15px; padding: 11px; border-radius: 6px; background: #fff4ef; color: #a34538; font-size: 12px; }
.actions, .manual-actions { display: flex; gap: 10px; margin-top: 18px; }
.actions button, .manual-actions button { flex: 1; min-width: 0; margin: 0; }
.stat-strip { display: flex; margin-top: 25px; padding: 15px 0; border-radius: 8px; background: #fff; }
.stat-strip view { display: flex; flex: 1; flex-direction: column; align-items: center; border-right: 1px solid #edf1ee; }
.stat-strip view:last-child { border: 0; }
.stat-strip text:first-child { color: #173f3b; font-size: 20px; font-weight: 700; }
.stat-strip text:last-child { margin-top: 5px; color: #75837c; font-size: 11px; }
.qr-section { margin-top: 24px; padding: 18px 12px; border-radius: 8px; background: #fff; text-align: center; }
.section-title { display: block; margin-bottom: 14px; color: #203734; font-size: 16px; font-weight: 700; text-align: left; }
.qr-frame { position: relative; width: 252px; height: 252px; margin: 0 auto; }
.qr-canvas { width: 252px; height: 252px; }
.qr-mask { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: #f3f6f4; color: #71827a; font-size: 13px; }
.qr-note { display: block; margin-top: 11px; color: #71827a; font-size: 12px; }
.qr-button { width: 160px; margin: 14px auto 0; }
.section-head { display: flex; align-items: center; justify-content: space-between; margin: 28px 0 12px; }
.section-head text { color: #203734; font-size: 17px; font-weight: 700; }
.section-head button { margin: 0; padding: 0; background: transparent; color: #147f74; font-size: 13px; }
.section-head button::after { border: 0; }
.roster-row { margin-bottom: 9px; padding: 15px; border-radius: 8px; background: #fff; }
.export-actions { display: flex; gap: 10px; margin-bottom: 12px; }
.export-actions button { flex: 1; min-width: 0; margin: 0; font-size: 12px; }
.roster-head { display: flex; justify-content: space-between; gap: 10px; }
.person { color: #203734; font-size: 14px; font-weight: 700; }
.roster-status { color: #147f74; font-size: 12px; }
.text-button { margin: 8px 0 0; padding: 0; background: transparent; color: #147f74; font-size: 12px; text-align: left; }
.text-button::after { border: 0; }
.manual textarea { box-sizing: border-box; width: 100%; height: 80px; margin-top: 12px; padding: 10px; border: 1px solid #dce6df; border-radius: 6px; background: #f8faf8; font-size: 13px; }
.feedback-section { margin-top: 28px; border-top: 1px solid #dce6df; }
.reviews-section { margin-top: 20px; border-top: 1px solid #dce6df; }
.reviews-section .section-head { margin-top: 16px; }
.feedback-section .section-head { margin-top: 20px; }
.feedback-loading { height: 80px; }
.feedback-overview { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; color: #566d65; font-size: 12px; }
.feedback-overview button { margin: 0 0 0 auto; padding: 0; background: transparent; color: #147f74; font-size: 12px; }
.feedback-overview button::after { border: 0; }
.feedback-row { margin-bottom: 9px; padding: 15px; border-radius: 8px; background: #fff; }
.feedback-comment { display: block; margin-top: 10px; color: #203734; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.cancellation-section { margin-top: 24px; padding-top: 18px; border-top: 1px solid #dce6df; }
.cancellation-section textarea { box-sizing: border-box; width: 100%; height: 92px; margin-bottom: 12px; padding: 12px; border: 1px solid #dce6df; border-radius: 6px; background: #fff; font-size: 13px; }
</style>
