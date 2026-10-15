<script setup>
import { computed } from 'vue'
import ActivityCover from './ActivityCover.vue'
import { activityBadge, formatTime } from '../common/format'

const props = defineProps({ activity: { type: Object, required: true }, compact: { type: Boolean, default: false } })
const emit = defineEmits(['open'])
const badge = computed(() => activityBadge(props.activity))
</script>

<template>
  <view class="activity-card ui-card" :class="{ 'activity-card--compact': compact }" @tap="emit('open', activity.id)">
    <view class="art"><ActivityCover :cover-url="activity.coverImageUrl" /></view>
    <view class="details">
      <view class="topline"><text class="ui-status" :class="'ui-status--' + badge.tone">{{ badge.text }}</text><text class="capacity">{{ activity.registeredCount }}/{{ activity.capacity }} 人</text></view>
      <text class="name">{{ activity.title }}</text>
      <text v-if="!compact && activity.articleSummary" class="summary">{{ activity.articleSummary }}</text>
      <view class="meta"><image src="/static/icons/calendar.png" mode="aspectFit" /><text>{{ formatTime(activity.startsAt) }}</text></view>
      <view class="meta"><image src="/static/icons/pin.png" mode="aspectFit" /><text class="place">{{ activity.location || '地点待定' }}</text></view>
    </view>
  </view>
</template>

<style scoped>
.activity-card { margin-bottom: 16px; }
.art { width: 100%; height: 188px; }
.details { padding: 16px 17px 18px; }
.topline { display: flex; align-items: center; justify-content: space-between; margin-bottom: 11px; }
.capacity { color: #778680; font-size: 11px; }
.name { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; color: #1d302e; font-size: 17px; font-weight: 700; line-height: 1.4; }
.summary { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; margin: 7px 0 12px; color: #60716c; font-size: 13px; line-height: 1.5; }
.meta { display: flex; align-items: center; min-width: 0; margin-top: 8px; color: #667773; font-size: 12px; line-height: 1.4; }
.meta image { width: 15px; height: 15px; margin-right: 6px; flex-shrink: 0; }
.place { display: block; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.activity-card--compact { display: flex; min-height: 148px; }
.activity-card--compact .art { width: 132px; min-width: 132px; height: 150px; }
.activity-card--compact .details { flex: 1; min-width: 0; padding: 13px 13px 14px; }
.activity-card--compact .topline { margin-bottom: 8px; }
.activity-card--compact .name { font-size: 15px; line-height: 1.35; }
.activity-card--compact .meta { margin-top: 7px; font-size: 11px; }
</style>
