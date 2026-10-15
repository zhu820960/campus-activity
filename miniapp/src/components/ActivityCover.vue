<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { assetUrl, downloadActivityImage } from '../common/api'

const props = defineProps({ coverUrl: { type: String, default: '' }, large: { type: Boolean, default: false } })
const failed = ref(false)
const source = ref('')
const hasCover = computed(() => source.value && !failed.value)
let generation = 0
watch(() => props.coverUrl, async value => {
  const current = ++generation
  source.value = ''; failed.value = false
  if (value?.startsWith('/images/')) {
    try {
      const file = await downloadActivityImage(value)
      if (current === generation) source.value = file
    } catch { if (current === generation) failed.value = true }
  } else source.value = assetUrl(value)
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <view class="cover" :class="{ 'cover--large': large }">
    <image :src="hasCover ? source : '/static/visual/cover-placeholder.jpg'" mode="aspectFill" class="cover-image" @error="failed = true" />
    <text v-if="!hasCover" class="fallback-label">封面待更新</text>
  </view>
</template>

<style scoped>
.cover { position: relative; width: 100%; height: 100%; overflow: hidden; background: #dce9e3; }
.cover-image { display: block; width: 100%; height: 100%; }
.fallback-label { position: absolute; left: 10px; bottom: 10px; padding: 5px 8px; border-radius: 4px; background: rgba(255,255,255,.92); color: #31554c; font-size: 10px; font-weight: 600; }
.cover--large .fallback-label { left: 18px; bottom: 17px; font-size: 12px; }
</style>
