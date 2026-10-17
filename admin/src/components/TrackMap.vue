<template>
  <div class="track-map-shell" :aria-busy="mapLoading">
    <div ref="mapContainer" class="track-map"></div>
    <div v-if="mapError" class="map-notice" role="alert">
      <span>{{ mapError }}</span>
      <el-button size="small" type="primary" plain @click="retryMap">重新加载</el-button>
    </div>
    <div v-else-if="mapLoading" class="map-loading" role="status">正在加载地图底图…</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import AMapLoader from '@amap/amap-jsapi-loader'
import { AMAP_KEY, AMAP_SECURITY_CODE } from '@/config/amap'

const props = defineProps({
  // 坐标点数组：[{ latitude, longitude, recordTime }]
  points: {
    type: Array,
    default: () => []
  },
  // 附加标记（停留点等）：[{ id, latitude, longitude, color, label }]
  // 不传时行为与之前完全一致（只画轨迹线与起终点）
  overlays: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['overlay-click'])

const mapContainer = ref(null)
const mapLoading = ref(true)
const mapError = ref('')
let map = null
let disposed = false
let loadTimer = null
let initializing = false
let polyline = null
let markers = []
let overlayMarkers = []

// 加载高德 JS API
async function loadMap() {
  if (!AMAP_KEY) throw new Error('MAP_KEY_MISSING')
  // 安全密钥必须在 load() 之前挂到 window._AMapSecurityConfig——jsapi-loader 1.0.x
  // 并不认识 loadOptions.securityJsCode，传了会被静默忽略；没有它 2.0 的服务鉴权
  // 全部失败，症状是矢量覆盖层正常、底图瓦片全灰（实测踩过）。
  if (AMAP_SECURITY_CODE) {
    window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_CODE }
  }
  await AMapLoader.load({
    key: AMAP_KEY,
    version: '2.0',
    plugins: ['AMap.Scale', 'AMap.ToolBar'],
    AMapUI: false
  })
}

// WGS-84（GPS 原始坐标）→ GCJ-02（高德坐标系）转换
// 手机端 geolocator 采集并上传的是 WGS-84，直接绘制会偏移几百米，必须转换
const PI = Math.PI
const A = 6378245.0
const EE = 0.00669342162296594323

function outOfChina(lat, lng) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
  ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
  return ret
}

function transformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
  ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
  return ret
}

// WGS-84 → GCJ-02，返回 [lng, lat]（高德坐标）
function wgs84ToGcj02(lat, lng) {
  if (outOfChina(lat, lng)) return [lng, lat]
  let dLat = transformLat(lng - 105.0, lat - 35.0)
  let dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = (lat / 180.0) * PI
  let magic = Math.sin(radLat)
  magic = 1 - EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
  dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
  return [lng + dLng, lat + dLat]
}

// 归一化坐标：WGS-84 转 GCJ-02 后返回 AMap 使用的 [lng, lat]
function toLngLat(points) {
  return points
    .filter((p) => p.latitude != null && p.longitude != null)
    .map((p) => wgs84ToGcj02(p.latitude, p.longitude))
}

function clearOverlays() {
  if (markers.length) {
    markers.forEach((m) => m.remove())
    markers = []
  }
  if (overlayMarkers.length) {
    overlayMarkers.forEach((m) => m.remove())
    overlayMarkers = []
  }
  if (polyline) {
    map.remove(polyline)
    polyline = null
  }
}

// 绘制轨迹线 + 起终点标记
function draw(points) {
  if (!map) return
  clearOverlays()
  const lngLats = toLngLat(points)
  if (!lngLats.length) return

  // 轨迹线
  polyline = new AMap.Polyline({
    path: lngLats,
    strokeColor: '#1890ff',
    strokeWeight: 5,
    strokeOpacity: 0.9,
    lineJoin: 'round',
    lineCap: 'round'
  })
  map.add(polyline)

  // 起点
  const start = new AMap.Marker({
    position: lngLats[0],
    content: '<div class="track-marker start"></div>',
    offset: new AMap.Pixel(-8, -8)
  })
  // 终点
  const end = new AMap.Marker({
    position: lngLats[lngLats.length - 1],
    content: '<div class="track-marker end"></div>',
    offset: new AMap.Pixel(-8, -8)
  })
  markers.push(start, end)
  map.add(markers)

  drawOverlays()

  // 自适应视野（含停留点标记，否则它们可能落在视野外）
  map.setFitView([polyline, ...markers, ...overlayMarkers], false, [60, 60, 60, 60])
}

/**
 * 绘制附加标记（停留点）。
 *
 * <p>颜色由调用方按复核状态传入——待确认/已确认/已驳回三种状态在同一张图上
 * 要能一眼分开，这是这一屏存在的理由。</p>
 */
