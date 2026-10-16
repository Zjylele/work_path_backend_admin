<template>
  <el-container class="admin-layout">
    <!-- 侧边栏 -->
    <el-aside width="210px" class="admin-aside">
      <div class="logo">
        <el-icon :size="22"><Location /></el-icon>
        <span>工作轨迹管理端</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
        class="admin-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/tracks">
          <el-icon><MapLocation /></el-icon>
          <span>轨迹管理</span>
        </el-menu-item>
        <el-menu-item index="/ai-analysis">
          <el-icon><Connection /></el-icon>
          <span>AI轨迹分析</span>
        </el-menu-item>
        <el-menu-item index="/logs">
          <el-icon><Document /></el-icon>
          <span>操作日志</span>
        </el-menu-item>
        <el-menu-item index="/policy">
          <el-icon><Notebook /></el-icon>
          <span>制度知识库</span>
        </el-menu-item>
        <el-menu-item index="/work-events">
          <el-icon><Calendar /></el-icon>
          <span>工作事件复核</span>
        </el-menu-item>
        <!-- 菜单顺序对应业务链路：复核 → 核查结论/工单 → 规则配置 -->
        <el-menu-item index="/orders">
          <el-icon><Tickets /></el-icon>
          <span>整改工单</span>
        </el-menu-item>
        <el-menu-item index="/rules">
          <el-icon><SetUp /></el-icon>
          <span>合规规则</span>
        </el-menu-item>
        <!-- 模型接入配置：仅超级管理员可见，普通管理员看不到入口 -->
        <el-menu-item v-if="isSuperAdmin" index="/model-providers">
          <el-icon><Connection /></el-icon>
          <span>模型接入</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="admin-header">
        <div class="header-title">{{ pageTitle }}</div>
        <el-dropdown @command="handleCommand">
          <span class="user-info">
            <el-icon><UserFilled /></el-icon>
            {{ nickname }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <!-- 内容区 -->
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { logout as logoutApi } from '@/api/user'
import { Calendar } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const nickname = computed(
  () => userStore.user?.nickname || userStore.user?.username || '管理员'
)
const pageTitle = computed(() => route.meta.title || '')
// 详情页高亮父菜单
const activeMenu = computed(() => route.meta.activeMenu || route.path)
// 模型接入配置仅超级管理员可见；后端 Service 层同样校验，双保险
const isSuperAdmin = computed(() => userStore.isSuperAdmin)

function handleCommand(command) {
  if (command === 'logout') {
    ElMessageBox.confirm('确定退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
      .then(async () => {
        try {
          await logoutApi()
        } catch (e) {
          // 退出接口失败也允许本地登出
        }
        userStore.clearAuth()
        router.push('/login')
      })
      .catch(() => {})
  }
}
</script>

<style scoped>
.admin-layout {
  height: 100%;
}

.admin-aside {
  background-color: #001529;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.admin-menu {
  border-right: none;
  flex: 1;
}

.admin-menu .el-menu-item.is-active {
  background-color: #1890ff;
}

.admin-header {
  height: 60px;
  background-color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  z-index: 1;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #303133;
  outline: none;
}

.admin-main {
  background-color: #f0f2f5;
  padding: 16px;
}
</style>
