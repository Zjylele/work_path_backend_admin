<template>
  <el-card shadow="never">
    <!-- 筛选栏 -->
    <div class="toolbar">
      <el-input
        v-model="query.username"
        placeholder="按操作人搜索"
        clearable
        style="width: 180px"
        :prefix-icon="Search"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-select
        v-model="query.action"
        placeholder="操作类型"
        clearable
        style="width: 160px"
        @change="handleSearch"
      >
        <el-option
          v-for="(label, value) in actionLabels"
          :key="value"
          :label="label"
          :value="value"
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
        @change="handleSearch"
      />
      <el-button type="primary" :icon="Search" @click="handleSearch">
        搜索
      </el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>
    </div>

    <!-- 日志表格 -->
    <el-table v-loading="loading" :data="list" stripe @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="50" />
      <el-table-column prop="createTime" label="操作时间" min-width="170" />
      <el-table-column prop="username" label="操作人" width="120">
        <template #default="{ row }">
          {{ row.username || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-tag :type="actionTagType(row.action)" size="small">
            {{ actionLabels[row.action] || row.action }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="目标" min-width="130">
        <template #default="{ row }">
          <span v-if="row.targetType">
            {{ row.targetType === 'USER' ? '用户' : '轨迹' }}：{{ row.targetName || row.targetId }}
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="详情" min-width="200">
        <template #default="{ row }">
          {{ row.detail || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === 1 ? 'success' : 'danger'" size="small">
            {{ row.result === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="handleDeleteOne(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无日志" />
      </template>
    </el-table>

    <!-- 批量操作栏 -->
    <div class="batch-bar">
      <span>已选 {{ selectedIds.length }} 条</span>
      <el-button
        type="danger"
        plain
        :disabled="selectedIds.length === 0"
        @click="handleBatchDelete"
      >
        批量删除所选
      </el-button>
      <el-button type="danger" plain @click="handleClearAll">清空全部日志</el-button>
    </div>

    <!-- 分页 -->
    <el-pagination
      class="pagination"
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="query.page"
      :page-size="query.pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getLogList, deleteLog, deleteLogs } from '@/api/admin'

// 动作码 → 中文标签
const actionLabels = {
  LOGIN_SUCCESS: '登录成功',
  LOGIN_FAIL: '登录失败',
  LOGOUT: '退出登录',
  USER_DISABLE: '禁用用户',
  USER_ENABLE: '启用用户',
  USER_DELETE: '删除用户',
  PASSWORD_RESET: '重置密码',
  ROLE_GRANT: '授予管理员',
  ROLE_REVOKE: '撤销管理员',
  TRACK_DELETE: '删除轨迹'
}

function actionTagType(action) {
  if (action === 'USER_DELETE' || action === 'TRACK_DELETE') return 'danger'
  if (action === 'LOGIN_FAIL') return 'warning'
  if (action === 'ROLE_GRANT' || action === 'ROLE_REVOKE') return 'primary'
  if (action === 'USER_DISABLE') return 'warning'
  if (action === 'USER_ENABLE') return 'success'
  return 'info'
}

const loading = ref(false)
const list = ref([])
const total = ref(0)
const selectedIds = ref([])
const timeRange = ref([])
const query = reactive({
  page: 1,
  pageSize: 20,
  username: '',
  action: '',
  startTime: '',
  endTime: ''
})

async function fetchList() {
  loading.value = true
  try {
    const params = { ...query }
    if (timeRange.value && timeRange.value.length === 2) {
      params.startTime = timeRange.value[0]
      params.endTime = timeRange.value[1]
    }
    const res = await getLogList(params)
    list.value = res.data.list
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  fetchList()
}

function handleReset() {
  query.username = ''
  query.action = ''
  timeRange.value = []
  query.startTime = ''
  query.endTime = ''
  query.page = 1
  fetchList()
}

function handlePageChange(page) {
  query.page = page
  fetchList()
}

function handleSelectionChange(rows) {
  selectedIds.value = rows.map((r) => r.id)
}

// 单条删除
async function handleDeleteOne(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除该条日志吗？（${row.username || '-'} · ${row.action}）`,
      '删除日志',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await deleteLog(row.id)
  ElMessage.success('已删除')
  fetchList()
}

// 批量删除所选
async function handleBatchDelete() {
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedIds.value.length} 条日志吗？此操作不可恢复`,
      '批量删除日志',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await deleteLogs(selectedIds.value)
  ElMessage.success('已删除所选日志')
  fetchList()
}

// 清空全部日志
async function handleClearAll() {
  try {
    await ElMessageBox.confirm(
      '确定清空全部日志吗？此操作不可恢复',
      '清空全部日志',
      { type: 'error', confirmButtonText: '清空', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await deleteLogs([])
  ElMessage.success('已清空全部日志')
  fetchList()
}

function handleSizeChange(size) {
  query.pageSize = size
  query.page = 1
  fetchList()
}

onMounted(fetchList)
</script>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
</style>
