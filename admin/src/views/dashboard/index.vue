<template>
  <div class="dashboard" v-loading="loading">
    <section class="dashboard-intro"><div><span class="eyebrow">WORKSPACE OVERVIEW</span><h1>今天的工作，一目了然。</h1><p>欢迎回来，{{ nickname || '管理员' }}。{{ today }}</p></div><el-button type="primary" @click="go('/tracks')"><el-icon><MapLocation /></el-icon>查看工作轨迹</el-button></section>
    <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false" show-icon class="load-error"><el-button link @click="loadDashboard">重新加载</el-button></el-alert>
    <section class="stats-grid" aria-label="工作统计"><el-card v-for="card in cards" :key="card.label" shadow="never" class="stat-card"><div class="stat-top"><span>{{ card.label }}</span><el-icon><component :is="card.icon" /></el-icon></div><div class="stat-value">{{ card.value }}</div><span class="stat-caption">{{ card.caption }}</span></el-card></section>
    <section class="recent-grid"><el-card v-for="panel in panels" :key="panel.key" shadow="never" class="recent-card"><template #header><div class="panel-header"><span class="panel-title">{{ panel.title }}</span><el-button link type="primary" @click="go(panel.to)">查看全部 →</el-button></div></template><div v-if="panel.items.length" class="panel-list"><button v-for="item in panel.items" :key="item.key" type="button" class="panel-item" :title="item.tip || item.text" @click="go(panel.to)"><span class="item-text">{{ item.text }}</span><span v-if="item.extra" class="item-extra">{{ item.extra }}</span><el-icon><ArrowRight /></el-icon></button></div><el-empty v-else :image-size="56" description="暂无数据" /></el-card></section>
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
const loading = ref(false)
const loadError = ref('')
const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full' }).format(new Date())

const nickname = computed(
  () => userStore.user?.nickname || userStore.user?.username || ''
)

const cards = computed(() => [
  {
    label: '用户总数',
    value: stats.value.userCount ?? '-',
    icon: 'User',
    caption: '当前工作空间的用户'
  },
  {
    label: '轨迹总数',
    value: stats.value.trackCount ?? '-',
    icon: 'MapLocation',
    caption: '已记录的全部工作轨迹'
  },
  {
    label: '今日轨迹',
    value: stats.value.todayTrackCount ?? '-',
    icon: 'TrendCharts',
    caption: '今天新增的工作记录'
  },
  {
    label: '今日新增用户',
    value: stats.value.todayUserCount ?? '-',
    icon: 'UserFilled',
    caption: '今天加入工作空间的用户'
  }
])

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
    // 轨迹名称来自移动端录入；旧轨迹没有名字，统一显示「未命名」
    items: (recent.value.recentTracks || []).map((t) => ({
      key: 't' + t.id,
      text: t.username
        ? `${t.username}的轨迹（${t.name || '未命名'}）`
        : `轨迹（${t.name || '未命名'}）`,
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
      // 完整文本渲染；视觉溢出交给 CSS 单行省略号（.item-text），悬浮显示全文
      return {
        key: 'l' + l.id,
        text: full,
        tip: full
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

async function loadDashboard() {
  loading.value = true
  loadError.value = ''
  const results = await Promise.allSettled([loadStats(), loadRecent()])
  if (results.some(result => result.status === 'rejected')) loadError.value = '部分数据加载失败，请稍后重试。'
  loading.value = false
}
onMounted(loadDashboard)
</script>

<style scoped>
.dashboard { max-width:1500px; margin:0 auto; }
.dashboard-intro { display:flex; justify-content:space-between; align-items:center; gap:20px; margin:8px 0 28px; }
.eyebrow { font-size:11px; color: #596579; letter-spacing:2px; }
h1 { font-size:28px; letter-spacing:-.8px; font-weight:600; margin:8px 0; color:#24282d; }
.dashboard-intro p { color: #596579; line-height:1.7; }
.dashboard-intro .el-icon { margin-right:7px; }
.load-error { margin-bottom:18px; }
.stats-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:18px; margin-bottom:24px; }
.stat-card,.recent-card { border-radius:14px; min-width:0; }
.stat-top { display:flex; justify-content:space-between; align-items:center; color: #596579; font-size:13px; }
.stat-top .el-icon { background:#f4f6f8; border-radius:8px; padding:9px; width:34px; height:34px; color:#4b5969; }
.stat-value { font-size:34px; font-weight:600; color:#24282d; margin:12px 0 7px; font-variant-numeric:tabular-nums; }
.stat-caption { font-size:12px; color: #596579; }
.recent-grid { display:grid; grid-template-columns:minmax(0,1.4fr) minmax(0,1fr); gap:20px; }
.recent-card :deep(.el-card__header) { padding:20px 22px; border-bottom:1px solid #edf0f3; }
.panel-header { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.panel-title { font-size:16px; font-weight:600; color:#28333e; }
.panel-list { max-height:320px; overflow-y:auto; }
.panel-item { width:100%; display:flex; align-items:center; gap:12px; padding:18px 0; font:inherit; text-align:left; border:0; background:transparent; border-bottom:1px solid #edf0f3; cursor:pointer; color:#364452; }
.panel-item:last-child { border-bottom:0; }
.panel-item:hover { color:#162c40; background:#f7f8fa; }
.item-text { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.item-extra { font-size:12px; color: #596579; }
@media(max-width:1100px) { .stats-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:760px) { .dashboard-intro { align-items:flex-start; flex-direction:column; } .recent-grid { grid-template-columns:1fr; } h1 { font-size:23px; } .stats-grid { gap:10px; } .stat-card :deep(.el-card__body) { padding:14px; } }
</style>
