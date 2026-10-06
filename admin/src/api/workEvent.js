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
// 撤销确认（已确认 → 待确认；存在未闭环工单时后端拒绝）
export function revokeWorkEvent(id) {
  return request.post(`/admin/work-events/${id}/revoke`)
}
export function reanalyzeTrack(trackId) {
  return request.post(`/admin/work-events/tracks/${trackId}/reanalyze`)
}
// 某条轨迹的全部工作事件（分析详情页：轨迹线 + 事件时序同屏）
export function getTrackWorkEvents(trackId) {
  return request.get(`/admin/work-events/tracks/${trackId}`)
}
