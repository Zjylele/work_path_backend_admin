<template>
  <main class="analysis-output" aria-label="AI 分析输出">
    <header class="output-heading">
      <div class="output-title"><span class="ai-mark"><el-icon><MagicStick /></el-icon></span><div><h1>AI 轨迹分析</h1><p>{{ batches.length ? `本次会话 · ${batches.length} 组记录` : '分析工作区' }}<template v-if="hasRunning"> · 正在等待分析结果</template></p></div></div>
      <el-button class="refresh-button" :icon="Refresh" :loading="polling || loading" @click="$emit('refresh')">刷新状态</el-button>
    </header>

    <div v-if="syncError" class="sync-warning" role="alert"><el-icon><Warning /></el-icon><span>{{ syncError }}</span><el-button text type="primary" size="small" @click="$emit('refresh')">重新同步</el-button></div>

    <div ref="scrollArea" class="message-scroll" role="log" aria-label="轨迹分析记录" @scroll="onScroll">
      <div v-if="!batches.length" class="welcome">
        <div class="welcome-icon"><el-icon><MagicStick /></el-icon></div>
        <h2>让轨迹变成可读的工作记录</h2>
        <p>在左侧选择需要分析的轨迹，点击“发送给 AI 分析”。<br>识别到的事件、模型判定及其依据将在这里显示。</p>
        <div class="welcome-steps"><span><b>1</b> 选择轨迹</span><el-icon><ArrowRight /></el-icon><span><b>2</b> 发起分析</span><el-icon><ArrowRight /></el-icon><span><b>3</b> 人工复核</span></div>
      </div>

      <section v-for="batch in batches" :key="batch.id" class="analysis-batch" :aria-label="`分析记录 ${batch.id}`">
        <div class="batch-divider"><span>{{ batch.kind === 'saved' ? '查看已保存结果' : batch.kind === 'recovered' ? '恢复进行中的分析' : '提交分析' }} · {{ batch.createdAt }}</span></div>
        <template v-if="batch.kind === 'submitted'">
          <div class="submission"><strong>已提交 {{ batch.items.length }} 条轨迹进行分析</strong><div class="submission-tracks"><span v-for="item in batch.items" :key="item.id"><el-icon><Position /></el-icon>{{ item.name || '未命名轨迹' }} <small>#{{ item.id }}</small></span></div></div>
          <div class="submission-caption">管理员 · 已发送</div>
        </template>

        <div class="assistant-message"><span class="assistant-avatar"><el-icon><MagicStick /></el-icon></span><div class="assistant-content">
          <div class="assistant-name">WorkPath AI <span>· 分析结果</span></div>
          <h2>{{ batchHeading(batch) }}</h2>
          <p class="assistant-intro">{{ batchStats(batch).running ? '每条轨迹完成后会在下方显示结果，当前状态由服务端同步。' : '以下展示已保存的工作事件、模型标签与判定依据，可进入事件复核继续处理。' }}</p>

          <div class="batch-stats" aria-label="本组结果统计">
            <div><b>{{ batchStats(batch).completed }}<small> / {{ batch.items.length }}</small></b><span>轨迹已完成</span></div>
            <div v-if="batchStats(batch).running"><b>{{ batchStats(batch).running }}</b><span>排队或分析中</span></div>
            <div v-if="batchStats(batch).failed"><b class="failed-number">{{ batchStats(batch).failed }}</b><span>未成功</span></div>
            <div><b>{{ batchStats(batch).events }}<small v-if="batchStats(batch).resultsPending"> 已读取</small></b><span>识别事件</span></div>
            <div><b>{{ batchStats(batch).pendingReview }}</b><span>等待人工复核</span></div>
          </div>

          <article v-for="item in batch.items" :key="item.id" class="result-card" :data-track-id="item.id">
            <div class="result-heading"><div><el-icon><Position /></el-icon><strong>{{ item.name || '未命名轨迹' }}</strong><span>#{{ item.id }}</span></div><el-tag size="small" :type="statusType(item.analysisStatus)" effect="light">{{ statusText(item.analysisStatus) }}</el-tag></div>
            <div class="track-summary">{{ item.username || '员工未记录' }} · {{ formatTime(item.startTime) }}<template v-if="item.pointCount != null"> · {{ item.pointCount }} 个轨迹点</template><template v-if="formatDistance(item.distance)"> · {{ formatDistance(item.distance) }}</template></div>

            <div v-if="item.analysisStatus === 1" class="processing" role="status"><el-icon class="is-loading"><Loading /></el-icon><span>{{ syncError ? '状态同步已暂停，分析仍可能在服务端继续' : '轨迹已提交，排队或分析中…' }}</span></div>
            <div v-else-if="item.analysisStatus === 3 || item.analysisStatus === -1" class="result-error" role="status"><el-icon><Warning /></el-icon><div><p>{{ item.statusError || '轨迹分析失败，当前没有可展示的新结果。' }}</p><el-button v-if="item.analysisStatus === 3" text type="primary" size="small" @click="$emit('show-failed')">前往失败列表重新选择</el-button></div></div>

            <template v-else-if="item.analysisStatus === 2">
              <div v-if="item.eventsLoading" class="processing" role="status"><el-icon class="is-loading"><Loading /></el-icon><span>分析已完成，正在读取结果…</span></div>
              <div v-else-if="item.resultError" class="result-error" role="alert"><el-icon><Warning /></el-icon><div><p>{{ item.resultError }}</p><el-button text type="primary" size="small" @click="$emit('retry', item)">重新加载结果</el-button></div></div>
              <div v-else-if="item.eventsLoaded && !item.events.length" class="no-events"><el-icon><CircleCheck /></el-icon><span>分析已完成，未识别到可复核的停留事件。</span></div>
              <div v-else-if="!item.eventsLoaded" class="processing"><span>结果尚未读取。</span><el-button text type="primary" size="small" @click="$emit('retry', item)">加载结果</el-button></div>
              <div v-else class="event-list">
                <section v-for="event in item.events" :key="event.eventId" class="event-result">
                  <div class="event-time">{{ formatTime(event.startTime) }} 至 {{ formatTime(event.endTime) }}<span>停留 {{ formatDuration(event.dwellSeconds) }}</span></div>
                  <div class="event-label"><strong>{{ event.poiName || '地点未识别' }}</strong><el-tag size="small" :type="event.semanticLabel ? 'primary' : 'info'">{{ event.semanticLabel || '未标注' }}</el-tag><span v-if="confidenceText(event.confidence)" class="confidence">置信度 {{ confidenceText(event.confidence) }}</span></div>
                  <p class="event-reason"><span>判定说明</span>{{ event.aiReason || '本事件没有保存判定说明，请结合原始轨迹与依据复核。' }}</p>
                  <details v-if="event.analysisTrace" class="event-evidence"><summary>模型原始输出与解析依据</summary><dl v-if="traceSections(event.analysisTrace).length"><template v-for="(section, index) in traceSections(event.analysisTrace)" :key="index"><dt>{{ section.label }}</dt><dd>{{ section.value }}</dd></template></dl><details class="full-trace"><summary>查看完整依据记录</summary><pre>{{ event.analysisTrace }}</pre></details></details>
                  <div class="event-review-state"><span :class="{ pending: event.confirmStatus === 0 }">{{ reviewText(event.confirmStatus) }}</span><small>事件 #{{ event.eventId }}</small></div>
                </section>
              </div>
              <div v-if="item.eventsLoaded && item.events.length" class="review-action"><span>结果已保存，可查看与处理复核状态</span><el-button size="small" type="primary" plain @click="$emit('review', item.id)">进入事件复核 <el-icon><TopRight /></el-icon></el-button></div>
            </template>
          </article>
          <div v-if="!batchStats(batch).running && !batchStats(batch).failed && !batchStats(batch).resultsPending" class="batch-finished"><el-icon><CircleCheck /></el-icon>本组结果已读取完毕，AI 结论需经人工复核</div>
        </div></div>
      </section>
    </div>

    <button v-if="unreadUpdates" class="new-results" type="button" @click="scrollToBottom">有新的分析状态或结果 <el-icon><ArrowDown /></el-icon></button>
    <footer class="output-footer"><span><i :class="{ busy: hasRunning, paused: syncError }"></i>{{ syncError ? '状态同步已暂停' : hasRunning ? '等待服务端分析结果' : batches.length ? '分析记录已更新' : '等待选择轨迹' }}</span><span>AI 结论需经人工复核</span></footer>
  </main>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { ArrowDown, ArrowRight, CircleCheck, Loading, MagicStick, Position, Refresh, TopRight, Warning } from '@element-plus/icons-vue'
