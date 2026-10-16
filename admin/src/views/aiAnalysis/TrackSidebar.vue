<template>
  <aside class="track-library" aria-label="轨迹选择">
    <div class="library-heading">
      <span class="eyebrow">轨迹库</span>
      <h2>选择分析轨迹</h2>
      <p>从列表中选择，每批最多 {{ MAX_BATCH_SIZE }} 条</p>
    </div>

    <div class="status-tabs" role="tablist" aria-label="轨迹分析状态">
      <button
        v-for="tab in tabs" :key="tab.value" type="button" role="tab"
        :aria-selected="activeStatus === tab.value" :class="{ active: activeStatus === tab.value }"
        @click="$emit('tab', tab.value)"
      >{{ tab.label }} <span>{{ tab.count ?? '—' }}</span></button>
    </div>

    <div class="library-filters">
      <el-input v-model="searchText" placeholder="搜索本页轨迹 / 员工" :prefix-icon="Search" clearable aria-label="搜索本页轨迹或员工" />
      <div class="filter-actions">
        <el-popover trigger="click" :width="320" placement="bottom-start">
          <template #reference><el-button size="small" :icon="Filter">筛选<span v-if="filterCount"> · {{ filterCount }}</span></el-button></template>
          <div class="filter-form">
            <label>员工</label>
            <el-select v-model="filterUser" placeholder="全部员工" clearable filterable aria-label="按员工筛选">
              <el-option v-for="user in users" :key="user.id" :value="user.id" :label="user.nickname ? `${user.nickname}（${user.username}）` : user.username" />
            </el-select>
            <label>开始时间范围</label>
            <el-date-picker v-model="filterTime" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="至" style="width:100%" />
            <div class="filter-buttons"><el-button size="small" @click="resetFilters">重置</el-button><el-button size="small" type="primary" @click="applyFilters">应用筛选</el-button></div>
          </div>
        </el-popover>
        <span v-if="filterCount" class="filter-summary">已启用 {{ filterCount }} 项筛选</span>
        <span v-else class="filter-summary">全部员工与日期</span>
        <el-button :icon="Refresh" circle size="small" :loading="loading" aria-label="刷新轨迹列表" @click="$emit('refresh')" />
      </div>
    </div>

    <div v-loading="loading" class="track-list" role="tabpanel" :aria-label="tabs.find(tab => tab.value === activeStatus)?.label">
      <div v-if="listError" class="library-empty" role="alert"><el-icon><Warning /></el-icon><p>{{ listError }}</p><el-button size="small" @click="$emit('refresh')">重新加载</el-button></div>
      <template v-else>
        <article v-for="track in visibleTracks" :key="track.id" class="track-card" :class="{ selected: selectedIds.has(String(track.id)) }">
          <div class="track-main">
            <el-checkbox
              v-if="[0, 3].includes(track.analysisStatus)" :model-value="selectedIds.has(String(track.id))"
              :disabled="submitting || (selectedTracks.length >= MAX_BATCH_SIZE && !selectedIds.has(String(track.id)))"
              :aria-label="`选择轨迹 ${track.name || track.id}`" @change="$emit('select', track)"
            />
            <span v-else class="track-state-icon" :class="{ complete: track.analysisStatus === 2 }"><el-icon><CircleCheck v-if="track.analysisStatus === 2" /><Clock v-else /></el-icon></span>
            <div class="track-info">
              <button type="button" class="track-name" :disabled="submitting && [0, 3].includes(track.analysisStatus)" @click="activateTrack(track)">{{ track.name || '未命名轨迹' }}</button>
              <p>{{ track.username || '员工未记录' }} <span>· #{{ track.id }}</span></p>
              <time>{{ formatTime(track.startTime) }}</time>
            </div>
          </div>
          <div class="track-meta">
            <span>{{ track.pointCount == null ? '点数未记录' : `${track.pointCount} 个点` }}<template v-if="formatDistance(track.distance)"> · {{ formatDistance(track.distance) }}</template></span>
            <span v-if="track.analysisStatus === 0">待分析</span>
            <span v-else-if="track.analysisStatus === 3" class="failed-label">可重试</span>
            <button v-else type="button" @click="$emit('open', track)">{{ track.analysisStatus === 2 ? '查看结果' : '查看状态' }} ›</button>
          </div>
        </article>
        <div v-if="!visibleTracks.length && !loading" class="library-empty"><el-icon><MapLocation /></el-icon><p>{{ searchText ? '本页没有匹配的轨迹' : '当前筛选下没有轨迹' }}</p><span>可切换状态或调整筛选条件</span></div>
      </template>
    </div>

    <div class="library-pagination">
      <span>共 {{ tabs.find(tab => tab.value === activeStatus)?.count ?? '—' }} 条</span>
      <el-pagination size="small" background layout="prev, pager, next" :pager-count="5" :total="total" :current-page="page" :page-size="10" :disabled="loading" @current-change="$emit('page', $event)" />
    </div>
    <footer class="selection-footer">
      <div class="selection-count"><span>已选择 <b>{{ selectedTracks.length }}</b> / {{ MAX_BATCH_SIZE }} 条</span><el-button text size="small" :disabled="!selectedTracks.length || submitting" @click="$emit('clear')">清空选择</el-button></div>
      <div v-if="selectedTracks.length" class="selected-chips" aria-label="已选轨迹">
        <el-tag v-for="track in selectedTracks" :key="track.id" size="small" :closable="!submitting" @close="$emit('remove', track.id)">{{ track.name || `轨迹 #${track.id}` }}</el-tag>
      </div>
      <p v-if="submitError" class="submit-error" role="alert">{{ submitError }}</p>
      <el-button class="send-button" type="primary" :icon="Promotion" :loading="submitting" :disabled="!selectedTracks.length || running" @click="$emit('send')">{{ running ? '等待本批分析完成' : '发送给 AI 分析' }}</el-button>
      <p class="selection-note">{{ running ? '你仍可浏览轨迹并准备下一批选择' : '提交后在右侧查看分析结果与判定依据' }}</p>
    </footer>
  </aside>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CircleCheck, Clock, Filter, MapLocation, Promotion, Refresh, Search, Warning } from '@element-plus/icons-vue'
