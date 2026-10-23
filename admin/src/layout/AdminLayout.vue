<template>
  <el-container class="admin-layout" :class="{ 'analysis-shell': route.name === 'AIAnalysis' }">
    <!-- 侧边栏 -->
    <el-aside width="210px" class="admin-aside">
      <div class="logo">
        <el-icon :size="22"><Location /></el-icon>
        <span>WorkPath</span>
      </div>
      <el-menu
        ref="menuRef"
        :default-active="activeMenu"
        :default-openeds="defaultOpenGroups"
        router
        background-color="#ffffff"
        text-color="#717780"
        active-text-color="#364452"
        class="admin-menu"
      >
        <el-sub-menu index="overview">
          <template #title>
            <el-icon><Odometer /></el-icon>
            <span>总览</span>
          </template>
          <el-menu-item index="/dashboard">
            <el-icon><Odometer /></el-icon>
            <span>仪表盘</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="tracks">
          <template #title>
            <el-icon><MapLocation /></el-icon>
            <span>轨迹业务</span>
          </template>
          <el-menu-item index="/tracks">
            <el-icon><MapLocation /></el-icon>
            <span>轨迹管理</span>
          </el-menu-item>
          <el-menu-item index="/ai-analysis">
            <el-icon><Connection /></el-icon>
            <span>AI轨迹分析</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="approvals">
          <template #title>
            <el-icon><Calendar /></el-icon>
            <span>工作审批</span>
          </template>
          <el-menu-item index="/work-events">
            <el-icon><Calendar /></el-icon>
            <span>工作事件复核</span>
          </el-menu-item>
          <!-- 工作审批按复核、整改的业务顺序排列 -->
          <el-menu-item index="/orders">
            <el-icon><Tickets /></el-icon>
            <span>整改工单</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="management">
          <template #title>
            <el-icon><SetUp /></el-icon>
            <span>管理配置</span>
          </template>
          <el-menu-item index="/users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/policy">
            <el-icon><Notebook /></el-icon>
            <span>制度知识库</span>
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
          <el-menu-item index="/logs">
            <el-icon><Document /></el-icon>
            <span>操作日志</span>
          </el-menu-item>
        </el-sub-menu>
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
import { computed, nextTick, ref, watch } from 'vue'
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
const menuRef = ref(null)
const defaultOpenGroups = ['overview', 'tracks', 'approvals', 'management']
const menuGroups = {
  '/dashboard': 'overview',
  '/tracks': 'tracks',
  '/ai-analysis': 'tracks',
  '/work-events': 'approvals',
  '/orders': 'approvals',
  '/users': 'management',
  '/policy': 'management',
  '/rules': 'management',
  '/model-providers': 'management',
  '/logs': 'management'
}

// 路由切换或进入详情页时，展开当前页面所属分组。
watch(activeMenu, async (path) => {
  await nextTick()
  const group = menuGroups[path]
  if (group) menuRef.value?.open(group)
}, { immediate: true })

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
  background-color: #ffffff;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #28333e;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid #edf0f3;
}

.admin-menu {
  border-right: none;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 12px;
}

.admin-menu :deep(.el-sub-menu__title) {
  height: 48px;
  color: #596579;
  background-color: #ffffff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 1px;
}

.admin-menu :deep(.el-sub-menu__title:hover) {
  background-color: #f7f8fa;
}

.admin-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #364452;
  box-shadow: none;
}

.admin-menu :deep(.el-sub-menu + .el-sub-menu) {
  margin-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.admin-menu :deep(.el-sub-menu__icon-arrow) {
  color: #596579;
}

.admin-menu .el-menu-item {
  height: 44px;
  line-height: 44px;
  padding-left: 44px !important;
}

.admin-menu .el-menu-item.is-active {
  background-color: #edf1f4;
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
  background-color: #f7f8fa;
  padding: 28px;
}

.analysis-shell > .el-container { min-width: 0; }

@media (max-width: 650px) {
  .analysis-shell .admin-aside { width: 56px !important; }
  .analysis-shell .logo { gap: 0; }
  .analysis-shell .admin-menu :deep(.el-sub-menu__title) { padding: 0 !important; justify-content: center; }
  .analysis-shell .admin-menu :deep(.el-sub-menu__title > .el-icon:not(.el-sub-menu__icon-arrow)) { margin-right: 0; }
  .analysis-shell .admin-menu :deep(.el-sub-menu__icon-arrow) { right: 3px; font-size: 10px; }
  .analysis-shell .admin-menu :deep(.el-sub-menu__title > span) {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .analysis-shell .logo span,
  .analysis-shell .admin-menu .el-menu-item > span {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .analysis-shell .admin-menu .el-menu-item { padding: 0 !important; justify-content: center; }
  .analysis-shell .admin-menu .el-menu-item :deep(.el-icon) { margin-right: 0; }
  .analysis-shell .admin-header { padding: 0 12px; }
  .analysis-shell .admin-main { padding: 8px; }
}
.admin-aside { border-right: 1px solid #e8eaed; }
.admin-layout > .el-container { min-width: 0; }
.logo { height: 76px; justify-content: flex-start; padding: 0 24px; font-size: 22px; letter-spacing: -.6px; }
.logo > .el-icon { color: #4b5969; }
.admin-menu { padding: 12px; }
.admin-menu :deep(.el-sub-menu__title) { font-size: 12px; letter-spacing: .5px; border-radius: 8px; }
.admin-menu .el-menu-item { border-radius: 8px; margin: 3px 0; }
.admin-menu .el-menu-item.is-active { color: #364452; font-weight: 600; }
.admin-header { border-bottom: 1px solid #e8eaed; box-shadow: none; padding: 0 28px; }
.user-info { background: #f7f8fa; padding: 8px 12px; border-radius: 8px; }
@media(max-width:900px) { .admin-aside { width: 180px !important; } .admin-main { padding: 18px; } }
@media(max-width:650px) {
  .admin-aside { width: 64px !important; }
  .logo { padding: 0; justify-content: center; gap: 0; }
  .logo span, .admin-menu .el-menu-item > span, .admin-menu :deep(.el-sub-menu__title > span) { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
  .admin-menu { padding: 4px; }
  .admin-menu .el-menu-item, .admin-menu :deep(.el-sub-menu__title) { padding: 0 !important; justify-content: center; }
  .admin-menu :deep(.el-icon) { margin-right: 0; }
  .admin-menu :deep(.el-sub-menu__icon-arrow) { right: 1px; font-size: 9px; }
  .admin-header { padding: 0 12px; } .admin-main { padding: 12px; }
}
</style>
