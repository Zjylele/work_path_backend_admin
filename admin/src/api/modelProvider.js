import request from '@/utils/request'

// 模型接入（一用途多模型）：三个用途（语言/检索/逆）各自可挂多条配置，
// selected=1 的是「当前使用」，切换走 /select
export function getModelProviders() {
  return request.get('/admin/model-providers')
}
export function createModelProvider(data) {
  return request.post('/admin/model-providers', data)
}
export function updateModelProvider(id, data) {
  return request.put(`/admin/model-providers/${id}`, data)
}
export function deleteModelProvider(id) {
  return request.delete(`/admin/model-providers/${id}`)
}
export function selectModelProvider(id) {
  return request.post(`/admin/model-providers/${id}/select`)
}
// 测试已保存模型的连通性；data 可省略（省略时测库里保存的值）
export function testModelProviderById(id, data) {
  return request.post(`/admin/model-providers/${id}/test`, data || {})
}
