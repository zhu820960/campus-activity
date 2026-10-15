<script setup>
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import ActivityCard from '../../components/ActivityCard.vue'
import { api, isSignedIn, requireSignIn } from '../../common/api'

const activities = ref([])
const announcements = ref([])
const name = ref('同学')
const query = ref('')
const loading = ref(false)
const error = ref('')
const guest = ref(true)

async function load() {
  guest.value = !isSignedIn()
  if (guest.value) {
    activities.value = []; announcements.value = []; name.value = '同学'; error.value = ''
    uni.stopPullDownRefresh(); return
  }
  loading.value = true
  error.value = ''
  try {
    const [items, notices] = await Promise.all([api.activities(), api.announcements()])
    activities.value = [...items].sort((a, b) => Date.parse(b.publishedAt || 0) - Date.parse(a.publishedAt || 0))
    announcements.value = notices
    name.value = (await api.me()).name || '同学'
  } catch (e) {
    guest.value = !isSignedIn()
    activities.value = []; announcements.value = []
    error.value = guest.value ? '' : e.message
  }
  finally { loading.value = false; uni.stopPullDownRefresh() }
}

function openList(filter = '') {
  uni.setStorageSync('activity_list_filter', filter || 'all')
  uni.switchTab({ url: '/pages/activities/index' })
}
function search() {
  uni.setStorageSync('activity_list_query', query.value.trim())
  openList()
}
function scan() {
  if (requireSignIn()) uni.navigateTo({ url: '/pages/scan/index' })
}
function mine() {
  if (requireSignIn()) uni.switchTab({ url: '/pages/my/index' })
}
function openActivity(id) { uni.navigateTo({ url: `/pages/detail/index?id=${id}` }) }
function openLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
onShow(load)
onPullDownRefresh(load)
</script>

<template>
  <view class="ui-page home">
    <view class="welcome-row">
      <text class="brand-name">校园活动</text>
      <text class="ui-title welcome">你好，{{ name }}</text>
      <text v-if="!guest && !loading && !error" class="activity-count">共 {{ activities.length }} 场活动，看看今天有什么新鲜事</text>
    </view>

    <view class="search-bar">
      <image src="/static/icons/search.png" class="ui-icon" mode="aspectFit" />
      <input v-model="query" type="text" confirm-type="search" placeholder="搜索活动名称或地点" @confirm="search" />
      <button class="search-action" @tap="search">搜索</button>
    </view>

    <view class="campus-banner">
      <image src="/static/visual/campus-life.jpg" mode="aspectFill" class="campus-photo" />
      <text class="banner-caption">发现校园里的新鲜事</text>
    </view>

    <view class="shortcuts">
      <view class="shortcut" @tap="scan"><view class="shortcut-icon tone-teal"><image src="/static/icons/scan.png" mode="aspectFit" /></view><text>扫码</text></view>
      <view class="shortcut" @tap="scan"><view class="shortcut-icon tone-mint"><image src="/static/icons/checkin.png" mode="aspectFit" /></view><text>签到</text></view>
      <view class="shortcut" @tap="openList('available')"><view class="shortcut-icon tone-amber"><image src="/static/icons/ticket.png" mode="aspectFit" /></view><text>预约</text></view>
      <view class="shortcut" @tap="mine"><view class="shortcut-icon tone-blue"><image src="/static/icons/list.png" mode="aspectFit" /></view><text>列表</text></view>
    </view>

    <view class="announcements">
      <image src="/static/icons/bell.png" mode="aspectFit" class="ui-icon" />
      <view v-if="announcements.length" class="announcement-content">
        <view v-for="notice in announcements" :key="notice.id" class="announcement-item"><text class="announcement-title">{{ notice.title }}</text><text class="announcement-body">{{ notice.body }}</text></view>
      </view>
      <text v-else class="no-announcement">{{ guest ? '登录后查看公告' : '暂无公告' }}</text>
    </view>

    <view class="ui-section">
      <view class="ui-section-head"><text class="ui-section-title">新鲜发布</text><view class="ui-link" @tap="openList()"><text>查看全部</text><image src="/static/icons/next.png" mode="aspectFit" /></view></view>
      <view v-if="guest" class="ui-empty"><text class="ui-empty-title">登录后查看校内活动</text><button class="ui-button ui-button--primary guest-login" @tap="openLogin">微信登录</button></view>
      <view v-else-if="loading" class="loading-list"><view class="ui-skeleton loading-cover" /><view class="ui-skeleton loading-line" /><view class="ui-skeleton loading-short" /></view>
      <view v-else-if="error" class="ui-feedback ui-feedback--error" @tap="load">{{ error }}，点击重试</view>
      <view v-else-if="!activities.length" class="ui-empty"><text class="ui-empty-title">还没有新活动</text><text>活动发布后会出现在这里</text></view>
      <ActivityCard v-for="item in activities" v-else :key="item.id" :activity="item" @open="openActivity" />
    </view>
  </view>
