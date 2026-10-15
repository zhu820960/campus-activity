<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import ActivityCard from '../../components/ActivityCard.vue'
import { api, isSignedIn } from '../../common/api'
import { registrationLabel } from '../../common/format'

const items = ref([])
const query = ref('')
const filter = ref('all')
const loading = ref(false)
const error = ref('')
const guest = ref(true)

const filtered = computed(() => items.value.filter(item => {
  if (filter.value === 'available' && registrationLabel(item) !== '可报名') return false
  const term = query.value.trim().toLowerCase()
  return !term || `${item.title} ${item.location || ''}`.toLowerCase().includes(term)
}))

async function load() {
  guest.value = !isSignedIn()
  if (guest.value) { items.value = []; error.value = ''; uni.stopPullDownRefresh(); return }
  loading.value = true
  error.value = ''
  try {
    const result = await api.activities()
    items.value = [...result].sort((a, b) => {
      const aEnded = Date.parse(a.endsAt) <= Date.now() ? 1 : 0
      const bEnded = Date.parse(b.endsAt) <= Date.now() ? 1 : 0
      return aEnded - bEnded || Date.parse(a.startsAt) - Date.parse(b.startsAt)
    })
  } catch (e) {
    guest.value = !isSignedIn()
    items.value = []
    error.value = guest.value ? '' : e.message
  }
  finally { loading.value = false; uni.stopPullDownRefresh() }
}

function enter() {
  const requestedFilter = uni.getStorageSync('activity_list_filter')
  const requestedQuery = uni.getStorageSync('activity_list_query')
  if (requestedFilter) filter.value = requestedFilter
  if (uni.getStorageInfoSync().keys.includes('activity_list_query')) query.value = requestedQuery || ''
  uni.removeStorageSync('activity_list_filter')
  uni.removeStorageSync('activity_list_query')
  load()
}
function openActivity(id) { uni.navigateTo({ url: `/pages/detail/index?id=${id}` }) }
function openLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
onShow(enter)
onPullDownRefresh(load)
</script>

<template>
  <view class="ui-page list-page">
    <view class="list-heading"><text class="eyebrow">校园活动</text><text class="ui-title">活动列表</text><text class="ui-subtitle">查找适合你的线下活动</text></view>
    <view class="search-bar"><image src="/static/icons/search.png" mode="aspectFit" /><input v-model="query" type="text" confirm-type="search" placeholder="搜索活动名称或地点" /></view>
    <view class="filters"><view class="filter" :class="{ active: filter === 'all' }" @tap="filter = 'all'">全部活动</view><view class="filter" :class="{ active: filter === 'available' }" @tap="filter = 'available'">可报名</view></view>
    <view v-if="!guest" class="result-heading"><text>{{ loading ? '正在加载' : `${filtered.length} 场活动` }}</text><text v-if="query" class="clear" @tap="query = ''">清除搜索</text></view>
    <view v-if="guest" class="ui-empty guest-list"><text class="ui-empty-title">登录后查看校内活动</text><button class="ui-button ui-button--primary guest-login" @tap="openLogin">微信登录</button></view>
    <view v-else-if="loading" class="loading-list"><view v-for="i in 3" :key="i" class="ui-skeleton loading-item" /></view>
    <view v-else-if="error" class="ui-feedback ui-feedback--error" @tap="load">{{ error }}，点击重试</view>
    <view v-else-if="!filtered.length" class="ui-empty"><text class="ui-empty-title">没有找到活动</text><text>换个关键词，或查看全部活动</text></view>
    <ActivityCard v-for="item in filtered" v-else :key="item.id" :activity="item" compact @open="openActivity" />
  </view>
</template>

<style scoped>
.list-page { padding-top: 25px; }
.eyebrow { display: block; margin-bottom: 5px; color: #147f74; font-size: 12px; font-weight: 700; }
.list-heading .ui-subtitle { margin-top: 5px; }
.search-bar { display: flex; align-items: center; height: 48px; margin-top: 21px; padding: 0 14px; border: 1px solid #dce6df; border-radius: 8px; background: #fff; }
.search-bar image { width: 20px; height: 20px; margin-right: 10px; }
.search-bar input { flex: 1; min-width: 0; height: 100%; color: #1d302e; font-size: 14px; }
.filters { display: flex; gap: 9px; margin-top: 18px; }
.filter { min-width: 77px; padding: 9px 13px; border: 1px solid #dce6df; border-radius: 6px; background: #fff; color: #5d716b; font-size: 13px; text-align: center; box-sizing: border-box; }
.filter.active { border-color: #173f3b; background: #173f3b; color: #fff; font-weight: 600; }
.result-heading { display: flex; justify-content: space-between; margin: 26px 0 13px; color: #364f47; font-size: 13px; font-weight: 600; }
.clear { color: #147f74; font-weight: 500; }
.loading-item { height: 150px; margin-bottom: 15px; }
.guest-list { margin-top: 27px; }.guest-login { margin-top: 14px; }
</style>
