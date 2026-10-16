<template>
  <div class="analysis-page">
    <el-alert
      title="由管理员选择轨迹后启动分析"
      description="分析会逐条处理。完成后，AI 生成的工作事件会显示在下方，并进入工作事件复核；AI 结果不会自动视为审核通过。"
      type="info"
      :closable="false"
      show-icon
      class="intro"
    />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="query.userId" placeholder="全部用户" clearable filterable style="width: 180px">
          <el-option
            v-for="user in users"
            :key="user.id"
            :label="`${user.nickname || user.username}（${user.username}）`"
            :value="user.id"
          />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 380px"
        />
        <el-button type="primary" :icon="Search" @click="search">筛选</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
        <el-button
          type="success"
          :disabled="selectedTracks.length === 0 || submitting"
          :loading="submitting"
          @click="startSelected"
        >
          分析所选轨迹（{{ selectedTracks.length }}/10）
        </el-button>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="tracks"
        row-key="id"
        stripe
        @selection-change="handleSelectionChange"
        @select="handleSelect"
      >
        <el-table-column type="selection" width="48" :selectable="isSelectable" />
        <el-table-column prop="id" label="ID" width="78" />
        <el-table-column label="轨迹名称" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.name || '未命名' }}</template>
        </el-table-column>
        <el-table-column prop="username" label="用户" min-width="110" />
        <el-table-column prop="startTime" label="开始时间" min-width="170" />
        <el-table-column prop="pointCount" label="轨迹点" width="90" align="right" />
        <el-table-column label="分析状态" width="115">
          <template #default="{ row }">
            <el-tag :type="statusType(row.analysisStatus)" size="small">{{ statusText(row.analysisStatus) }}</el-tag>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当前筛选下没有待处理轨迹" /></template>
      </el-table>

      <el-pagination
        class="pagination"
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :current-page="query.page"
        :page-size="query.pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </el-card>

    <section v-if="activeResults.length" class="results-section">
      <div class="section-title">
        <h3>本次分析结果</h3>
        <el-button text :icon="Refresh" @click="pollActive">刷新状态</el-button>
      </div>

      <el-card v-for="item in activeResults" :key="item.id" shadow="never" class="result-card">
        <template #header>
          <div class="result-header">
            <span>{{ item.name || '未命名轨迹' }} · #{{ item.id }} · {{ item.username || '未知用户' }}</span>
            <el-tag :type="statusType(item.analysisStatus)" size="small">{{ statusText(item.analysisStatus) }}</el-tag>
          </div>
        </template>

        <el-progress v-if="item.analysisStatus === 1" :percentage="100" status="warning" :indeterminate="true" />
        <el-alert
          v-else-if="item.analysisStatus === 3"
          title="分析失败，可在上方重新勾选该轨迹后重试。"
          type="error"
          :closable="false"
        />
        <template v-else-if="item.analysisStatus === 2">
          <el-empty v-if="item.eventsLoaded && item.events.length === 0" description="没有识别到可复核的停留事件" />
          <div v-else-if="!item.eventsLoaded" class="loading-events">正在读取分析结果…</div>
          <div v-else class="event-list">
            <el-card v-for="event in item.events" :key="event.eventId" shadow="never" class="event-card">
              <div class="event-heading">
                <strong>{{ event.poiName || '地点未识别' }}</strong>
                <el-tag v-if="event.semanticLabel" size="small">{{ event.semanticLabel }}</el-tag>
                <el-tag v-else type="info" size="small">未标注</el-tag>
                <span v-if="event.confidence != null" class="confidence">置信度 {{ (event.confidence * 100).toFixed(0) }}%</span>
              </div>
              <div class="event-meta">
                {{ event.startTime }} 至 {{ event.endTime }} · 停留 {{ event.dwellSeconds || 0 }} 秒
              </div>
              <p v-if="event.aiReason" class="reason">{{ event.aiReason }}</p>
              <el-collapse v-if="event.analysisTrace">
                <el-collapse-item title="查看 AI 判定依据">
                  <pre class="trace">{{ event.analysisTrace }}</pre>
                </el-collapse-item>
              </el-collapse>
            </el-card>
            <div class="review-action">
              <span>AI 结果已保存为待确认事件，需由管理员复核后才会触发合规核查。</span>
              <el-button type="primary" @click="openReview(item.id)">进入事件复核</el-button>
            </div>
          </div>
        </template>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { getTrackList, getUserList, startManualTrackAnalysis } from '@/api/admin'
import { getTrackWorkEvents } from '@/api/workEvent'

const router = useRouter()
const tableRef = ref(null)
const loading = ref(false)
const submitting = ref(false)
const tracks = ref([])
const users = ref([])
const total = ref(0)
const selectedTracks = ref([])
const activeResults = ref([])
const timeRange = ref(null)
const pollTimer = ref(null)
const polling = ref(false)

const query = reactive({
  page: 1,
  pageSize: 10,
  userId: null,
  startTime: null,
  endTime: null
})

async function fetchTracks() {
  loading.value = true
  try {
    const res = await getTrackList({
      ...query,
      analysisStatuses: '0,1,3'
    })
    tracks.value = res.data.list
    total.value = res.data.total
    syncActiveTracks(tracks.value.filter((track) => track.analysisStatus === 1))
  } finally {
    loading.value = false
  }
}