import { formatDistance, formatTime, MAX_BATCH_SIZE } from './analysisDisplay'

const props = defineProps({
  tracks: { type: Array, default: () => [] }, users: { type: Array, default: () => [] },
  tabs: { type: Array, default: () => [] }, selectedTracks: { type: Array, default: () => [] },
  activeStatus: Number, total: Number, page: Number, loading: Boolean, submitting: Boolean,
  running: Boolean, listError: String, submitError: String
})
const emit = defineEmits(['tab', 'select', 'open', 'page', 'filter', 'refresh', 'send', 'clear', 'remove'])
const searchText = ref('')
const filterUser = ref(null)
const filterTime = ref(null)
const filterCount = ref(0)
const selectedIds = computed(() => new Set(props.selectedTracks.map(track => String(track.id))))
const visibleTracks = computed(() => {
  const text = searchText.value.trim().toLocaleLowerCase()
  return props.tracks.filter(track => !text || `${track.name || ''} ${track.username || ''} ${track.id}`.toLocaleLowerCase().includes(text))
})

watch(() => [props.activeStatus, props.page], () => { searchText.value = '' })

function activateTrack(track) {
  emit([0, 3].includes(track.analysisStatus) ? 'select' : 'open', track)
}
function applyFilters() {
  filterCount.value = Number(filterUser.value != null && filterUser.value !== '') + Number(Boolean(filterTime.value?.length))
  emit('filter', { userId: filterUser.value, timeRange: filterTime.value })
}
function resetFilters() {
  filterUser.value = null
  filterTime.value = null
  searchText.value = ''
  applyFilters()
}
</script>

