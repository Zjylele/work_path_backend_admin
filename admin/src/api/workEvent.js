import request from '@/utils/request'

// 工作事件（M4 产出的人工复核）
export function getWorkEvents(confirmStatus, page = 1, pageSize = 10) {
  return request.get('/admin/work-events', { params: { confirmStatus, page, pageSize } })
}
export function getWorkEvent(id) {
  return request.get(`/admin/work-events/${id}`)
}
export function confirmWorkEvent(id) {
  return request.post(`/admin/work-events/${id}/confirm`)
}
export function rejectWorkEvent(id) {
  return request.post(`/admin/work-events/${id}/reject`)
}
export function reanalyzeTrack(trackId) {
  return request.post(`/admin/work-events/tracks/${trackId}/reanalyze`)
}
