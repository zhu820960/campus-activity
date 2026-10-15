<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AMapLoader from '@amap/amap-jsapi-loader'
import { api } from '../api'

const props = defineProps({
  longitude: { type: Number, default: null },
  latitude: { type: Number, default: null },
  interactive: { type: Boolean, default: false },
})
const emit = defineEmits(['select'])
const mapTarget = ref(null)
const error = ref('')
const keyword = ref('')
const results = ref([])
const searching = ref(false)
let map
let marker
let AMap
let active = true

function selectedCoordinates() {
  return Number.isFinite(props.longitude) && Number.isFinite(props.latitude)
    ? [props.longitude, props.latitude] : null
}

function mark(position, center = true) {
  if (!map || !AMap) return
  if (!marker) marker = new AMap.Marker({ position, map })
  else marker.setPosition(position)
  if (center) {
    map.setCenter(position)
    map.setZoom(16)
  }
}

function select(position, name, address) {
  const longitude = Number(position[0])
  const latitude = Number(position[1])
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return
  mark([longitude, latitude])
  emit('select', { longitude, latitude, name, address })
  results.value = []
}

async function search() {
  const query = keyword.value.trim()
  if (!map || !query || searching.value) return
  error.value = ''
  searching.value = true
  try {
    const places = await api(`/map/search?keyword=${encodeURIComponent(query)}`)
    if (!active) return
    results.value = places
    if (!places.length) error.value = '没有找到相关地点'
  } catch {
    if (active) error.value = '地点搜索暂时不可用，请稍后重试或直接在地图上选点。'
  } finally {
    searching.value = false
  }
}

function choose(poi) {
  select([poi.longitude, poi.latitude], poi.name, poi.address)
  keyword.value = poi.name
  error.value = ''
}

async function reverseSelect(position) {
  error.value = ''
  try {
    const details = await api(`/map/reverse?longitude=${position[0]}&latitude=${position[1]}`)
    if (!active) return
    select(position, details.name, details.address)
  } catch {
    if (active) error.value = '未能解析该位置，请搜索具体建筑或稍后重试。原地点不会被覆盖。'
  }
}

onMounted(async () => {
  try {
    const config = await api('/map/config')
    if (!active) return
    window._AMapSecurityConfig = { serviceHost: `${window.location.origin}/_AMapService` }
    AMap = await AMapLoader.load({ key: config.key, version: '2.0' })
    if (!active) return
    const point = selectedCoordinates()
    map = new AMap.Map(mapTarget.value, {
      viewMode: '2D', zoom: point ? 16 : 11,
      center: point || [108.9398, 34.3416],
    })
    if (point) mark(point, false)
    if (props.interactive) {
      map.on('click', (event) => reverseSelect([event.lnglat.getLng(), event.lnglat.getLat()]))
    }
  } catch {
    if (active) error.value = '地图暂时无法加载，请检查地图配置或网络连接。'
  }
})

watch(() => [props.longitude, props.latitude], () => {
  const point = selectedCoordinates()
  if (point) mark(point)
})

onBeforeUnmount(() => {
  active = false
  map?.destroy()
})
</script>

<template>
  <div class="activity-map">
    <div v-if="interactive" class="map-search"><input v-model.trim="keyword" aria-label="搜索活动地点" placeholder="搜索建筑或活动地点" @keyup.enter="search" /><button type="button" :disabled="searching" @click="search">{{ searching ? '搜索中…' : '搜索地点' }}</button></div>
    <div v-if="interactive && results.length" class="map-results"><button v-for="poi in results" :key="poi.id || `${poi.name}-${poi.longitude}`" type="button" @click="choose(poi)"><strong>{{ poi.name }}</strong><span>{{ poi.address }}</span></button></div>
    <div ref="mapTarget" class="map-canvas" aria-label="活动地点地图"></div>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
  </div>
</template>
