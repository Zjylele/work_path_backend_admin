export const MAX_BATCH_SIZE = 10

export const STATUS_TABS = [
  { value: 0, label: '待分析' },
  { value: 1, label: '进行中' },
  { value: 2, label: '已完成' },
  { value: 3, label: '失败' }
]

export function statusText(status) {
  return { 0: '待分析', 1: '排队或分析中', 2: '已完成', 3: '分析失败' }[status] || '状态不可用'
}

export function statusType(status) {
  return { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }[status] || 'info'
}

export function formatTime(value) {
  if (!value) return '时间未记录'
  return String(value).replace('T', ' ').slice(0, 16)
}

export function formatDistance(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value)) || Number(value) < 0) return ''
  const meters = Number(value)
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${Math.round(meters)} m`
}

export function formatDuration(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value)) || Number(value) < 0) return '时长未记录'
  const seconds = Math.round(Number(value))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return seconds % 60 ? `${minutes} 分 ${seconds % 60} 秒` : `${minutes} 分钟`
}

export function confidenceText(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return null
  const confidence = Number(value)
  if (confidence < 0 || confidence > 1) return null
  return `${Math.round(confidence * 100)}%`
}

export function traceSections(trace) {
  if (typeof trace !== 'string') return []
  const names = { params: '分析参数', input: '输入数据', poi: '地点识别', llm: '模型原始输出', parsed: '解析结果' }
  return trace.split(/\r?\n/).map((line) => {
    const match = line.match(/^(params|input|poi|llm|parsed):\s*(.*)$/)
    return match ? { label: names[match[1]], value: match[2].replace(/^raw=/, '') } : null
  }).filter(Boolean)
}

export function batchStats(batch) {
  const items = batch.items
  const events = items.filter((item) => item.eventsLoaded).flatMap((item) => item.events)
  return {
    running: items.filter((item) => item.analysisStatus === 1).length,
    completed: items.filter((item) => item.analysisStatus === 2).length,
    failed: items.filter((item) => item.analysisStatus === 3 || item.analysisStatus === -1).length,
    resultsPending: items.some((item) => item.analysisStatus === 2 && !item.eventsLoaded),
    events: events.length,
    pendingReview: events.filter((event) => Number(event.confirmStatus) === 0 && event.confirmStatus != null).length
  }
}
