import request from '@/utils/request'

// 模型接入配置（语言模型 / 向量化 / 逆地理编码）
export function getModelProviders() {
  return request.get('/admin/model-providers')
}
export function getModelProvider(purpose) {
  return request.get(`/admin/model-providers/${purpose}`)
}
export function saveModelProvider(data) {
  return request.post('/admin/model-providers', data)
}

// 测试连接：data 需含 baseUrl / modelName / apiKey（测试的是待保存值，不要求先保存）
export function testModelProvider(purpose, data) {
  return request.post(`/admin/model-providers/${purpose}/test`, data)
}
