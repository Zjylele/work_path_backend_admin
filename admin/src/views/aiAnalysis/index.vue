<template>
  <div class="analysis-workspace">
    <TrackSidebar
      :tracks="tracks" :users="users" :tabs="tabs" :selected-tracks="selectedTracks"
      :active-status="activeStatus" :total="total" :page="query.page" :loading="loading"
      :submitting="submitting" :running="hasRunning" :list-error="listError" :submit-error="submitError"
      @tab="changeTab" @select="toggleSelection" @open="showTrack" @page="changePage"
      @filter="applyFilters" @refresh="fetchLibrary" @send="startSelected"
      @clear="clearSelection" @remove="removeSelection"
    />
    <AnalysisMessages
      ref="messages" :batches="batches" :has-running="hasRunning" :polling="polling"
      :loading="loading" :sync-error="syncError" @refresh="refreshWorkspace"
      @retry="loadEvents" @review="openReview" @show-failed="changeTab(3)"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import TrackSidebar from './TrackSidebar.vue'
import AnalysisMessages from './AnalysisMessages.vue'
import { useAnalysisWorkspace } from './useAnalysisWorkspace'

const router = useRouter()
const messages = ref(null)
const {
  tracks, users, batches, selectedTracks, activeStatus, tabs, query, total,
  loading, submitting, polling, hasRunning, listError, syncError, submitError,
  toggleSelection, removeSelection, clearSelection, startSelected, loadEvents,
  openTrack, fetchLibrary, refreshWorkspace, changeTab, changePage, applyFilters
} = useAnalysisWorkspace()

async function showTrack(track) {
  const id = await openTrack(track)
  await messages.value?.focusTrack(id)
}

function openReview(trackId) {
  router.push({ name: 'WorkEventDetail', params: { trackId } })
}
</script>

<style scoped>
.analysis-workspace {
  display: flex;
  height: calc(100dvh - 94px);
  min-height: 560px;
  overflow: hidden;
  border: 1px solid #e1e7f0;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 5px 20px #1d355908;
}
@media (max-width: 850px) {
  .analysis-workspace { flex-direction: column; height: auto; min-height: calc(100dvh - 94px); }
}
</style>