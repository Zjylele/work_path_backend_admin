<template>
  <div>
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        AI 分析结果以「疑似事件、待人工复核」的形式输出。只有点击「确认」后，
        该事件才会成为合规核查的依据。列表按轨迹汇总，请进入分析详情逐条复核，
        未经确认的结论不会自动变成管理行为。
      </template>
    </el-alert>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="confirmStatus" placeholder="复核状态" clearable style="width: 160px" @change="handleFilterChange">
          <el-option label="全部轨迹" value="all" />
          <el-option label="待确认" :value="0" />
          <el-option label="已确认" :value="1" />
          <el-option label="已驳回" :value="2" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="events" stripe>
        <el-table-column label="轨迹名称" min-width="260"><template #default="{ row }">{{ row.name || '未命名轨迹' }} <small>#{{ row.trackId }}</small></template></el-table-column>
        <el-table-column prop="username" label="员工" width="140" />
        <el-table-column prop="startTime" label="开始时间" min-width="165" />
        <el-table-column prop="endTime" label="结束时间" min-width="165" />
        <el-table-column prop="eventCount" label="结果总数" width="95" />
        <el-table-column label="复核进度" min-width="230"><template #default="{ row }">待确认 {{ row.pending }} · 已确认 {{ row.approved }} · 已驳回 {{ row.rejected }}</template></el-table-column>
        <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }">
          <el-button link type="primary" @click="showReason(row)">查看理由</el-button>
          <el-button link type="primary" @click="goDetail(row)">分析详情</el-button>
        </template></el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        class="pagination"
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>

    <el-dialog v-model="reasonVisible" title="轨迹分析摘要" width="680px">
      <h3>{{ current.name || '未命名轨迹' }}</h3>
      <p class="reason">共 {{ current.eventCount }} 条结果，待确认 {{ current.pending }} 条，已确认 {{ current.approved }} 条，已驳回 {{ current.rejected }} 条。</p>
      <p class="reason">{{ Object.entries(current.labels || {}).map(([label, count]) => `${label} ${count} 条`).join('；') }}</p>
      <div v-loading="reasonLoading">
        <p class="reason" v-if="reasonError">{{ reasonError }}</p>
        <details v-for="event in reasonEvents" :key="event.eventId" class="reason-event">
          <summary>{{ event.poiName || '地点未识别' }} · {{ event.semanticLabel || '未标注' }} · {{ Math.round((event.dwellSeconds || 0) / 60) }} 分钟</summary>
          <p class="reason">{{ event.startTime }} 至 {{ event.endTime }}</p>
          <p class="reason">{{ event.aiReason || '未保存判定理由' }}</p>
        </details>
      </div>
      <template #footer><el-button type="primary" @click="goDetail(current)">查看全部结果并复核</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import request from '@/utils/request'
import { getTrackWorkEvents } from '@/api/workEvent'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'

const router = useRouter()
const loading = ref(false)
const events = ref([])
const confirmStatus = ref(0)
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const reasonVisible = ref(false)
const current = reactive({})
const reasonEvents = ref([])
const reasonLoading = ref(false)
const reasonError = ref('')
let reasonVersion = 0

async function load() {
  loading.value = true
  try {
    const res = await request.get('/admin/work-events/track-groups', { params: {
      confirmStatus: typeof confirmStatus.value === 'number' ? confirmStatus.value : null,
      page: page.value, pageSize: pageSize.value
    } })
    // 管理端分页字段是 list 不是 records（后端 PageVO）
    events.value = res.data.list || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  load()
}

function handlePageChange(p) {
  page.value = p
  load()
}

function handleSizeChange(s) {
  pageSize.value = s
  page.value = 1
  load()
}

async function showReason(row) {
  const version = ++reasonVersion
  Object.assign(current, row)
  reasonEvents.value = []
  reasonError.value = ''
  reasonVisible.value = true
  reasonLoading.value = true
  try {
    const res = await getTrackWorkEvents(row.trackId)
    if (version === reasonVersion) reasonEvents.value = res.data || []
  } catch {
    if (version === reasonVersion) reasonError.value = '结果读取失败，请关闭后重新打开。'
  } finally {
    if (version === reasonVersion) reasonLoading.value = false
  }
}

/** 跳到分析详情页：把该事件的来源轨迹、停留点与事件时序放在一屏里看 */
function goDetail(row) {
  router.push(`/work-events/${row.trackId}`)
}

onMounted(load)
</script>

<style scoped>
.reason-event { padding: 12px 0; border-bottom: 1px solid #e2e6ea; color: #374151; }
.reason-event summary { cursor: pointer; line-height: 1.7; }
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
.confidence {
  font-size: 12px;
  color: #596579;
}
.reason {
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}
</style>
