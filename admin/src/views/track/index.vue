<template>
  <el-card shadow="never">
    <!-- 筛选栏 -->
    <div class="toolbar">
      <el-select
        v-model="query.userId"
        placeholder="全部用户"
        clearable
        filterable
        style="width: 180px"
      >
        <el-option
          v-for="u in userOptions"
          :key="u.id"
          :label="`${u.nickname || u.username}（${u.username}）`"
          :value="u.id"
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
      <el-button type="primary" :icon="Search" @click="handleSearch">
        搜索
      </el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>
    </div>

    <!-- 表格 -->
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="名称" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.name || '未命名' }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户" min-width="110" />
      <el-table-column prop="startTime" label="开始时间" min-width="170" />
      <el-table-column prop="endTime" label="结束时间" min-width="170" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 2 ? 'success' : 'warning'">
            {{ row.status === 2 ? '已完成' : '进行中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="distance" label="距离(m)" width="110" align="right">
        <template #default="{ row }">
          {{ row.distance == null ? '-' : Number(row.distance).toFixed(1) }}
        </template>
      </el-table-column>
      <el-table-column prop="pointCount" label="坐标点数" width="100" align="right" />
      <el-table-column label="分析状态" width="110">
        <template #default="{ row }">
          <el-tag :type="analysisTagType(row.analysisStatus)" size="small">
            {{ analysisLabel(row.analysisStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDetail(row)">
            详情
          </el-button>
          <el-button link type="danger" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无数据" />
      </template>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      class="pagination"
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="query.page"
      :page-size="query.pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getTrackList, deleteTrack } from '@/api/admin'
import { getUserList } from '@/api/admin'

const router = useRouter()
const loading = ref(false)
const list = ref([])
const total = ref(0)
const userOptions = ref([])
const timeRange = ref(null)

const query = reactive({
  page: 1,
  pageSize: 10,
  userId: null,
  startTime: null,
  endTime: null
})

async function fetchList() {
  loading.value = true
  try {
    const res = await getTrackList(query)
    list.value = res.data.list
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function fetchUsers() {
  const res = await getUserList({ page: 1, pageSize: 100 })
  userOptions.value = res.data.list
}

function handleSearch() {
  query.startTime = timeRange.value ? timeRange.value[0] : null
  query.endTime = timeRange.value ? timeRange.value[1] : null
  query.page = 1
  fetchList()
}

function handleReset() {
  query.userId = null
  timeRange.value = null
  query.startTime = null
  query.endTime = null
  query.page = 1
  fetchList()
}

function handlePageChange(page) {
  query.page = page
  fetchList()
}

function handleSizeChange(size) {
  query.pageSize = size
  query.page = 1
  fetchList()
}

function goDetail(row) {
  router.push(`/tracks/${row.id}`)
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除轨迹 #${row.id}（${row.username}）吗？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await deleteTrack(row.id)
  ElMessage.success('已删除')
  // 删除后若当前页为空则回退一页
  if (list.value.length === 1 && query.page > 1) {
    query.page -= 1
  }
  fetchList()
}

onMounted(() => {
  fetchList()
  fetchUsers()
})

// 分析状态与 TrackAnalysisService 的四个常量一一对应。
// 3（失败）要显式标出来：它不会自愈，且停在 1/0 之外的隐性问题会让
// "这条轨迹为什么永远不出结果"变得难排查
const ANALYSIS_LABELS = { 0: '未分析', 1: '分析中', 2: '已分析', 3: '分析失败' }
function analysisLabel(s) {
  return ANALYSIS_LABELS[s] ?? '未知'
}
function analysisTagType(s) {
  if (s === 2) return 'success'
  if (s === 3) return 'danger'
  if (s === 1) return 'warning'
  return 'info'
}

</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
