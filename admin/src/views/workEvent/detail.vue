<template>
  <div>
    <!-- 顶部：轨迹摘要 -->
    <el-card shadow="never" class="head-card">
      <template #header>
        <div class="card-head">
          <span>分析详情 · 轨迹 #{{ trackId }}</span>
          <el-button link @click="router.push('/work-events')">
            <el-icon><Back /></el-icon>
            返回复核列表
          </el-button>
        </div>
      </template>

      <el-descriptions v-if="track" :column="4" border size="small">
        <el-descriptions-item label="名称">
          {{ track.name || '未命名' }}
        </el-descriptions-item>
        <el-descriptions-item label="所属员工">
          {{ track.username || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ track.startTime || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ track.endTime || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="距离(m)">
          {{ track.distance == null ? '-' : Number(track.distance).toFixed(1) }}
        </el-descriptions-item>
        <el-descriptions-item label="坐标点数">
          {{ points.length }}
        </el-descriptions-item>
        <el-descriptions-item label="分析状态">
          <el-tag size="small" :type="analysisTagType(track.analysisStatus)">
            {{ analysisLabel(track.analysisStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="识别出的停留">
          {{ stayCount }}
        </el-descriptions-item>
        <el-descriptions-item label="待复核">
          {{ pendingCount }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-row :gutter="16" class="body">
      <!-- 左：轨迹 + 停留点 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>轨迹与停留点</span>
              <div class="legend">
                <span class="dot" style="background: #faad14"></span>待确认
                <span class="dot" style="background: #52c41a"></span>已确认
                <span class="dot" style="background: #f5222d"></span>已驳回
              </div>
            </div>
          </template>

          <TrackMap
            v-if="points.length"
            ref="mapRef"
            :points="points"
            :overlays="overlays"
            @overlay-click="onMapOverlayClick"
          />
          <el-empty v-else description="该轨迹没有坐标点，无法绘制" />
        </el-card>
      </el-col>

      <!-- 右：工作事件时序 -->
      <el-col :span="10">
        <el-card shadow="never" v-loading="loading">
          <template #header>
            <div class="card-head">
              <span>工作事件时序（{{ events.length }}）</span>
              <el-button link type="warning" @click="handleReanalyze">重新分析</el-button>
            </div>
          </template>

          <el-empty v-if="!events.length" :description="emptyDescription" />

          <div
            v-for="(e, i) in events"
            :key="e.eventId"
            class="event-item"
            :class="{ active: activeEventId === e.eventId }"
            @click="focusEvent(e)"
          >
            <div class="event-line">
              <span class="idx">{{ i + 1 }}</span>
              <span class="time">{{ e.startTime }} → {{ e.endTime }}</span>
              <el-tag size="small" :type="statusTagType(e.confirmStatus)">
                {{ statusLabel(e.confirmStatus) }}
              </el-tag>
            </div>

            <div class="event-main">
              <span class="poi">{{ e.poiName || '未知地点' }}</span>
              <span v-if="e.semanticLabel" class="label">{{ e.semanticLabel }}</span>
              <el-tag v-else size="small" type="info">未标注</el-tag>
              <span v-if="e.confidence != null" class="conf">
                {{ (e.confidence * 100).toFixed(0) }}%
              </span>
            </div>

            <div class="event-sub">
              <span>停留 {{ e.dwellSeconds || 0 }} 秒</span>
            </div>
            <div v-if="e.aiReason" class="event-reason">{{ e.aiReason }}</div>

            <!-- 判定轨迹：参数快照 / 输入统计 / LLM 原始输出 / 解析结果。
                 只展示「结论」的话，没法分辨「模型明确判为其他」和
                 「模型说了一堆废话被解析成其他」——这两者该不该驳回完全不同 -->
            <el-collapse v-if="e.analysisTrace" class="trace" @click.stop>
              <el-collapse-item title="判定轨迹（AI 依据什么判的）">
                <pre class="trace-text">{{ e.analysisTrace }}</pre>
              </el-collapse-item>
            </el-collapse>

            <div v-if="e.confirmStatus === 0" class="event-actions">
              <el-button size="small" type="success" @click.stop="handleConfirm(e)">
                确认
              </el-button>
              <el-button size="small" type="danger" @click.stop="handleReject(e)">
                驳回
              </el-button>
            </div>
            <div v-else-if="e.confirmStatus === 1" class="event-actions">
              <el-button size="small" @click.stop="handleRevoke(e)">撤销确认</el-button>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import TrackMap from '@/components/TrackMap.vue'
import { getTrackDetail } from '@/api/admin'
import {
  confirmWorkEvent,
  getTrackWorkEvents,
  reanalyzeTrack,
  rejectWorkEvent,
  revokeWorkEvent
} from '@/api/workEvent'

const route = useRoute()
const router = useRouter()
const trackId = route.params.trackId

const loading = ref(false)
const track = ref(null)
const points = ref([])
const events = ref([])
const activeEventId = ref(null)
const mapRef = ref(null)

/** 复核状态 → 地图标记颜色。三态在图上必须一眼分开，这是这一屏的意义所在 */
const STATUS_COLOR = { 0: '#faad14', 1: '#52c41a', 2: '#f5222d' }

const stayCount = computed(() => events.value.length)
const pendingCount = computed(
  () => events.value.filter((e) => e.confirmStatus === 0).length
)

/** 停留点质心（WGS-84，与轨迹点同源，TrackMap 内部负责坐标系转换） */
const overlays = computed(() =>
  events.value
    .filter((e) => e.centerLat != null && e.centerLng != null)
    .map((e) => ({
      id: e.eventId,
      latitude: e.centerLat,
      longitude: e.centerLng,
      color: STATUS_COLOR[e.confirmStatus] || '#1890ff',
      label: e.semanticLabel
    }))
)

/**
 * 空态文案按分析状态区分。
 *
 * 「还没分析」与「分析完确实没有停留」是两回事——只有后者是业务结论，
 * 前者只是状态还没到。不区分的话，管理员会把「排队中」误读成「这条轨迹没问题」。
 */
const emptyDescription = computed(() => {
  switch (track.value?.analysisStatus) {
    case 0:
      return '该轨迹尚未分析，下一轮定时任务（30 秒）会捞起它'
    case 1:
      return '该轨迹正在分析中，稍后刷新'
    case 3:
      return '该轨迹分析失败，可点右上角「重新分析」重试'
    default:
      return '该轨迹没有识别出工作事件（全是移动，没有达到停留阈值的点群）'
  }
})

function analysisLabel(s) {
  return { 0: '未分析', 1: '分析中', 2: '已完成', 3: '分析失败' }[s] ?? '—'
}

function analysisTagType(s) {
  return { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }[s] ?? 'info'
}

async function load() {
  loading.value = true
  try {
    // 两个请求各自独立：轨迹详情失败不该让事件列表也空着
    const [trackRes, eventRes] = await Promise.all([
      getTrackDetail(trackId),
      getTrackWorkEvents(trackId)
    ])
    track.value = trackRes.data
    points.value = trackRes.data?.points || []
    events.value = eventRes.data || []
  } finally {
    loading.value = false
  }
}

/** 点击事件条目：地图定位过去（这条是这一屏的核心交互） */
function focusEvent(e) {
  activeEventId.value = e.eventId
  if (e.centerLat != null && e.centerLng != null) {
    mapRef.value?.focusOn(e.centerLat, e.centerLng)
  }
}

/** 点击地图上的停留点：反向高亮右侧对应条目 */
function onMapOverlayClick(overlay) {
  const target = events.value.find((e) => e.eventId === overlay.id)
  if (target) {
    activeEventId.value = target.eventId
  }
}

async function handleConfirm(e) {
  await ElMessageBox.confirm(
    `确认事件 #${e.eventId}？确认后该事件将作为 M5 合规核查的依据。`,
    '确认事件',
    { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
  )
  // 与复核列表页同口径：核查失败要如实告诉管理员，不能一律提示「已确认」
  const res = await confirmWorkEvent(e.eventId)
  const result = res.data || {}
  if (result.checkFailed) {
    ElMessage.warning('已确认，但合规核查执行失败，请到「整改工单 → 核查结论」核对，或联系管理员查看日志')
  } else if (result.newChecks > 0) {
    ElMessage.success(`已确认，合规核查新增 ${result.newChecks} 条结论`)
  } else {
    ElMessage.success('已确认，合规核查未命中规则')
  }
  await load()
}

async function handleReject(e) {
  await ElMessageBox.confirm(
    `驳回事件 #${e.eventId}？该事件不会作为核查依据，但仍会保留用于优化分析参数。`,
    '驳回事件',
    { type: 'warning', confirmButtonText: '驳回', cancelButtonText: '取消' }
  )
  await rejectWorkEvent(e.eventId)
  ElMessage.success('已驳回')
  await load()
}

/**
 * 撤销确认：误点确认的正路。
 *
 * 存在未闭环工单时后端会拒绝并说明原因——那种情况下撤销会让工单失去依据，
 * 提示由 request 拦截器统一弹出后端文案。
 */
async function handleRevoke(e) {
  await ElMessageBox.confirm(
    `撤销事件 #${e.eventId} 的确认？该事件将退回「待确认」，可重新判断。\n注意：已产生的核查结论会保留，不会删除。`,
    '撤销确认',
    { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }
  )
  await revokeWorkEvent(e.eventId)
  ElMessage.success('已撤销确认，事件退回待确认')
  await load()
}

async function handleReanalyze() {
  await ElMessageBox.confirm(
    `重新分析轨迹 #${trackId}？旧结论会被清空，下一轮定时任务（30 秒）重新分析。`,
    '重新分析',
    { type: 'warning', confirmButtonText: '提交', cancelButtonText: '取消' }
  )
  await reanalyzeTrack(trackId)
  ElMessage.success('已提交重新分析，请稍后刷新')
}

function statusLabel(s) {
  if (s === 1) return '已确认'
  if (s === 2) return '已驳回'
  return '待确认'
}

function statusTagType(s) {
  if (s === 1) return 'success'
  if (s === 2) return 'danger'
  return 'warning'
}

onMounted(load)
</script>

<style scoped>
.head-card {
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.legend {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 400;
  color: #606266;
}

.legend .dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-left: 8px;
}

.event-item {
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.event-item:hover {
  border-color: #c6e2ff;
  background-color: #f5faff;
}

.event-item.active {
  border-color: #1890ff;
  background-color: #f0f7ff;
}

.event-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.idx {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background-color: #1890ff;
  color: #fff;
  font-size: 12px;
  flex-shrink: 0;
}

.time {
  font-size: 12px;
  color: #909399;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.event-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.poi {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.label {
  color: #1890ff;
  font-size: 13px;
}

.conf {
  font-size: 12px;
  color: #909399;
}

.event-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
}

.event-reason {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.trace {
  margin-top: 6px;
  border-top: none;
}

.trace-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: Consolas, Monaco, monospace;
}

.event-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>
