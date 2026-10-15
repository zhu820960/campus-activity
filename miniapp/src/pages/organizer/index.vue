<script setup>
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { api, isSignedIn } from '../../common/api'
import { formatTime } from '../../common/format'

const activities = ref([])
const loading = ref(false)
const error = ref('')
const statusText = { DRAFT: '草稿', PENDING: '待审核', REJECTED: '已驳回', PUBLISHED: '已发布', CANCELLED: '已取消' }

async function load() {
  if (!isSignedIn()) { activities.value = []; error.value = '请先登录'; uni.stopPullDownRefresh(); return }
  loading.value = true; error.value = ''
  try {
    const user = await api.me()
    if (!user.roles?.includes('ORGANIZER') || user.roles?.includes('ADMIN')) {
      error.value = '仅学生组织者可使用活动管理'; activities.value = []; return
    }
    activities.value = await api.organizerActivities()
  } catch (e) { activities.value = []; error.value = e.message || '加载失败' }
  finally { loading.value = false; uni.stopPullDownRefresh() }
}
function openActivity(id) { uni.navigateTo({ url: `/pages/organizer/detail?id=${id}` }) }
function createActivity() { uni.navigateTo({ url: '/pages/organizer/edit' }) }
onShow(load)
onPullDownRefresh(load)
</script>

<template>
  <view class="ui-page organizer-page">
    <view class="page-head"><view><text class="eyebrow">组织者工作台</text><text class="heading">我组织的活动</text></view><button v-if="!loading && !error" class="ui-button ui-button--primary create" @tap="createActivity">新建活动</button></view>
    <view v-if="loading" class="ui-skeleton loading" />
    <view v-else-if="error" class="ui-feedback ui-feedback--error" @tap="load">{{ error }}，点击重试</view>
    <view v-else-if="!activities.length" class="ui-empty"><text class="ui-empty-title">还没有组织的活动</text></view>
    <view v-for="item in !loading && !error ? activities : []" :key="item.id" class="activity-row" @tap="openActivity(item.id)">
      <view class="row-top"><text class="title">{{ item.title }}</text><text class="status">{{ statusText[item.status] || item.status }}</text></view>
      <text class="meta">{{ formatTime(item.startsAt) }} · {{ item.location }}</text>
      <view class="row-foot"><text>{{ item.registeredCount }}/{{ item.capacity }} 人报名</text><text v-if="item.reviewNote" class="rejected">审核意见：{{ item.reviewNote }}</text><image src="/static/icons/next.png" mode="aspectFit" /></view>
    </view>
  </view>
</template>

<style scoped>
.organizer-page { padding-top: 24px; padding-bottom: 60px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-bottom: 24px; }
.eyebrow { display: block; color: #147f74; font-size: 12px; font-weight: 600; }
.heading { display: block; margin-top: 4px; color: #203734; font-size: 23px; font-weight: 700; }
.create { flex-shrink: 0; width: 105px; height: 42px; margin: 0; line-height: 42px; font-size: 13px; }
.loading { height: 105px; }
.activity-row { margin-bottom: 10px; padding: 16px; border-radius: 8px; background: #fff; }
.row-top, .row-foot { display: flex; align-items: flex-start; gap: 10px; }
.title { flex: 1; min-width: 0; color: #203734; font-size: 15px; font-weight: 700; line-height: 1.4; word-break: break-all; }
.status { flex-shrink: 0; color: #147f74; font-size: 12px; }
.meta { display: block; margin-top: 9px; color: #6e8078; font-size: 12px; line-height: 1.5; }
.row-foot { align-items: center; margin-top: 13px; color: #6e8078; font-size: 12px; }
.row-foot text:first-child { flex-shrink: 0; }
.rejected { flex: 1; min-width: 0; color: #b25341; word-break: break-all; }
.row-foot image { width: 16px; height: 16px; margin-left: auto; flex-shrink: 0; }
</style>
