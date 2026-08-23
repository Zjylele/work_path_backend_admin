<template>
  <el-card shadow="never">
    <!-- 搜索栏 -->
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="按用户名/昵称搜索"
        clearable
        style="width: 260px"
        :prefix-icon="Search"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-button type="primary" :icon="Search" @click="handleSearch">
        搜索
      </el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>
    </div>

    <!-- 表格 -->
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column label="角色" width="150">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'primary'">
            {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
          </el-tag>
          <el-tag v-if="row.superAdmin === 1" type="warning" size="small" style="margin-left: 4px">
            超管
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="注册时间" min-width="170" />
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.role !== 'ADMIN'"
            link
            :type="row.status === 1 ? 'warning' : 'success'"
            @click="handleToggleStatus(row)"
          >
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button link type="primary" @click="handleResetPwd(row)">
            重置密码
          </el-button>
          <template v-if="isSuperAdmin && row.id !== currentUserId && row.superAdmin !== 1">
            <el-button
              link
              :type="row.role === 'ADMIN' ? 'info' : 'success'"
              @click="handleToggleRole(row)"
            >
              {{ row.role === 'ADMIN' ? '取消管理员' : '设为管理员' }}
            </el-button>
            <el-button link type="danger" @click="handleDeleteUser(row)">
              删除
            </el-button>
          </template>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import {
  getUserList,
  updateUserStatus,
  resetUserPassword,
  deleteUser,
  changeUserRole
} from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
// 仅超级管理员可删除用户、授予/撤销管理员
const isSuperAdmin = userStore.isSuperAdmin
const currentUserId = userStore.user?.id

const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({
  page: 1,
  pageSize: 10,
  keyword: ''
})

async function fetchList() {
  loading.value = true
  try {
    const res = await getUserList(query)
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
  query.keyword = ''
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

async function handleToggleStatus(row) {
  const nextStatus = row.status === 1 ? 0 : 1
  const action = nextStatus === 0 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}用户「${row.username}」吗？`,
      '提示',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await updateUserStatus(row.id, nextStatus)
  ElMessage.success(`已${action}`)
  fetchList()
}

async function handleResetPwd(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `为用户「${row.username}」设置新密码`,
      '重置密码',
      {
        inputType: 'password',
        inputPlaceholder: '请输入新密码（6~50 位）',
        inputValidator: (v) => {
          if (!v) return '请输入新密码'
          if (v.length < 6 || v.length > 50) return '密码长度为 6~50 位'
          return true
        },
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }
    )
    await resetUserPassword(row.id, value)
    ElMessage.success('密码已重置')
  } catch (e) {
    // 取消或失败
  }
}

// 授予/撤销管理员（仅超管按钮可见）
async function handleToggleRole(row) {
  const toAdmin = row.role !== 'ADMIN'
  const action = toAdmin ? '设为管理员' : '取消管理员'
  try {
    await ElMessageBox.confirm(
      `确定将「${row.username}」${toAdmin ? '设为管理员，使其可登录管理端' : '撤销管理员权限，降为普通用户'}吗？`,
      action,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await changeUserRole(row.id, toAdmin ? 'ADMIN' : 'USER')
  ElMessage.success(toAdmin ? '已设为管理员' : '已取消管理员')
  fetchList()
}

// 删除用户（仅超管按钮可见）：级联删除其轨迹
async function handleDeleteUser(row) {
  try {
    await ElMessageBox.confirm(
      `将删除用户「${row.username}」及其 ${row.trackCount ?? 0} 条轨迹，此操作不可恢复，确定继续吗？`,
      '删除用户',
      { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  await deleteUser(row.id)
  ElMessage.success('用户已删除')
  fetchList()
}

onMounted(fetchList)
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
</style>
