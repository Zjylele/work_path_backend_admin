import request from '@/utils/request'

// 合规核查结论与整改工单（M5-W6/W7）
export function getChecks(params) {
  return request.get('/admin/compliance-checks', { params })
}
export function scanAbnormalTracks() {
  return request.post('/admin/compliance-checks/scan-abnormal')
}
// 条款引用有效率统计（分母只算模型实际给出引用的条数）
export function getCheckStats() {
  return request.get('/admin/compliance-checks/stats')
}
export function createCheckOrder(id) {
  return request.post(`/admin/compliance-checks/${id}/order`)
}
export function getOrders(status) {
  return request.get('/admin/orders', { params: { status } })
}
export function closeOrder(id, reviewNote) {
  return request.post(`/admin/orders/${id}/close`, { reviewNote })
}
export function reviewOrder(id, accept, reviewNote) {
  return request.post(`/admin/orders/${id}/review`, { accept, reviewNote })
}
