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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getStats } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const stats = ref({})

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

onMounted(async () => {
  const res = await getStats()
  stats.value = res.data
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
</style>
