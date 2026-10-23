import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getTrackList, getUserList, startManualTrackAnalysis } from '@/api/admin'
import { getTrackWorkEvents } from '@/api/workEvent'
import { MAX_BATCH_SIZE, STATUS_TABS } from './analysisDisplay'

function normalizeTrack(track) {
  return { ...track, analysisStatus: track.analysisStatus == null ? -1 : Number(track.analysisStatus) }
}

export function useAnalysisWorkspace() {
  const tracks = ref([])
  const users = ref([])
  const batches = ref([])
  const selected = ref(new Map())
  const activeStatus = ref('all')
  const counts = reactive({ all: null, 0: null, 1: null, 2: null, 3: null })
  const query = reactive({ page: 1, pageSize: 10, userId: null, startTime: null, endTime: null })
  const loading = ref(false)
  const submitting = ref(false)
  const polling = ref(false)
  const listError = ref('')
  const syncError = ref('')
  const submitError = ref('')
  const latestRecords = new Map()
  let disposed = false
  let listVersion = 0
  let batchSequence = 0
  let pollTimer = null

  const selectedTracks = computed(() => [...selected.value.values()])
  const total = computed(() => counts[activeStatus.value] ?? 0)
  const hasRunning = computed(() => batches.value.some((batch) => batch.items.some((item) => item.analysisStatus === 1)))
  const tabs = computed(() => STATUS_TABS.map((tab) => ({ ...tab, count: counts[tab.value] })))

  function createRecord(track) {
    const record = reactive({ ...normalizeTrack(track), events: [], eventsLoaded: false, eventsLoading: false, resultError: '', statusError: '' })
    latestRecords.set(String(record.id), record)
    return record
  }

  function addBatch(items, kind = 'submitted') {
    const batch = { id: ++batchSequence, kind, createdAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }), items }
    batches.value.push(batch)
    return batch
  }

  function recoverTracks(items) {
    const unknown = items.filter((track) => track.analysisStatus === 1 && !latestRecords.has(String(track.id)))
    if (unknown.length) addBatch(unknown.map(createRecord), 'recovered')
    schedulePolling()
  }

  async function fetchLibrary() {
    const version = ++listVersion
    const status = activeStatus.value
    const params = { ...query }
    loading.value = true
    listError.value = ''
    const responses = await Promise.allSettled(STATUS_TABS.map((tab) => getTrackList({
      ...params,
      page: tab.value === status ? params.page : 1,
      pageSize: tab.value === status ? params.pageSize : 1,
      analysisStatuses: tab.value === 'all' ? '0,1,2,3' : String(tab.value)
    })))
    if (disposed || version !== listVersion) return
    responses.forEach((response, index) => {
      const tabStatus = STATUS_TABS[index].value
      counts[tabStatus] = response.status === 'fulfilled' ? Number(response.value.data.total) : null
      if (tabStatus !== status) return
      if (response.status === 'rejected') {
        tracks.value = []
        listError.value = '轨迹列表加载失败，请重新加载。'
        return
      }
      tracks.value = (response.value.data.list || []).map(normalizeTrack)
      for (const track of tracks.value) {
        const key = String(track.id)
        if (selected.value.has(key)) {
          if ([0, 3].includes(track.analysisStatus)) selected.value.set(key, track)
          else selected.value.delete(key)
        }
      }
      recoverTracks(tracks.value)
    })
    loading.value = false
    const lastPage = Math.max(1, Math.ceil(total.value / query.pageSize))
    if (!listError.value && query.page > lastPage) {
      query.page = lastPage
      await fetchLibrary()
    }
  }

  function toggleSelection(track) {
    if (submitting.value || ![0, 3].includes(track.analysisStatus)) return
    const key = String(track.id)
    if (selected.value.has(key)) selected.value.delete(key)
    else if (selected.value.size >= MAX_BATCH_SIZE) ElMessage.warning(`每批最多选择 ${MAX_BATCH_SIZE} 条轨迹`)
    else selected.value.set(key, track)
    submitError.value = ''
  }

  function removeSelection(id) {
    if (!submitting.value) selected.value.delete(String(id))
  }

  function clearSelection() {
    if (!submitting.value) selected.value.clear()
  }

  async function startSelected() {
    if (submitting.value || hasRunning.value || !selected.value.size) return
    const snapshot = [...selected.value.values()]
    if (snapshot.length > MAX_BATCH_SIZE) return
    submitting.value = true
    submitError.value = ''
    try {
      await startManualTrackAnalysis(snapshot.map((track) => track.id))
      if (disposed) return
      addBatch(snapshot.map((track) => createRecord({ ...track, analysisStatus: 1 })))
      snapshot.forEach((track) => selected.value.delete(String(track.id)))
      syncError.value = ''
      schedulePolling()
      await fetchLibrary()
    } catch {
      if (!disposed) submitError.value = '提交未成功，已保留选择。请刷新轨迹状态后重试。'
    } finally {
      if (!disposed) submitting.value = false
    }
  }

  async function loadEvents(record) {
    if (record.eventsLoading || disposed) return
    record.eventsLoading = true
    record.resultError = ''
    try {
      const response = await getTrackWorkEvents(record.id)
      if (!disposed) {
        if (!Array.isArray(response.data)) throw new Error('Unexpected event response')
        record.events = response.data
        record.eventsLoaded = true
      }
    } catch {
      if (!disposed) record.resultError = '分析已完成，但结果读取失败。可单独重新加载。'
    } finally {
      if (!disposed) record.eventsLoading = false
    }
  }

  async function openTrack(track) {
    let record = latestRecords.get(String(track.id))
    if (!record || record.analysisStatus !== track.analysisStatus) {
      record = createRecord(track)
      addBatch([record], track.analysisStatus === 1 ? 'recovered' : 'saved')
    }
    if (record.analysisStatus === 2 && !record.eventsLoaded) void loadEvents(record)
    schedulePolling()
    return record.id
  }

  function stopTimer() {
    if (pollTimer) window.clearTimeout(pollTimer)
    pollTimer = null
  }

  function schedulePolling() {
    if (disposed || syncError.value || !hasRunning.value) {
      stopTimer()
      return
    }
    if (!pollTimer && !polling.value) pollTimer = window.setTimeout(pollActive, 2000)
  }

  async function pollActive() {
    if (polling.value || disposed) return
    stopTimer()
    const records = [...latestRecords.values()].filter((item) => item.analysisStatus === 1)
    if (!records.length) return
    polling.value = true
    let changed = false
    try {
      // The API paginates results, including ID-filtered requests. Keep each request within its page size.
      for (let offset = 0; offset < records.length; offset += MAX_BATCH_SIZE) {
        const group = records.slice(offset, offset + MAX_BATCH_SIZE)
        const response = await getTrackList({ page: 1, pageSize: MAX_BATCH_SIZE, trackIds: group.map((item) => item.id).join(',') })
        if (disposed) return
        const freshById = new Map((response.data.list || []).map((track) => [String(track.id), normalizeTrack(track)]))
        for (const record of group) {
          const fresh = freshById.get(String(record.id))
          if (!fresh) {
            record.analysisStatus = -1
            record.statusError = '轨迹已不存在或当前不可访问，请刷新列表。'
            changed = true
            continue
          }
          if (fresh.analysisStatus === record.analysisStatus) continue
          Object.assign(record, fresh)
          changed = true
          selected.value.delete(String(record.id))
          if (record.analysisStatus === 2) void loadEvents(record)
        }
      }
    } catch {
      if (!disposed) syncError.value = '状态同步暂时中断。已有结果已保留，点击“重新同步”继续查看。'
    } finally {
      if (!disposed) {
        polling.value = false
        if (changed) void fetchLibrary()
        schedulePolling()
      }
    }
  }

  async function refreshWorkspace() {
    syncError.value = ''
    await Promise.allSettled([fetchLibrary(), pollActive()])
    schedulePolling()
  }

  function changeTab(status) {
    activeStatus.value = status
    query.page = 1
    void fetchLibrary()
  }

  function changePage(page) {
    query.page = page
    void fetchLibrary()
  }

  function applyFilters(filters) {
    query.userId = filters.userId ?? null
    query.startTime = filters.timeRange?.[0] || null
    query.endTime = filters.timeRange?.[1] || null
    query.page = 1
    void fetchLibrary()
  }

  onMounted(async () => {
    await Promise.allSettled([
      fetchLibrary(),
      getUserList({ page: 1, pageSize: 100 }).then((response) => { if (!disposed) users.value = response.data.list || [] }),
      getTrackList({ page: 1, pageSize: 10, analysisStatuses: '1' }).then((response) => {
        if (!disposed) recoverTracks((response.data.list || []).map(normalizeTrack))
      })
    ])
  })

  onUnmounted(() => {
    disposed = true
    listVersion++
    stopTimer()
  })

  return {
    tracks, users, batches, selectedTracks, activeStatus, tabs, query, total,
    loading, submitting, polling, hasRunning, listError, syncError, submitError,
    toggleSelection, removeSelection, clearSelection, startSelected, loadEvents,
    openTrack, fetchLibrary, refreshWorkspace, changeTab, changePage, applyFilters
  }
}