function drawOverlays() {
  if (!map) return
  const list = (props.overlays || []).filter(
    (o) => o && o.latitude != null && o.longitude != null
  )
  overlayMarkers = list.map((o) => {
    const [lng, lat] = wgs84ToGcj02(o.latitude, o.longitude)
    const marker = new AMap.Marker({
      position: [lng, lat],
      content: `<div class="stay-marker" style="background:${o.color || '#1890ff'}"></div>`,
      offset: new AMap.Pixel(-9, -9),
      zIndex: 120
    })
    marker.on('click', () => emit('overlay-click', o))
    return marker
  })
  if (overlayMarkers.length) {
    map.add(overlayMarkers)
  }
}

async function init() {
  if (initializing || disposed) return
  initializing = true
  mapLoading.value = true
  mapError.value = ''
  clearTimeout(loadTimer)
  loadTimer = setTimeout(() => {
    if (disposed) return
    mapLoading.value = false
    mapError.value = '地图底图加载超时，请检查网络；若持续空白，请管理员核对地图服务的域名白名单与鉴权配置。'
  }, 15000)
  try {
    await loadMap()
    if (disposed || !mapContainer.value) return
    map = new AMap.Map(mapContainer.value, {
      zoom: 4,
      center: [116.397428, 39.90923],
      viewMode: '2D',
      mapStyle: 'amap://styles/whitesmoke',
      resizeEnable: true
    })
    map.on('complete', () => {
      clearTimeout(loadTimer)
      if (disposed) return
      mapLoading.value = false
      mapError.value = ''
    })
    map.addControl(
      new AMap.Scale({
        position: 'RB'
      })
    )
    map.addControl(
      new AMap.ToolBar({
        position: 'RB'
      })
    )
    draw(props.points)
  } catch (error) {
    clearTimeout(loadTimer)
    if (disposed) return
    const message = String(error?.message || error || '')
    mapLoading.value = false
    if (message.includes('INVALID_USER_DOMAIN')) {
      mapError.value = '地图域名校验失败，请管理员在高德控制台核对当前域名的白名单配置。'
    } else if (message.includes('MAP_KEY_MISSING')) {
      mapError.value = '地图服务未配置，请联系管理员完成配置。'
    } else if (/INVALID_USER_KEY|INVALID_USER_SCODE|USERKEY_PLAT_NOMATCH/.test(message)) {
      mapError.value = '地图服务鉴权失败，请管理员核对地图配置。'
    } else {
      mapError.value = '地图加载失败，请检查网络后重新加载。'
    }
  } finally {
    initializing = false
  }
}

function retryMap() {
  if (initializing) {
    // SDK 请求仍在等待时，通过重新加载页面重新发起请求。
    window.location.reload()
    return
  }
  clearTimeout(loadTimer)
  if (map) {
    clearOverlays()
    map.destroy()
    map = null
  } else if (!window.AMap) {
    // loader 会缓存 SDK 加载失败的状态，重试前需要清除该状态。
    AMapLoader.reset()
  }
  init()
}

onMounted(init)

// 组件复用时 points 更新则重绘
watch(
  () => props.points,
  (val) => draw(val || []),
  { deep: true }
)

// 停留点变化时只重绘标记，不重画轨迹线（复核操作后状态色要立即变）
watch(
  () => props.overlays,
  () => {
    if (overlayMarkers.length) {
      overlayMarkers.forEach((m) => m.remove())
      overlayMarkers = []
    }
    drawOverlays()
  },
  { deep: true }
)

/**
 * 定位到某个坐标。点击右侧事件列表时调用，让地图跟着指过去。
 * 入参是 WGS-84 原始坐标（与 points/overlays 同源），内部做坐标系转换。
 */
function focusOn(lat, lng) {
  if (!map || lat == null || lng == null) return
  const [gcjLng, gcjLat] = wgs84ToGcj02(lat, lng)
  map.setZoomAndCenter(17, [gcjLng, gcjLat])
}

defineExpose({ focusOn })

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(loadTimer)
  if (map) {
    map.destroy()
    map = null
  }
})
</script>

<style scoped>
.track-map-shell {
  position: relative;
}

.map-notice,
.map-loading {
  position: absolute;
  top: 12px;
  left: 12px;
  right: 12px;
  z-index: 200;
  padding: 10px 12px;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  font-size: 13px;
  line-height: 1.6;
}

.map-notice {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #a65b00;
  background: #fffbe6;
  border: 1px solid #ffe58f;
}

.map-notice span { flex: 1; }
.map-loading { color: #606266; pointer-events: none; }

.track-map {
  width: 100%;
  height: 420px;
  border-radius: 4px;
  overflow: hidden;
}
</style>

<style>
.track-marker {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.4);
}

.track-marker.start {
  background-color: #52c41a;
}

.track-marker.end {
  background-color: #f5222d;
}

/* 停留点标记：加白色描边与阴影，压在轨迹线上也能看清 */
.stay-marker {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 3px solid #fff;
  box-shadow: 0 0 6px rgba(0, 0, 0, 0.45);
  cursor: pointer;
  transition: transform 0.15s;
}

.stay-marker:hover {
  transform: scale(1.25);
}
</style>