import { batchStats, confidenceText, formatDistance, formatDuration, formatTime, statusText, statusType, traceSections } from './analysisDisplay'

const props = defineProps({ batches: { type: Array, default: () => [] }, hasRunning: Boolean, polling: Boolean, loading: Boolean, syncError: String })
defineEmits(['refresh', 'retry', 'review', 'show-failed'])
const scrollArea = ref(null)
const followsBottom = ref(true)
const unreadUpdates = ref(false)

function onScroll() {
  const area = scrollArea.value
  if (!area) return
  followsBottom.value = area.scrollHeight - area.scrollTop - area.clientHeight < 80
  if (followsBottom.value) unreadUpdates.value = false
}

function scrollToBottom() {
  if (!scrollArea.value) return
  scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  followsBottom.value = true
  unreadUpdates.value = false
}

async function focusTrack(id) {
  await nextTick()
  const area = scrollArea.value
  if (!area) return
  const target = [...area.querySelectorAll('[data-track-id]')].filter(el => el.dataset.trackId === String(id)).at(-1)
  if (target) {
    area.scrollTop += target.getBoundingClientRect().top - area.getBoundingClientRect().top - 16
    onScroll()
  }
}

function batchHeading(batch) {
  const stats = batchStats(batch)
  if (stats.running) return '正在处理本批轨迹'
  if (stats.failed) return '本组分析已结束，部分轨迹未成功'
  if (stats.resultsPending) return '轨迹分析已完成，部分结果待加载'
  return batch.kind === 'saved' ? '已保存的轨迹分析结果' : '已完成本批轨迹分析'
}

