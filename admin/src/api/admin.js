import request from '@/utils/request'

// 数据统计（仪表盘）
export function getStats() {
  return request.get('/admin/stats')
}

// 仪表盘最近动态（轨迹/待复核事件/日志/制度文档各最多 10 条）
export function getDashboardRecent() {
  return request.get('/admin/dashboard/recent')
}

// 用户管理
export function getUserList(params) {
  return request.get('/admin/users', { params })
}
export function updateUserStatus(id, status) {
  return request.put(`/admin/users/${id}/status`, { status })
}
export function resetUserPassword(id, password) {
  return request.put(`/admin/users/${id}/password`, { password })
}
export function deleteUser(id) {
  return request.delete(`/admin/users/${id}`)
}
export function changeUserRole(id, role) {
  return request.put(`/admin/users/${id}/role`, { role })
}

// 轨迹管理
export function getTrackList(params) {
  return request.get('/admin/tracks', { params })
}
export function getTrackDetail(id) {
  return request.get(`/admin/tracks/${id}`)
}
export function deleteTrack(id) {
  return request.delete(`/admin/tracks/${id}`)
}

// 操作日志
export function getLogList(params) {
  return request.get('/admin/logs', { params })
}
export function deleteLog(id) {
  return request.delete(`/admin/logs/${id}`)
}
export function deleteLogs(ids) {
  return request.delete('/admin/logs', { data: ids })
}
