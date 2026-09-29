<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        制度文档是合规核查的依据。上传后按条款编号自动切分并计算语义向量；
        同名文档再次上传会生成新版本，旧版本停用但保留，便于回溯当时的核查依据。
      </template>
    </el-alert>

    <el-row :gutter="16">
      <!-- 左：上传 + 文档列表 -->
      <el-col :span="14">
        <el-card shadow="never" style="margin-bottom: 16px">
          <template #header><span class="card-title">上传制度文档</span></template>
          <el-form :model="form" label-width="80px">
            <el-form-item label="文档标题">
              <el-input v-model="form.title" placeholder="如 外勤人员管理制度" />
            </el-form-item>
            <el-form-item label="分类">
              <el-input v-model="form.category" placeholder="如 考勤 / 行为规范" />
            </el-form-item>
            <el-form-item label="制度原文">
              <el-input
                v-model="form.content"
                type="textarea"
                :rows="10"
                placeholder="按条款编号逐行粘贴，如「1.1 外勤人员因公外出应提前报备」。没有编号的段落会按顺序生成 AUTO 编号。"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="uploading" @click="handleUpload">
                上传并切分
              </el-button>
              <el-button @click="Object.assign(form, blank())">清空</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">制度文档</span></template>
          <el-table v-loading="listLoading" :data="documents" stripe>
            <el-table-column prop="version" label="版本" width="80" />
            <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
            <el-table-column prop="category" label="分类" width="100">
              <template #default="{ row }">{{ row.category || '-' }}</template>
            </el-table-column>
            <el-table-column label="上传时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewClauses(row)">查看条款</el-button>
                <el-button link type="warning" @click="handleReindex(row)">重新向量化</el-button>
                <el-button link type="danger" @click="handleDisable(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 右：问答 + 检索 + 条款 -->
      <el-col :span="10">
        <el-card shadow="never" style="margin-bottom: 16px">
          <template #header><span class="card-title">制度问答</span></template>
          <el-input
            v-model="askQuestion"
            placeholder="用自然语言提问，如 外出需要提前报备吗"
            clearable
            @keyup.enter="handleAsk"
          >
            <template #append>
              <el-button :loading="asking" @click="handleAsk">提问</el-button>
            </template>
          </el-input>
          <div v-if="answer" class="answer">
            <div class="answer-head">
              <el-tag :type="answer.answered ? 'success' : 'info'" size="small">
                {{ answer.answered ? '已依据制度作答' : '未找到依据' }}
              </el-tag>
            </div>
            <div class="answer-text">{{ answer.text }}</div>
            <div v-if="answer.citations && answer.citations.length" class="answer-cites">
              <div class="answer-cites-title">依据条款（{{ answer.citations.length }}）</div>
              <div v-for="c in answer.citations" :key="c.clauseId" class="cite">
                <span class="cite-code">{{ c.clauseCode }}</span>
                <span class="cite-doc">{{ c.docTitle }} {{ c.docVersion }}</span>
                <span class="cite-score">相似度 {{ (c.score ?? 0).toFixed(3) }}</span>
                <div class="cite-content">{{ c.content }}</div>
              </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">条款检索</span></template>

          <el-input
            v-model="searchQuery"
            placeholder="用自然语言描述要找的规定，如 外出需要提前报备吗"
            clearable
            @keyup.enter="handleSearch"
          >
            <template #append>
              <el-button :loading="searching" @click="handleSearch">检索</el-button>
            </template>
          </el-input>
          <div v-if="searched" class="search-result">
            <div v-if="hits.length === 0" class="empty">没有匹配的条款</div>
            <div v-for="c in hits" :key="c.clauseId" class="hit">
              <div class="hit-head">
                <el-tag size="small" type="primary">{{ c.clauseCode }}</el-tag>
                <span class="hit-doc">{{ c.docTitle }}（{{ c.docVersion }}）</span>
                <span class="hit-score">相似度 {{ (c.score ?? 0).toFixed(3) }}</span>
              </div>
              <div class="hit-content">{{ c.content }}</div>
            </div>
          </div>
        </el-card>

        <el-card v-if="currentDoc" shadow="never">
          <template #header>
            <div class="card-header">
              <span class="card-title">{{ currentDoc.title }} · {{ currentDoc.version }}</span>
              <el-button link @click="currentDoc = null">关闭</el-button>
            </div>
          </template>
          <el-table v-loading="clauseLoading" :data="clauses" size="small">
            <el-table-column prop="clauseCode" label="编号" width="90" />
            <el-table-column label="来源" width="80">
              <template #default="{ row }">
                <el-tag :type="row.codeSource === 1 ? 'success' : 'info'" size="small">
                  {{ row.codeSource === 1 ? '原文' : '生成' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="向量" width="70">
              <template #default="{ row }">
                <el-tooltip
                  :content="row.vectorModel ? '模型：' + row.vectorModel : '未向量化，可点重新向量化补齐'"
                  placement="top"
                >
                  <el-tag :type="row.vectorData ? 'success' : 'danger'" size="small">
                    {{ row.vectorData ? '有' : '无' }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="条款内容" min-width="220" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPolicyDocuments,
  uploadPolicyDocument,
  getPolicyClauses,
  deletePolicyDocument,
  reindexPolicyDocument,
  searchPolicyClauses,
  askPolicyClause
} from '@/api/policy'

const documents = ref([])
const listLoading = ref(false)
const uploading = ref(false)
const searching = ref(false)
const asking = ref(false)
const askQuestion = ref('')
const answer = ref(null)
const clauseLoading = ref(false)

const form = reactive(blank())
const currentDoc = ref(null)
const clauses = ref([])
const searchQuery = ref('')
const hits = ref([])
const searched = ref(false)

function blank() {
  return { title: '', category: '', content: '' }
}

async function loadDocuments() {
  listLoading.value = true
  try {
    const res = await getPolicyDocuments()
    documents.value = res.data || []
  } finally {
    listLoading.value = false
  }
}

async function handleUpload() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('标题和制度原文不能为空')
    return
  }
  uploading.value = true
  try {
    const res = await uploadPolicyDocument({ ...form })
    ElMessage.success(`已上传 ${res.data.version}，文档 ID ${res.data.docId}`)
    Object.assign(form, blank())
    await loadDocuments()
  } catch {
    // 失败原因已由 request 拦截器提示
  } finally {
    uploading.value = false
  }
}