</template>

<style scoped>
.home { padding-bottom: 24px; }
.welcome-row { margin: 2px 0 20px; }
.brand-name { display: block; margin-bottom: 7px; color: #137a6f; font-size: 12px; font-weight: 700; }
.welcome { font-size: 25px; word-break: break-all; }
.activity-count { display: block; margin-top: 5px; color: #6c7e77; font-size: 12px; }
.search-bar { display: flex; align-items: center; height: 47px; padding: 0 5px 0 14px; border: 1px solid #dce6df; border-radius: 8px; background: #fff; }
.search-bar image { margin-right: 10px; }
.search-bar input { flex: 1; min-width: 0; height: 100%; font-size: 14px; color: #1d302e; }
.search-action { display: flex; align-items: center; justify-content: center; width: 60px; height: 36px; margin: 0; padding: 0; border-radius: 6px; background: #173f3b; color: #fff; font-size: 13px; }
.campus-banner { overflow: hidden; margin-top: 18px; border-radius: 8px; background: #173f3b; }
.campus-photo { display: block; width: 100%; height: 158px; }
.banner-caption { display: block; padding: 11px 15px 13px; color: #fff; font-size: 15px; font-weight: 600; }
.shortcuts { display: flex; justify-content: space-between; margin-top: 23px; }
.shortcut { display: flex; flex: 1; flex-direction: column; align-items: center; color: #263f3a; font-size: 13px; }
.shortcut-icon { display: flex; align-items: center; justify-content: center; width: 53px; height: 53px; margin-bottom: 9px; border-radius: 8px; }
.shortcut-icon image { width: 30px; height: 30px; }
.tone-teal { background: #d8efec; }.tone-mint { background: #e1f1df; }.tone-amber { background: #f8ead2; }.tone-blue { background: #e2edf1; }
.shortcut:active { opacity: .66; }
.announcements { display: flex; align-items: flex-start; margin-top: 24px; padding: 13px 14px; border-left: 3px solid #d8994b; border-radius: 5px; background: #fff; }
.announcements>.ui-icon { width: 18px; height: 18px; margin: 1px 10px 0 0; }
.announcement-content { flex: 1; min-width: 0; }
.announcement-item + .announcement-item { margin-top: 11px; padding-top: 11px; border-top: 1px solid #edf1ee; }
.announcement-title { display: block; color: #263b36; font-size: 13px; font-weight: 600; }
.announcement-body { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; margin-top: 3px; color: #657570; font-size: 12px; line-height: 1.45; white-space: pre-wrap; }
.no-announcement { color: #788783; font-size: 13px; }
.loading-list { padding: 0 0 20px; }.loading-cover { height: 178px; }.loading-line { width: 84%; height: 18px; margin: 16px 0 10px; }.loading-short { width: 53%; height: 12px; }
.guest-login { margin-top: 14px; }
</style>
