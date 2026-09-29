import request from '@/utils/request'

// 制度知识库（M6）：文档上传、条款查看、语义检索

export function getPolicyDocuments(category) {
  return request.get('/admin/policy/documents', { params: { category } })
}
export function uploadPolicyDocument(data) {
  return request.post('/admin/policy/documents', data)
}
export function getPolicyClauses(id) {
  return request.get(`/admin/policy/documents/${id}/clauses`)
}
export function deletePolicyDocument(id) {
  return request.delete(`/admin/policy/documents/${id}`)
}
export function reindexPolicyDocument(id) {
  return request.post(`/admin/policy/documents/${id}/reindex`)
}
export function searchPolicyClauses(query, topK) {
  return request.get('/admin/policy/clauses/search', { params: { query, topK } })
}
