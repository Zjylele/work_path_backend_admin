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
export function askPolicyClause(question, topK) {
  return request.post('/admin/policy/clauses/ask', { question, topK })
}

// 预览文档内容（原文优先；加列前的存量文档返回条款拼合，source 标注来源）
export function previewPolicyDocument(id) {
  return request.get(`/admin/policy/documents/${id}/preview`)
}

// 上传文档文件（.txt / .md / .docx）：服务端自动提取原文后走同一套切分链路。
// 不手动设置 Content-Type——axios 检测到 FormData 会自动带上 multipart boundary
export function uploadPolicyFile(file, title, category) {
  const form = new FormData()
  form.append('file', file)
  if (title) form.append('title', title)
  if (category) form.append('category', category)
  return request.post('/admin/policy/documents/upload-file', form)
}