function syncActiveTracks(runningTracks) {
  for (const track of runningTracks) {
    if (!activeResults.value.some((item) => item.id === track.id)) {
      activeResults.value.unshift({ ...track, events: [], eventsLoaded: false })
    }
  }
  ensurePolling()
}

async function fetchUsers() {
  const res = await getUserList({ page: 1, pageSize: 100 })
  users.value = res.data.list
}

function isSelectable(row) {
  return row.analysisStatus === 0 || row.analysisStatus === 3
}

function handleSelectionChange(selection) {
  selectedTracks.value = selection
}

function handleSelect(selection, row) {
  if (selection.includes(row) && selection.length > 10) {
    tableRef.value?.toggleRowSelection(row, false)
    ElMessage.warning('每批最多选择 10 条轨迹')
  }
}

async function startSelected() {
  const selected = [...selectedTracks.value]
  if (selected.length === 0) return
  submitting.value = true
  try {
    await startManualTrackAnalysis(selected.map((track) => track.id))
    for (const track of selected) {
      const existing = activeResults.value.find((item) => item.id === track.id)
      if (existing) {
        Object.assign(existing, { ...track, analysisStatus: 1, events: [], eventsLoaded: false })
      } else {
        activeResults.value.unshift({ ...track, analysisStatus: 1, events: [], eventsLoaded: false })
      }
    }
    selectedTracks.value = []
    tableRef.value?.clearSelection()
    ElMessage.success(`已启动 ${selected.length} 条轨迹的分析`)
    await fetchTracks()
    ensurePolling()
  } finally {
    submitting.value = false
  }
}

function ensurePolling() {
  const needsPolling = activeResults.value.some((item) =>
    item.analysisStatus === 1 || (item.analysisStatus === 2 && !item.eventsLoaded)
  )
  if (needsPolling && !pollTimer.value) {
    pollTimer.value = window.setInterval(pollActive, 2000)
  } else if (!needsPolling && pollTimer.value) {
    window.clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

async function pollActive() {
  if (polling.value) return
  const ids = activeResults.value
    .filter((item) => item.analysisStatus === 1 || (item.analysisStatus === 2 && !item.eventsLoaded))
    .map((item) => item.id)
  if (!ids.length) {
    ensurePolling()
    return
  }

  polling.value = true
  try {
    const res = await getTrackList({ page: 1, pageSize: 10, trackIds: ids.join(',') })
    for (const fresh of res.data.list) {
      const item = activeResults.value.find((result) => result.id === fresh.id)
      if (!item) continue
      const previousStatus = item.analysisStatus
      item.analysisStatus = fresh.analysisStatus
      if (fresh.analysisStatus === 2 && !item.eventsLoaded) {
        const eventRes = await getTrackWorkEvents(item.id)
        item.events = eventRes.data
        item.eventsLoaded = true
      } else if (fresh.analysisStatus === 3) {
        item.analysisError = true
      }
      if (previousStatus !== fresh.analysisStatus && fresh.analysisStatus === 2) {
        ElMessage.success(`轨迹 #${item.id} 分析完成`)
      }
    }
  } catch {
    // 请求拦截器会提示网络或业务错误，保留轮询以便下一轮恢复。
  } finally {
    polling.value = false
    ensurePolling()
  }
}

function search() {
  query.startTime = timeRange.value?.[0] || null
  query.endTime = timeRange.value?.[1] || null
  query.page = 1
  fetchTracks()
}

function reset() {
  query.userId = null
  query.startTime = null
  query.endTime = null
  timeRange.value = null
  query.page = 1
  fetchTracks()
}

function changePage(page) {
  query.page = page
  fetchTracks()
}

function changePageSize(pageSize) {
  query.pageSize = pageSize
  query.page = 1
  fetchTracks()
}

function openReview(trackId) {
  router.push({ name: 'WorkEventDetail', params: { trackId } })
}

function statusText(status) {
  return { 0: '待分析', 1: '排队/分析中', 2: '已完成', 3: '分析失败' }[status] || '未知'
}

function statusType(status) {
  return { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }[status] || 'info'
}

onMounted(async () => {
  await Promise.all([fetchTracks(), fetchUsers()])
})

onUnmounted(() => {
  if (pollTimer.value) window.clearInterval(pollTimer.value)
})
</script>

<style scoped>
.analysis-page { display: flex; flex-direction: column; gap: 16px; }
.intro { margin-bottom: 0; }
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.results-section { display: flex; flex-direction: column; gap: 12px; }
.section-title, .result-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-title h3 { margin: 0; font-size: 16px; color: #303133; }
.result-card { margin-bottom: 0; }
.event-list { display: flex; flex-direction: column; gap: 12px; }
.event-card { background: #fafafa; }
.event-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.confidence, .event-meta { color: #606266; font-size: 13px; }
.event-meta { margin-top: 8px; }
.reason { margin: 8px 0; color: #303133; }
.trace { margin: 0; padding: 10px; white-space: pre-wrap; word-break: break-word; background: #f5f7fa; border-radius: 4px; }
.review-action { display: flex; justify-content: space-between; align-items: center; gap: 12px; color: #606266; }
.loading-events { padding: 12px 0; color: #909399; }
</style>
