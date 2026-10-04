<template>
  <div>
    <el-card shadow="never" class="detail-card">
      <template #header>
        <div class="detail-header">
          <span>轨迹详情 #{{ trackId }}</span>
          <el-button link @click="router.back()">
            <el-icon><Back /></el-icon>
            返回列表
          </el-button>
        </div>
      </template>

      <div v-loading="loading">
        <el-descriptions :column="3" border v-if="detail">
          <el-descriptions-item label="名称">
            {{ detail.name || '未命名' }}
          </el-descriptions-item>
          <el-descriptions-item label="所属用户">
            {{ detail.username }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.status === 2 ? 'success' : 'warning'">
              {{ detail.status === 2 ? '已完成' : '进行中' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="距离(m)">
            {{ detail.distance == null ? '-' : Number(detail.distance).toFixed(1) }}
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            {{ detail.startTime }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ detail.endTime }}
          </el-descriptions-item>
          <el-descriptions-item label="坐标点数">
            {{ points.length }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="points-title" v-if="points.length">
          轨迹路线图（{{ points.length }} 个点）
        </div>
        <TrackMap v-if="points.length" :points="points" class="map-area" />

        <div class="points-title" v-if="points.length">
          坐标点列表（{{ points.length }} 个）
        </div>
        <el-table v-loading="loading" :data="points" stripe max-height="520">
          <el-table-column type="index" label="#" width="70" />
          <el-table-column prop="latitude" label="纬度" min-width="140" />
          <el-table-column prop="longitude" label="经度" min-width="140" />
          <el-table-column prop="recordTime" label="记录时间" min-width="170" />
          <template #empty>
            <el-empty description="该轨迹暂无坐标点" />
          </template>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTrackDetail } from '@/api/admin'
import TrackMap from '@/components/TrackMap.vue'

const route = useRoute()
const router = useRouter()
const trackId = route.params.id

const loading = ref(false)
const detail = ref(null)
const points = ref([])

onMounted(async () => {
  loading.value = true
  try {
    const res = await getTrackDetail(trackId)
    detail.value = res.data
    points.value = res.data.points || []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.points-title {
  margin: 20px 0 12px;
  font-weight: 600;
  color: #303133;
}

.map-area {
  margin-bottom: 8px;
}
</style>
