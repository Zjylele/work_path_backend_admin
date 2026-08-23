<template>
  <div ref="mapContainer" class="track-map"></div>
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
  }
})

const mapContainer = ref(null)
let map = null
let polyline = null
let markers = []

// 加载高德 JS API
async function loadMap() {
  await AMapLoader.load({
    key: AMAP_KEY,
    version: '2.0',
    plugins: ['AMap.Scale', 'AMap.ToolBar'],
    AMapUI: false,
    securityJsCode: AMAP_SECURITY_CODE
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

  // 自适应视野
  map.setFitView([polyline, ...markers], false, [60, 60, 60, 60])
}

async function init() {
  await loadMap()
  map = new AMap.Map(mapContainer.value, {
    zoom: 4,
    center: [116.397428, 39.90923],
    viewMode: '2D',
    mapStyle: 'amap://styles/whitesmoke',
    resizeEnable: true
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
}

onMounted(init)

// 组件复用时 points 更新则重绘
watch(
  () => props.points,
  (val) => draw(val || []),
  { deep: true }
)

onBeforeUnmount(() => {
  if (map) {
    map.destroy()
    map = null
  }
})
</script>

<style scoped>
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
</style>