async function viewClauses(row) {
  currentDoc.value = row
  clauseLoading.value = true
  try {
    const res = await getPolicyClauses(row.docId)
    clauses.value = res.data || []
  } finally {
    clauseLoading.value = false
  }
}

async function handleReindex(row) {
  try {
    await ElMessageBox.confirm(
      `重新计算「${row.title}」缺失或模型已变更条款的向量？`,
      '重新向量化',
      { type: 'warning', confirmButtonText: '开始', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await reindexPolicyDocument(row.docId)
    ElMessage.success(`已更新 ${res.data.updated} 条向量`)
    await viewClauses(row)
  } catch {
    // 失败原因已由 request 拦截器提示
  }
}

async function handleDisable(row) {
  try {
    await ElMessageBox.confirm(
      `停用后该文档及其条款不再参与检索（历史版本仍保留在库中）。确定停用「${row.title} ${row.version}」吗？`,
      '停用制度文档',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await deletePolicyDocument(row.docId)
    ElMessage.success('已停用')
    if (currentDoc.value && currentDoc.value.docId === row.docId) {
      currentDoc.value = null
      clauses.value = []
    }
    await loadDocuments()
  } catch {
    // 失败原因已由 request 拦截器提示
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searching.value = true
  try {
    const res = await searchPolicyClauses(searchQuery.value.trim(), 5)
    hits.value = res.data || []
    searched.value = true
  } catch {
    // 向量化失败等原因由拦截器提示；此时保留上一次结果没有意义
    searched.value = false
  } finally {
    searching.value = false
  }
}

async function handleAsk() {
  if (!askQuestion.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  asking.value = true
  try {
    const res = await askPolicyClause(askQuestion.value.trim(), 5)
    answer.value = res.data
  } catch {
    // 失败原因已由 request 拦截器提示；保留上一次结果没有意义
    answer.value = null
  } finally {
    asking.value = false
  }
}

function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(loadDocuments)
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.search-result {
  margin-top: 12px;
}
.answer {
  margin-top: 12px;
}
.answer-head {
  margin-bottom: 6px;
}
.answer-text {
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}
.answer-cites {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
}
.answer-cites-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.cite {
  border-left: 3px solid #409eff;
  padding: 6px 10px;
  margin-bottom: 8px;
  background: #f5f7fa;
}
.cite-code {
  font-weight: 600;
  font-size: 12px;
  color: #409eff;
  margin-right: 8px;
}
.cite-doc {
  font-size: 12px;
  color: #606266;
  margin-right: 8px;
}
.cite-score {
  font-size: 12px;
  color: #909399;
}
.cite-content {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}
.empty {
  color: #909399;
  font-size: 13px;
  text-align: center;
  padding: 16px 0;
}
.hit {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.hit-doc {
  font-size: 12px;
  color: #606266;
  flex: 1;
}
.hit-score {
  font-size: 12px;
  color: #909399;
}
.hit-content {
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}
</style>
