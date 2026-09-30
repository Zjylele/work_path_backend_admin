import request from '@/utils/request'

// 合规规则（M5-W5）：规则配置管理
export function getRules(enabled) {
  return request.get('/admin/rules', { params: { enabled } })
}
export function createRule(data) {
  return request.post('/admin/rules', data)
}
export function updateRule(id, data) {
  return request.put(`/admin/rules/${id}`, data)
}
export function deleteRule(id) {
  return request.delete(`/admin/rules/${id}`)
}