<style scoped>
.track-library { width: 316px; flex: 0 0 316px; display: flex; flex-direction: column; min-height: 0; background: #fbfcfe; border-right: 1px solid #e7ecf3; }
.library-heading { padding: 24px 20px 18px; }
.eyebrow { color: #8c9bb1; font-size: 11px; letter-spacing: 2px; }
.library-heading h2 { margin: 8px 0; font-size: 18px; font-weight: 600; color: #2b3c55; }
.library-heading p { color: #8c98aa; font-size: 12px; line-height: 1.6; }
.status-tabs { display: flex; padding: 0 12px; border-bottom: 1px solid #e8edf4; }
.status-tabs button { flex: 1; border: 0; border-bottom: 2px solid transparent; padding: 12px 0; background: transparent; color: #8491a6; font-size: 12px; cursor: pointer; white-space: nowrap; }
.status-tabs button.active { color: #2f69cf; border-bottom-color: #2f69cf; font-weight: 600; }
.status-tabs span { font-size: 10px; margin-left: 2px; }
.library-filters { padding: 16px 16px 10px; }
.library-filters :deep(.el-input__wrapper) { border-radius: 7px; }
.filter-actions { display: flex; gap: 6px; align-items: center; margin-top: 10px; }
.filter-summary { flex: 1; color: #8d9aab; font-size: 10px; }
.filter-actions :deep(.el-button + .el-button) { margin-left: 0; }
.filter-form { display: flex; flex-direction: column; gap: 10px; }
.filter-form label { color: #7e8ba0; font-size: 12px; }
.filter-buttons { display: flex; justify-content: flex-end; margin-top: 4px; }
.track-list { flex: 1; min-height: 100px; overflow-y: auto; padding: 4px 12px; }
.track-card { background: #fff; border: 1px solid #e4eaf2; border-radius: 8px; padding: 13px 11px 12px; margin-bottom: 10px; transition: border-color .15s, background .15s; }
.track-card.selected { border-color: #8db3f3; background: #f2f7ff; }
.track-main { display: flex; gap: 9px; align-items: flex-start; }
.track-main :deep(.el-checkbox) { height: 22px; margin-right: 0; }
.track-state-icon { width: 16px; flex: 0 0 16px; height: 22px; display: grid; place-items: center; color: #c49a52; }
.track-state-icon.complete { color: #58a18d; }
.track-info { flex: 1; min-width: 0; }
.track-name { color: #34465f; background: none; border: 0; font: inherit; font-weight: 600; font-size: 12px; text-align: left; cursor: pointer; line-height: 1.7; overflow-wrap: anywhere; }
.track-name:hover { color: #2867cf; }
.track-info p { font-size: 11px; color: #8290a4; margin: 5px 0; overflow-wrap: anywhere; }
.track-info p span { color: #a0aabd; }
.track-info time { color: #9ba6b6; font-size: 11px; }
.track-meta { display: flex; justify-content: space-between; align-items: center; gap: 6px; margin: 12px 0 0 24px; font-size: 10px; color: #8e9bae; }
.track-meta button { padding: 0; background: none; border: 0; font-size: 10px; color: #507fc4; cursor: pointer; white-space: nowrap; }
.failed-label { color: #ba6c6c; }
.library-empty { padding: 40px 10px; text-align: center; color: #95a1b3; font-size: 12px; }
.library-empty > .el-icon { font-size: 26px; color: #b2bed0; }
.library-empty p { margin: 12px 0; line-height: 1.8; }
.library-empty span { font-size: 11px; }
.library-pagination { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; gap: 5px; border-top: 1px solid #edf0f5; }
.library-pagination > span { color: #95a1b2; font-size: 10px; }
.library-pagination :deep(.el-pagination) { --el-pagination-button-width-small: 24px; --el-pagination-button-height-small: 24px; gap: 2px; }
.selection-footer { padding: 12px 16px 14px; background: white; border-top: 1px solid #e7ecf3; }
.selection-count { display: flex; align-items: center; justify-content: space-between; color: #8b99ab; font-size: 11px; margin-bottom: 8px; }
.selection-count b { color: #3972c4; }
.selection-count :deep(.el-button) { font-size: 11px; }
.selected-chips { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 12px; max-height: 72px; overflow-y: auto; }
.selected-chips :deep(.el-tag) { max-width: 100%; height: auto; min-height: 22px; white-space: normal; }
.selected-chips :deep(.el-tag__content) { overflow-wrap: anywhere; }
.send-button { width: 100%; height: 38px; border-radius: 7px; --el-color-primary: #2e69d4; }
.selection-note { text-align: center; font-size: 10px; color: #9aa6b7; margin-top: 10px; line-height: 1.6; }
.submit-error { color: #b55e60; font-size: 11px; line-height: 1.7; margin-bottom: 10px; }
button:focus-visible { outline: 2px solid #71a0ea; outline-offset: 3px; }
@media (max-width: 1200px) { .track-library { width: 288px; flex-basis: 288px; } .library-heading { padding-left: 17px; } }
@media (max-width: 850px) { .track-library { width: 100%; flex-basis: auto; max-height: 530px; border-right: 0; border-bottom: 1px solid #e7ecf3; } .library-heading { padding: 18px 18px 10px; } .track-list { max-height: 200px; min-height: 130px; } .library-filters { padding-top: 10px; } .library-pagination { padding-top: 6px; padding-bottom: 6px; } }
</style>
