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
        保存时若不重新填写密钥则保持原值不变。
      </template>
    </el-alert>

    <el-card
      v-for="cfg in configs"
      :key="cfg.purpose"
      shadow="never"
      style="margin-bottom: 16px"
    >
      <template #header>
        <div class="card-header">
          <span class="purpose-title">{{ purposeLabels[cfg.purpose] }}</span>
          <el-tag :type="statusTagType(cfg.status)" size="small">
            {{ statusLabels[cfg.status] }}
          </el-tag>
        </div>
      </template>

      <el-form label-width="100px" style="max-width: 720px">
        <el-form-item label="服务商">
          <el-input
            v-model="forms[cfg.purpose].providerName"
            :placeholder="providerPlaceholders[cfg.purpose]"
          />
        </el-form-item>

        <el-form-item label="接口地址">
          <el-input
            v-model="forms[cfg.purpose].baseUrl"
            :placeholder="baseUrlPlaceholders[cfg.purpose]"
          />
        </el-form-item>

        <el-form-item label="模型标识">
          <el-input
            v-model="forms[cfg.purpose].modelName"
            :placeholder="modelPlaceholders[cfg.purpose]"
          />
        </el-form-item>

        <el-form-item label="接口密钥">
          <el-input
            v-model="forms[cfg.purpose].apiKey"
            type="password"
            show-password
            :placeholder="keyPlaceholder(cfg)"
          />
        </el-form-item>

        <el-form-item label="附加配置">
          <el-input
            v-model="forms[cfg.purpose].extraConfig"
            type="textarea"
            :rows="2"
            placeholder='JSON 格式，如 {"dimension":1024}'
          />
        </el-form-item>

        <el-form-item label="是否启用">
          <el-switch
            v-model="forms[cfg.purpose].enabled"
            :active-value="1"
            :inactive-value="0"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving[cfg.purpose]" @click="handleSave(cfg.purpose)">
            保存
          </el-button>
          <el-button :loading="testing[cfg.purpose]" @click="handleTest(cfg.purpose)">
            测试连接
          </el-button>
          <span v-if="testResults[cfg.purpose]" class="test-result test-result--fail">
            {{ testResults[cfg.purpose] }}
          </span>
          <span v-else-if="tested[cfg.purpose]" class="test-result test-result--ok">
            连接通过
          </span>
        </el-form-item>

        <el-form-item v-if="cfg.lastError">
          <el-text type="danger" size="small">最近失败原因：{{ cfg.lastError }}</el-text>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getModelProviders,
  saveModelProvider,
  testModelProvider
} from '@/api/modelProvider'

/** 三类用途的固定顺序，页面按此渲染 */
const PURPOSES = ['CHAT', 'EMBEDDING', 'GEO']

const purposeLabels = {
  CHAT: '语言模型（用于轨迹语义标注与核查说明）',
  EMBEDDING: '向量化模型（用于制度条款检索）',
  GEO: '逆地理编码（用于停留点转地名）'
}

const statusLabels = { 0: '未验证', 1: '已连通', 2: '连接失败' }
const statusTagType = (s) => (s === 1 ? 'success' : s === 2 ? 'danger' : 'info')

const providerPlaceholders = {
  CHAT: 'deepseek',
  EMBEDDING: 'siliconflow',
  GEO: 'amap'
}
const baseUrlPlaceholders = {
  CHAT: 'https://api.deepseek.com',
  EMBEDDING: 'https://api.siliconflow.cn/v1',
  GEO: 'https://restapi.amap.com'
}
const modelPlaceholders = {
  CHAT: 'deepseek-flash',
  EMBEDDING: 'BAAI/bge-m3',
  GEO: 'v3/geocode/regeo'
}

/** 每个用途的独立表单与提交态 */
const forms = reactive({})
const saving = reactive({})
const testing = reactive({})
const testResults = reactive({})
const tested = reactive({})

/** 已存在的配置列表，从接口拉 */
const configs = ref([])

function blankForm() {
  return {
    providerName: '',
    baseUrl: '',
    modelName: '',
    apiKey: '',
    extraConfig: '',
    enabled: 1
  }
}

function keyPlaceholder(cfg) {
  // 测试连接时留空会自动使用已保存的密钥，这里把这件事说清楚，
  // 否则用户不知道测试前还要不要再填一遍密钥
  return cfg.apiKeyBack
    ? `测试连接时留空则使用已保存密钥（当前以 ${cfg.apiKeyBack} 结尾）`
    : '请输入接口密钥（测试连接也会使用它）'
}

async function load() {
  try {
    // 注意：request 拦截器返回的是整个响应包装 { code, message, data }，
    // 真正内容在 res.data 里（与 views/user/index.vue 的 res.data.list 同一约定）
    const res = await getModelProviders()
    const list = res.data || []
    configs.value = PURPOSES.map((p) => {
      const found = list.find((c) => c.purpose === p)
      return found || { purpose: p, status: 0 }
    })
    for (const cfg of configs.value) {
      forms[cfg.purpose] = blankForm()
      if (cfg.providerName) {
        Object.assign(forms[cfg.purpose], {
          providerName: cfg.providerName,
          baseUrl: cfg.baseUrl,
          modelName: cfg.modelName,
          extraConfig: cfg.extraConfig || '',
          enabled: cfg.enabled
          // apiKey 刻意留空：后端据此判断"不修改密钥"
        })
      }
      saving[cfg.purpose] = false
      testing[cfg.purpose] = false
      testResults[cfg.purpose] = ''
      tested[cfg.purpose] = false
    }
  } catch (e) {
    // 把真实原因显示出来，不要只说"加载失败"——否则下次排查无从下手
    const msg = e?.message || e?.toString?.() || '加载模型配置失败'
    ElMessage.error(msg)
  }
}

async function handleSave(purpose) {
  saving[purpose] = true
  try {
    await saveModelProvider({ purpose, ...forms[purpose] })
    ElMessage.success('已保存')
    await load()
  } catch {
    // 错误已由 request 拦截器统一提示
  } finally {
    saving[purpose] = false
  }
}

async function handleTest(purpose) {
  testing[purpose] = true
  testResults[purpose] = ''
  tested[purpose] = false
  try {
    // data 为 null 表示连通；为字符串则为失败原因（后端设计如此）
    const res = await testModelProvider(purpose, { purpose, ...forms[purpose] })
    const err = res.data
    if (err) {
      testResults[purpose] = err
      ElMessage.error('连接失败')
    } else {
      tested[purpose] = true
      ElMessage.success('连接通过')
    }
  } catch {
    // 错误已由 request 拦截器统一提示
  } finally {
    testing[purpose] = false
  }
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.purpose-title {
  font-weight: 600;
}
.test-result {
  margin-left: 12px;
  font-size: 13px;
}
.test-result--ok {
  color: var(--el-color-success);
}
.test-result--fail {
  color: var(--el-color-danger);
}
</style>
