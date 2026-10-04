<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon" :style="{ backgroundColor: card.color }">
              <el-icon :size="26" color="#fff">
                <component :is="card.icon" />
              </el-icon>
            </div>
            <div>
              <div class="stat-value">{{ card.value }}</div>
              <div class="stat-label">{{ card.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="welcome-card">
      <h3>欢迎回来，{{ nickname }}</h3>
      <p>这里是工作轨迹管理后台，可管理用户与轨迹数据。</p>
    </el-card>

    <!-- 最近动态：四块面板，点击条目跳转到对应管理页 -->
    <el-row :gutter="16" class="recent-row">
      <el-col :span="6" v-for="panel in panels" :key="panel.key">
        <el-card shadow="never" class="recent-card">
          <template #header>
            <div class="panel-header">
              <span class="panel-title">{{ panel.title }}</span>
              <el-button link type="primary" size="small" @click="go(panel.to)">
                更多
              </el-button>
            </div>
          </template>
          <div v-if="panel.items.length" class="panel-list">
            <div
              v-for="item in panel.items"
              :key="item.key"
              class="panel-item"
              :title="item.tip || null"
              @click="go(panel.to)"
            >
              <span class="item-text">{{ item.text }}</span>
              <span v-if="item.extra" class="item-extra">{{ item.extra }}</span>
            </div>
          </div>
          <el-empty v-else :image-size="48" description="暂无数据" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getStats, getDashboardRecent } from '@/api/admin'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const stats = ref({})
const recent = ref({})

const nickname = computed(
  () => userStore.user?.nickname || userStore.user?.username || ''
)

const cards = computed(() => [
  {
    label: '用户总数',
    value: stats.value.userCount ?? '-',
    icon: 'User',
    color: '#1890ff'
  },
  {
    label: '轨迹总数',
    value: stats.value.trackCount ?? '-',
    icon: 'MapLocation',
    color: '#52c41a'
  },
  {
    label: '今日轨迹',
    value: stats.value.todayTrackCount ?? '-',
    icon: 'TrendCharts',
    color: '#faad14'
  },
  {
    label: '今日新增用户',
    value: stats.value.todayUserCount ?? '-',
    icon: 'UserFilled',
    color: '#f5222d'
  }
])

// 「操作详情最多展示 10 个字」是展示层规则：后端返回完整 detail，
// 这里截断 + 悬浮显示全文（截断后的数据没法还原，所以不在数据层做）
const LOG_DETAIL_MAX = 10

// 日志 detail 为空时的兜底展示，与操作日志页的动作码标签同源
const ACTION_LABELS = {
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

function truncate(text, max) {
  if (!text) return ''
  return text.length > max ? text.slice(0, max) + '…' : text
}

function formatShort(t) {
  return t ? String(t).slice(5, 16) : ''
}

function eventTypeLabel(type) {
  if (type === 'STAY') return '停留'
  if (type === 'MOVE') return '移动'
  return type || '事件'
}

const panels = computed(() => [
  {
    key: 'tracks',
    title: '最近新增轨迹',
    to: '/tracks',
    // 轨迹没有名称字段，展示名用「{用户名}的轨迹 #{id}」组合
    items: (recent.value.recentTracks || []).map((t) => ({
      key: 't' + t.id,
      text: t.username ? `${t.username}的轨迹 #${t.id}` : `轨迹 #${t.id}`,
      extra: formatShort(t.startTime)
    }))
  },
  {
    key: 'events',
    title: '待复核工作事件',
    to: '/work-events',
    items: (recent.value.pendingEvents || []).map((e) => ({
      key: 'e' + e.eventId,
      text: `${e.username || '未知员工'} · ${e.semanticLabel || e.poiName || eventTypeLabel(e.eventType)}`,
      extra: formatShort(e.startTime)
    }))
  },
  {
    key: 'logs',
    title: '最新操作日志',
    to: '/logs',
    items: (recent.value.recentLogs || []).map((l) => {
      const full = l.detail || l.targetName || ACTION_LABELS[l.action] || l.action || '-'
      return {
        key: 'l' + l.id,
        text: truncate(full, LOG_DETAIL_MAX),
        tip: full.length > LOG_DETAIL_MAX ? full : null
      }
    })
  },
  {
    key: 'docs',
    title: '制度知识库',
    to: '/policy',
    items: (recent.value.recentDocuments || []).map((d) => ({
      key: 'd' + d.docId,
      text: d.title
    }))
  }
])

function go(to) {
  router.push(to)
}

// 两个请求各自独立加载：一个失败不影响另一块渲染
async function loadStats() {
  const res = await getStats()
  stats.value = res.data
}

async function loadRecent() {
  const res = await getDashboardRecent()
  recent.value = res.data || {}
}

onMounted(() => {
  loadStats()
  loadRecent()
})
</script>

<style scoped>
.stat-card {
  margin-bottom: 16px;
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.welcome-card {
  margin-top: 8px;
}

.welcome-card h3 {
  margin-bottom: 8px;
  color: #303133;
}

.welcome-card p {
  color: #909399;
}

.recent-row {
  margin-top: 16px;
}

.recent-card :deep(.el-card__header) {
  padding: 12px 16px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.panel-list {
  max-height: 320px;
  overflow-y: auto;
}

.panel-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 0;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
  border-bottom: 1px dashed #f0f0f0;
}

.panel-item:last-child {
  border-bottom: none;
}

.panel-item:hover .item-text {
  color: #1890ff;
}

.item-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-extra {
  color: #909399;
  font-size: 12px;
  flex-shrink: 0;
}
</style>
