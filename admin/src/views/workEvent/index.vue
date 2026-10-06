<template>
  <div>
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        AI 分析结果以「疑似事件、待人工复核」的形式输出。只有点击「确认」后，
        该事件才会成为 M5 合规核查的依据——这是「管理辅助工具」定位的落点，
        未经确认的结论不会自动变成管理行为。
      </template>
    </el-alert>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="confirmStatus" placeholder="复核状态" clearable style="width: 160px" @change="handleFilterChange">
          <el-option label="待确认" :value="0" />
          <el-option label="已确认" :value="1" />
          <el-option label="已驳回" :value="2" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="events" stripe>
        <el-table-column prop="eventId" label="ID" width="80" />
        <el-table-column prop="username" label="员工" width="110" />
        <el-table-column prop="eventType" label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.eventType === 'STAY' ? 'primary' : 'info'">
              {{ row.eventType === 'STAY' ? '停留' : row.eventType === 'MOVE' ? '移动' : row.eventType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地点" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.poiName || '未知地点' }}</template>
        </el-table-column>
        <el-table-column label="语义标注" min-width="120">
          <template #default="{ row }">
            <span v-if="row.semanticLabel">{{ row.semanticLabel }}</span>
            <el-tag v-else size="small" type="info">未标注</el-tag>
            <span v-if="row.confidence != null" class="confidence">
              ({{ (row.confidence * 100).toFixed(0) }}%)
            </span>
          </template>
        </el-table-column>
        <el-table-column label="停留时长" width="110" align="right">
          <template #default="{ row }">{{ row.dwellSeconds ? row.dwellSeconds + 's' : '-' }}</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" min-width="165" />
        <el-table-column prop="endTime" label="结束时间" min-width="165" />
        <el-table-column label="复核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="confirmTagType(row.confirmStatus)" size="small">
              {{ confirmLabel(row.confirmStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showReason(row)">查看理由</el-button>
            <el-button
              v-if="row.confirmStatus === 0"
              link
              type="success"
              @click="handleConfirm(row)"
            >
              确认
            </el-button>
            <el-button
              v-if="row.confirmStatus === 0"
              link
              type="danger"
              @click="handleReject(row)"
            >
              驳回
            </el-button>
            <el-button link type="warning" @click="handleReanalyze(row)">重新分析</el-button>
          </template>
        </el-table-column>
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

    <el-dialog v-model="reasonVisible" title="AI 判定理由" width="520px">
      <div class="reason">{{ current.aiReason || '（无）' }}</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  getWorkEvents,
  confirmWorkEvent,
  rejectWorkEvent,
  reanalyzeTrack
} from '@/api/workEvent'

const loading = ref(false)
const events = ref([])
const confirmStatus = ref(0)
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const reasonVisible = ref(false)
const current = reactive({ aiReason: '' })

async function load() {
  loading.value = true
  try {
    const res = await getWorkEvents(
      confirmStatus.value === '' ? null : confirmStatus.value,
      page.value,
      pageSize.value
    )
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

function showReason(row) {
  current.aiReason = row.aiReason
  reasonVisible.value = true
}

async function handleConfirm(row) {
  await ElMessageBox.confirm(
    `确认事件 #${row.eventId}？确认后该事件将作为 M5 合规核查的依据。`,
    '确认事件',
    { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
  )
  // 后端回传核查执行情况：确认本身已成功，但核查可能没跑成。
  // 不能一律提示「已确认」——那样管理员会以为结论已产生，
  // 到核查结论页却什么都找不到。
  const res = await confirmWorkEvent(row.eventId)
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

async function handleReject(row) {
  await ElMessageBox.confirm(
    `驳回事件 #${row.eventId}？该事件不会作为核查依据，但仍会保留用于优化分析参数。`,
    '驳回事件',
    { type: 'warning', confirmButtonText: '驳回', cancelButtonText: '取消' }
  )
  await rejectWorkEvent(row.eventId)
  ElMessage.success('已驳回')
  await load()
}

async function handleReanalyze(row) {
  await ElMessageBox.confirm(
    `重新分析来源轨迹 #${row.trackId}？旧结论会被清空，下一轮定时任务（30 秒）重新分析。`,
    '重新分析',
    { type: 'warning', confirmButtonText: '提交', cancelButtonText: '取消' }
  )
  await reanalyzeTrack(row.trackId)
  ElMessage.success('已提交重新分析，请稍后刷新')
}

function confirmLabel(s) {
  if (s === 1) return '已确认'
  if (s === 2) return '已驳回'
  return '待确认'
}

function confirmTagType(s) {
  if (s === 1) return 'success'
  if (s === 2) return 'danger'
  return 'warning'
}

onMounted(load)
</script>

<style scoped>
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
  color: #909399;
}
.reason {
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}
</style>
