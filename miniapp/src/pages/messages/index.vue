<script setup>
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { api } from '../../common/api'
import { formatTime } from '../../common/format'

const messages = ref([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true; error.value = ''
  try { messages.value = await api.messages() }
  catch (e) { error.value = e.message || '消息加载失败' }
  finally { loading.value = false; uni.stopPullDownRefresh() }
}

async function openMessage(message) {
  try {
    if (!message.readAt) {
      const updated = await api.readMessage(message.id)
      messages.value = messages.value.map(item => item.id === message.id ? updated : item)
    }
    if (message.activityId) uni.navigateTo({ url: `/pages/detail/index?id=${message.activityId}` })
  } catch (e) { uni.showToast({ title: e.message || '操作失败', icon: 'none' }) }
}
onShow(load)
onPullDownRefresh(load)
</script>

<template>
  <view class="ui-page message-page">
    <text class="ui-title">消息中心</text>
    <view v-if="loading" class="ui-skeleton loading-row" />
    <view v-else-if="error" class="ui-feedback ui-feedback--error" @tap="load">{{ error }}，点击重试</view>
    <view v-else-if="!messages.length" class="ui-empty"><text class="ui-empty-title">暂无个人消息</text></view>
    <button v-for="message in messages" :key="message.id" class="message-row" :class="{ unread: !message.readAt }" @tap="openMessage(message)">
      <view class="message-heading"><text>{{ message.title }}</text><text v-if="!message.readAt" class="unread-dot" /></view>
      <text class="message-body">{{ message.body }}</text>
      <text class="message-date">{{ formatTime(message.createdAt) }}</text>
    </button>
  </view>
</template>

<style scoped>
.message-page { padding-top: 25px; padding-bottom: 40px; }
.message-page .ui-title { display: block; margin-bottom: 20px; }
.message-row { display: block; box-sizing: border-box; width: 100%; margin-bottom: 10px; padding: 16px; border-radius: 8px; background: #fff; color: #233a34; text-align: left; }
.message-row.unread { border-left: 3px solid #147f74; }
.message-heading { display: flex; align-items: center; justify-content: space-between; font-size: 15px; font-weight: 600; }
.unread-dot { width: 7px; height: 7px; border-radius: 50%; background: #147f74; }
.message-body { display: block; margin-top: 8px; color: #52665e; font-size: 13px; line-height: 1.55; white-space: normal; }
.message-date { display: block; margin-top: 12px; color: #8a9690; font-size: 11px; }
.loading-row { height: 86px; }
</style>
