<template>
  <div>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #title>
        本页存放会产生真实费用的 API 凭据，仅超级管理员可修改。密钥只显示后 4 位，
        保存时若不重新填写密钥则保持原值不变。同一用途可添加多个模型，切换「当前使用」即时生效。
      </template>
    </el-alert>

    <!-- 三个用途子表格：当前使用的模型 + 下拉切换 -->
    <el-card
      v-for="p in purposes"
      :key="p.value"
      shadow="never"
      style="margin-bottom: 16px"
    >
      <template #header>
        <div class="card-header">
          <span class="purpose-title">{{ p.title }}<span class="purpose-desc">（{{ p.desc }}）</span></span>
          <el-tag
            v-if="activeOf(p.value)"
            :type="statusTagType(activeOf(p.value).status)"
            size="small"
          >
            {{ statusLabels[activeOf(p.value).status] }}
          </el-tag>
          <el-tag v-else type="info" size="small">未配置</el-tag>
        </div>
      </template>

      <el-table v-if="activeOf(p.value)" :data="[activeOf(p.value)]" stripe size="small">
        <el-table-column prop="providerName" label="服务商" width="140" />
        <el-table-column prop="modelName" label="模型标识" min-width="160" />
        <el-table-column prop="baseUrl" label="接口地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="apiKeyBack" label="密钥" width="120" />
      </el-table>
      <el-empty v-else description="该用途还没有可用模型，点击下方「添加模型」先添加一条" :image-size="48" />

      <div v-if="activeOf(p.value)" class="switch-row">
        <span class="switch-label">切换为：</span>
        <el-select
          v-model="p.switchTo"
          :disabled="candidatesOf(p.value).length === 0"
          :placeholder="candidatesOf(p.value).length === 0
            ? '暂无其他模型，可先「添加模型」'
            : '选择该用途的其他模型'"
          style="width: 340px"
        >
          <el-option
            v-for="m in candidatesOf(p.value)"
            :key="m.id"
            :value="m.id"
            :label="`${m.modelName}（${m.providerName}，密钥 ${m.apiKeyBack || '无'}）`"
          />
        </el-select>
        <el-button
          type="primary"
          :disabled="!p.switchTo"
          :loading="switching"
          @click="handleSwitch(p)"
        >
          确认切换
        </el-button>
        <span class="switch-hint">{{ switchHint(p.value) }}</span>
      </div>
    </el-card>

    <!-- 全部模型列表 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="purpose-title">全部模型</span>
          <div class="header-actions">
            <el-button
              type="success"
              plain
              :loading="testingAll"
              :disabled="models.length === 0"
              @click="testAll"
            >
              全部测试
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="selection.length === 0"
              :loading="deleting"
              @click="batchDelete"
            >
              批量删除{{ selection.length ? `（${selection.length}）` : '' }}
            </el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">添加模型</el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="models"
        stripe
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column prop="modelName" label="模型标识" min-width="150" />
        <el-table-column label="模型类别" width="95">
          <template #default="{ row }">
            <el-tag size="small" :type="purposeTagType(row.purpose)">
              {{ purposeLabels[row.purpose] || row.purpose }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="providerName" label="服务商" width="110" />
        <el-table-column prop="baseUrl" label="接口地址" min-width="180" show-overflow-tooltip />
        <el-table-column prop="apiKeyBack" label="密钥" width="110" />
        <el-table-column label="连通性" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
              <el-tag size="small" :type="statusTagType(row.status)">
                {{ statusLabels[row.status] }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else size="small" :type="statusTagType(row.status)">
              {{ statusLabels[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前使用" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.selected === 1" type="success" size="small">使用中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="testingId === row.id"
              @click="handleTest(row)"
            >
              测试连接
            </el-button>
            <el-button link type="primary" @click="openEdit(row)">更改配置</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '更改模型配置' : '添加模型'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="模型类型" required>
          <el-select v-model="form.purpose" :disabled="editing" style="width: 100%">
            <el-option
              v-for="p in purposes"
              :key="p.value"
              :value="p.value"
              :label="`${p.label} · ${p.title}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="服务商" required>
          <el-input v-model="form.providerName" placeholder="如 deepseek / siliconflow / amap" />
        </el-form-item>
        <el-form-item label="接口地址" required>
          <el-input v-model="form.baseUrl" placeholder="如 https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="模型标识" required>
          <el-input v-model="form.modelName" placeholder="如 deepseek-flash / BAAI/bge-m3" />
        </el-form-item>
        <el-form-item label="接口密钥" :required="!editing">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="editing
              ? `留空保持原值（当前 ${editingRow?.apiKeyBack || '无'}）`
              : '服务商控制台申请的 API Key'"
          />
        </el-form-item>
        <el-form-item label="附加配置">
          <el-input
            v-model="form.extraConfig"
            type="textarea"
            :rows="2"
            placeholder='JSON 格式，如 {"dimension":1024}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  createModelProvider,
  deleteModelProvider,
  getModelProviders,
  selectModelProvider,
  testModelProviderById,
  updateModelProvider
} from '@/api/modelProvider'

// 三个用途的语言化描述。label 用用户口径：语言 / 检索 / 逆
const purposes = reactive([
  { value: 'CHAT', label: '语言', title: '语言模型', desc: '用于轨迹语义标注与核查说明', switchTo: null },
  { value: 'EMBEDDING', label: '检索', title: '向量化模型', desc: '用于制度条款检索', switchTo: null },
  { value: 'GEO', label: '逆', title: '逆地理编码', desc: '用于停留点 POI 解析', switchTo: null }
])
const purposeLabels = { CHAT: '语言', EMBEDDING: '检索', GEO: '逆' }
const statusLabels = { 0: '未验证', 1: '已连通', 2: '连通失败' }

const loading = ref(false)
const models = ref([])
const testingId = ref(null)
const switching = ref(false)
const dialogVisible = ref(false)
const editing = ref(false)
const editingRow = ref(null)
const saving = ref(false)
const selection = ref([])
const testingAll = ref(false)
const deleting = ref(false)

const form = reactive({
  id: null,
  purpose: 'CHAT',
  providerName: '',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  extraConfig: ''
})

const activeOf = computed(() => (purpose) =>
  models.value.find((m) => m.purpose === purpose && m.selected === 1))

function listOf(purpose) {
  return models.value.filter((m) => m.purpose === purpose)
}

// 该用途可切换的候选：除当前使用外的全部模型（含停用的——停用但能用，
// 测连通会失败，管理员自己看得出来）
function candidatesOf(purpose) {
  return listOf(purpose).filter((m) => m.selected !== 1)
}

async function load() {
  loading.value = true
  try {
    const res = await getModelProviders()
    models.value = res.data || []
    purposes.forEach((p) => {
      p.switchTo = null
    })
  } finally {
    loading.value = false
  }
}

async function handleSwitch(p) {
  if (!p.switchTo) return
  switching.value = true
  try {
    await selectModelProvider(p.switchTo)
    ElMessage.success('已切换当前使用模型')
    await load()
  } finally {
    switching.value = false
  }
}

async function handleTest(row) {
  testingId.value = row.id
  try {
    const res = await testModelProviderById(row.id)
    if (res.data) {
      ElMessage.warning(`连通失败：${res.data}`)
    } else {
      ElMessage.success('连通成功')
    }
    await load()
  } finally {
    testingId.value = null
  }
}

/** 逐条测试全部模型。探测超时 8 秒/条，顺序执行让每行的 loading 可见。 */
async function testAll() {
  testingAll.value = true
  let ok = 0
  let fail = 0
  try {
    for (const m of models.value) {
      testingId.value = m.id
      try {
        const res = await testModelProviderById(m.id)
        res.data ? fail++ : ok++
      } catch {
        fail++
      }
    }
    ElMessage.success(`全部测试完成：${ok} 连通，${fail} 失败${fail ? '（失败原因见列表悬浮提示）' : ''}`)
    await load()
  } finally {
    testingId.value = null
    testingAll.value = false
  }
}

function onSelectionChange(rows) {
  selection.value = rows
}

/**
 * 批量删除。使用中的模型若有同用途替代品可直接删，唯一一条也可删，
 * 唯一删不动的是"使用中且有替代品"——后端 409，拦截器会逐条弹原因，
 * 这里只做汇总，不让一条失败中断整批。
 */
async function batchDelete() {
  const ids = selection.value.map((m) => m.id)
  const inUse = selection.value.filter((m) => m.selected === 1).length
  const tip = inUse > 0
    ? `已选 ${ids.length} 条，其中 ${inUse} 条是当前使用中的模型（有替代品的可删，会被拒绝的会单独提示）。确认删除？`
    : `确认删除已选的 ${ids.length} 条模型？删除后不可恢复。`
  await ElMessageBox.confirm(tip, '批量删除', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  deleting.value = true
  let ok = 0
  let fail = 0
  try {
    for (const id of ids) {
      try {
        await deleteModelProvider(id)
        ok++
      } catch {
        fail++
      }
    }
    if (fail > 0) {
      ElMessage.warning(`批量删除完成：成功 ${ok} 条，失败 ${fail} 条（原因见上方提示）`)
    } else {
      ElMessage.success(`已删除 ${ok} 条`)
    }
    await load()
  } finally {
    deleting.value = false
  }
}

function openCreate() {
  editing.value = false
  editingRow.value = null
  Object.assign(form, {
    id: null,
    purpose: 'CHAT',
    providerName: '',
    baseUrl: '',
    modelName: '',
    apiKey: '',
    extraConfig: ''
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  editingRow.value = row
  Object.assign(form, {
    id: row.id,
    purpose: row.purpose,
    providerName: row.providerName,
    baseUrl: row.baseUrl,
    modelName: row.modelName,
    apiKey: '',
    extraConfig: row.extraConfig || ''
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.purpose || !form.providerName || !form.baseUrl || !form.modelName) {
    ElMessage.warning('请填写模型类型、服务商、接口地址与模型标识')
    return
  }
  if (!editing.value && !form.apiKey) {
    ElMessage.warning('新增模型必须填写接口密钥')
    return
  }
  if (form.extraConfig && form.extraConfig.trim()) {
    try {
      JSON.parse(form.extraConfig)
    } catch {
      ElMessage.warning('附加配置必须是合法的 JSON')
      return
    }
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateModelProvider(form.id, { ...form })
      ElMessage.success('已更新')
    } else {
      await createModelProvider({ ...form })
      ElMessage.success('已添加')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  const tip = row.selected === 1
    ? `「${row.modelName}」是该用途当前使用中的模型，${listOf(row.purpose).length > 1 ? '删除前需先切换到其他模型' : '删除后该用途将没有可用模型'}。确认删除？`
    : `确认删除模型「${row.modelName}」？`
  await ElMessageBox.confirm(tip, '删除模型', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await deleteModelProvider(row.id)
  ElMessage.success('已删除')
  await load()
}

function purposeTagType(p) {
  return { CHAT: 'primary', EMBEDDING: 'success', GEO: 'warning' }[p] || 'info'
}
function statusTagType(s) {
  return { 0: 'info', 1: 'success', 2: 'danger' }[s] ?? 'info'
}

// 切换提示按用途细分：三个用途的生效方式与善后动作完全不同，
// 一句统一的「立即生效」会误导（换向量化模型旧向量全部作废，必须重算）
const switchHints = {
  CHAT: '切换后立即生效：轨迹语义标注与核查说明将改用新模型',
  EMBEDDING: '切换后检索改用新模型；已有向量作废，需到「制度知识库」重新向量化',
  GEO: 'GEO 凭据在后端启动时读取，切换后需重启后端对 POI 解析生效'
}
function switchHint(p) {
  return switchHints[p] || ''
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.purpose-title {
  font-weight: 600;
}
.purpose-desc {
  font-weight: 400;
  font-size: 12px;
  color: #596579;
}
.switch-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.switch-label {
  font-size: 13px;
  color: #606266;
}
.switch-hint {
  font-size: 12px;
  color: #596579;
}
</style>