function reviewText(status) {
  return { 0: '待人工确认', 1: '已人工确认', 2: '已驳回' }[status] || '复核状态未记录'
}

watch(() => props.batches.map(batch => `${batch.id}:${batch.items.map(item => `${item.id}:${item.analysisStatus}:${item.eventsLoaded}:${item.eventsLoading}:${item.resultError}`).join(',')}`).join('|'), async () => {
  const shouldFollow = followsBottom.value
  await nextTick()
  if (shouldFollow) scrollToBottom()
  else unreadUpdates.value = true
})

defineExpose({ focusTrack })
</script>

<style scoped>
.analysis-output { flex: 1; min-width: 0; min-height: 0; position: relative; display: flex; flex-direction: column; background: #fff; }
.output-heading { padding: 20px 26px; display: flex; justify-content: space-between; gap: 12px; align-items: center; border-bottom: 1px solid #edf0f5; }
.output-title { display: flex; gap: 11px; align-items: center; }
.ai-mark, .assistant-avatar, .welcome-icon { display: grid; place-items: center; color: #4974ce; background: #eff4ff; border: 1px solid #e4ebfb; }
.ai-mark { width: 36px; height: 36px; border-radius: 10px; font-size: 20px; flex-shrink: 0; }
.output-title h1 { margin: 0 0 6px; font-weight: 600; font-size: 16px; color: #30435d; }
.output-title p { font-size: 11px; color: #94a1b3; line-height: 1.5; }
.refresh-button { color: #7c8da5; border-color: #e3e8f0; border-radius: 6px; font-size: 12px; }
.message-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 24px clamp(20px, 4%, 60px); background: linear-gradient(#fafcff 0, #fff 220px); overscroll-behavior: contain; }
.welcome { display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; height: 100%; min-height: 350px; }
.welcome-icon { width: 64px; height: 64px; border-radius: 19px; font-size: 32px; margin-bottom: 23px; }
.welcome h2 { font-size: 21px; font-weight: 600; margin-bottom: 14px; color: #344962; }
.welcome p { font-size: 12px; line-height: 2; color: #8f9db1; }
.welcome-steps { display: flex; align-items: center; gap: 15px; margin-top: 30px; color: #a0aec0; font-size: 11px; }
.welcome-steps span { white-space: nowrap; }
.welcome-steps b { display: inline-grid; place-items: center; width: 19px; height: 19px; border-radius: 50%; background: #f1f5fc; font-size: 10px; font-weight: 500; margin-right: 5px; color: #7392bb; }
.analysis-batch { max-width: 900px; margin: 0 auto 38px; }
.batch-divider { display: flex; justify-content: center; margin: 0 0 23px; font-size: 10px; color: #a4afbe; }
.submission { max-width: 600px; width: fit-content; margin-left: auto; background: #edf4ff; border: 1px solid #e0e9f9; border-radius: 12px 12px 3px 12px; padding: 14px 17px; color: #4d6587; font-size: 12px; }
.submission strong { font-weight: 500; line-height: 1.8; }
.submission-tracks { display: flex; flex-direction: column; gap: 6px; border-top: 1px solid #dce7f6; margin-top: 9px; padding-top: 10px; font-size: 11px; }
.submission-tracks > span { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; overflow-wrap: anywhere; }
.submission-tracks small { color: #92a3bb; font-size: 10px; }
.submission-caption { text-align: right; color: #a0acbc; font-size: 10px; margin: 8px 0 27px; }
.assistant-message { display: flex; gap: 12px; }
.assistant-avatar { width: 30px; height: 30px; border-radius: 9px; flex-shrink: 0; font-size: 17px; }
.assistant-content { flex: 1; min-width: 0; }
.assistant-name { font-size: 11px; font-weight: 600; color: #657b9b; margin: 7px 0 17px; }
.assistant-name span { color: #9ba9bb; font-weight: 400; margin-left: 6px; }
.assistant-content h2 { font-size: 16px; font-weight: 600; color: #354963; margin: 0 0 10px; }
.assistant-intro { font-size: 12px; line-height: 1.9; color: #8593a8; margin-bottom: 16px; }
.batch-stats { display: flex; flex-wrap: wrap; gap: 15px 0; background: #f8fafd; border: 1px solid #e9eef5; border-radius: 8px; padding: 14px 0; margin-bottom: 20px; }
.batch-stats > div { padding: 0 18px; border-right: 1px solid #e4eaf3; }
.batch-stats > div:last-child { border-right: 0; }
.batch-stats b { display: block; color: #405773; font-size: 21px; line-height: 1.3; font-weight: 600; margin-bottom: 5px; }
.batch-stats b small { font-size: 10px; color: #9ba9bb; font-weight: 400; }
.batch-stats span { font-size: 10px; color: #94a2b6; }
.batch-stats b.failed-number { color: #b86e70; }
.result-card { border: 1px solid #e4eaf2; border-radius: 9px; margin-bottom: 16px; overflow: hidden; scroll-margin-top: 16px; }
.result-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; background: #fbfcfe; padding: 13px 15px 8px; }
.result-heading > div { display: flex; gap: 7px; align-items: baseline; flex-wrap: wrap; min-width: 0; }
.result-heading .el-icon { color: #8c9fb7; align-self: center; }
.result-heading strong { font-size: 12px; color: #415672; font-weight: 600; overflow-wrap: anywhere; }
.result-heading div > span { color: #a3afbf; font-size: 10px; }
.result-heading :deep(.el-tag) { flex-shrink: 0; font-size: 10px; }
.track-summary { background: #fbfcfe; color: #98a6b9; font-size: 10px; padding: 0 15px 12px; line-height: 1.8; border-bottom: 1px solid #edf0f5; }
.processing, .no-events, .result-error { padding: 21px 16px; display: flex; align-items: flex-start; gap: 9px; color: #8194ae; font-size: 12px; line-height: 1.8; }
.processing > .el-icon, .result-error > .el-icon, .no-events > .el-icon { flex-shrink: 0; margin-top: 4px; }
.processing { align-items: center; }
.processing > .el-icon { margin-top: 0; }
.no-events { color: #6b9185; }
.result-error { color: #b37676; background: #fffdfd; }
.result-error :deep(.el-button) { padding-left: 0; margin-top: 4px; }
.event-result { padding: 15px; border-bottom: 1px solid #edf0f5; }
.event-result:last-child { border-bottom: 0; }
.event-time { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 10px; line-height: 1.8; color: #95a5b9; margin-bottom: 10px; }
.event-label { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; margin-bottom: 12px; }
.event-label strong { color: #405873; font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.event-label :deep(.el-tag) { height: auto; min-height: 20px; white-space: normal; max-width: 100%; }
.event-label :deep(.el-tag__content) { overflow-wrap: anywhere; }
.confidence { font-size: 10px; color: #96a3b6; }
.event-reason { font-size: 12px; line-height: 1.9; color: #71839e; overflow-wrap: anywhere; }
.event-reason > span { font-size: 10px; color: #a4afc0; margin-right: 8px; }
.event-evidence { margin-top: 13px; padding-top: 11px; border-top: 1px solid #edf0f5; }
summary { cursor: pointer; color: #7891b2; font-size: 11px; line-height: 1.8; }
summary:focus-visible { outline: 2px solid #71a0ea; outline-offset: 3px; }
dl { margin-top: 12px; background: #f7f9fc; padding: 12px; border-radius: 6px; }
dt { font-size: 10px; color: #9caabf; margin-bottom: 4px; }
dd { font-size: 11px; color: #7286a1; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; margin: 0 0 10px; }
dd:last-child { margin-bottom: 0; }
.full-trace { margin-top: 9px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-size: 11px; background: #f7f9fc; color: #8393aa; padding: 12px; margin-top: 8px; border-radius: 6px; }
.event-review-state { display: flex; justify-content: space-between; margin-top: 12px; font-size: 10px; color: #9faabb; }
.event-review-state .pending { color: #ba9659; }
.event-review-state small { font-size: 10px; }
.review-action { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 15px; border-top: 1px solid #edf0f5; }
.review-action > span { color: #9ba7b9; font-size: 10px; line-height: 1.6; }
.review-action :deep(.el-button) { font-size: 11px; flex-shrink: 0; }
.review-action .el-icon { margin-left: 5px; }
.batch-finished { display: flex; gap: 7px; align-items: center; color: #7e9f91; font-size: 11px; line-height: 1.8; }
.output-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; border-top: 1px solid #edf0f5; color: #9aa8bb; font-size: 10px; padding: 14px 24px; background: #fcfdff; }
.output-footer i { display: inline-block; width: 5px; height: 5px; background: #73ac9a; border-radius: 100%; margin-right: 6px; vertical-align: middle; }
.output-footer i.busy { background: #d0ad6b; }
.output-footer i.paused { background: #bd8a8a; }
.sync-warning { display: flex; align-items: center; gap: 8px; padding: 9px 20px; background: #fff8ea; color: #a1814e; font-size: 11px; line-height: 1.7; }
.sync-warning span { flex: 1; }
.new-results { position: absolute; bottom: 55px; left: 50%; transform: translateX(-50%); display: flex; align-items: center; gap: 7px; padding: 9px 14px; border: 1px solid #dfe7f5; border-radius: 20px; background: #fff; color: #577cb5; box-shadow: 0 4px 18px #304f7912; font-size: 11px; cursor: pointer; white-space: nowrap; }
@media (max-width: 1200px) { .output-heading { padding: 18px; } .message-scroll { padding: 22px 18px; } .batch-stats > div { padding: 0 13px; } }
@media (max-width: 850px) { .analysis-output { min-height: 580px; } .message-scroll { min-height: 420px; max-height: 75vh; } }
@media (max-width: 500px) { .output-heading { padding: 16px 12px; } .output-title h1 { font-size: 14px; } .refresh-button { padding: 8px; font-size: 11px; } .message-scroll { padding: 20px 12px; } .assistant-message { gap: 8px; } .assistant-avatar { width: 25px; height: 25px; font-size: 14px; } .assistant-content h2 { font-size: 14px; line-height: 1.8; } .result-heading { flex-direction: column; gap: 8px; } .batch-stats { gap: 14px 0; } .batch-stats > div { padding: 0 10px; } .batch-stats b { font-size: 18px; } .review-action { align-items: flex-start; flex-direction: column; } .welcome h2 { font-size: 17px; } .welcome-steps { gap: 6px; font-size: 10px; } .output-footer { padding: 12px; flex-wrap: wrap; } }
@media (prefers-reduced-motion: reduce) { .is-loading { animation: none; } }
</style>
