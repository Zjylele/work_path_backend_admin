<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        规则判定由程序完成（数值比较，可复现），LLM 只在命中后生成违规性质说明并引用制度条款，
        引用会经「条款回查」验证。DURATION 阈值单位为分钟、FREQUENCY 为次/天、RANGE 为米、
        ABNORMAL 无阈值（轨迹级：有移动但无任何停留）。
      </template>
    </el-alert>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="enabled" placeholder="启用状态" clearable style="width: 140px" @change="load">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <div class="spacer" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建规则</el-button>
      </div>

      <el-table v-loading="loading" :data="rules" stripe>
        <el-table-column prop="ruleCode" label="编码" width="120" />
        <el-table-column prop="ruleName" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTag(row.ruleType)">{{ typeLabel(row.ruleType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="阈值" width="90" align="right">
          <template #default="{ row }">{{ row.threshold != null ? row.threshold : '—' }}</template>
        </el-table-column>
        <el-table-column label="驻地配置" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ stationText(row) }}</template>
        </el-table-column>
        <el-table-column label="关联条款" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.clauseCode">
              <el-tag size="small" type="success">条款 {{ row.clauseCode }}</el-tag>
              <span class="clause-content">{{ row.clauseContent }}</span>
            </template>
            <el-tag v-else size="small" type="info">未关联</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled === 1" @change="toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑规则' : '新建规则'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="规则编码" required>
          <el-input v-model="form.ruleCode" placeholder="如 DUR-30MIN，唯一" />
        </el-form-item>
        <el-form-item label="规则名称" required>
          <el-input v-model="form.ruleName" placeholder="如 单次停留超时" />
        </el-form-item>
        <el-form-item label="规则类型" required>
          <el-select v-model="form.ruleType" style="width: 100%" @change="onTypeChange">
            <el-option label="DURATION 时长（分钟）" value="DURATION" />
            <el-option label="FREQUENCY 频次（次/天）" value="FREQUENCY" />
            <el-option label="RANGE 范围（米）" value="RANGE" />
            <el-option label="ABNORMAL 异常（轨迹级）" value="ABNORMAL" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.ruleType !== 'ABNORMAL'" label="阈值" required>
          <el-input-number v-model="form.threshold" :min="0" :precision="1" :step="5" />
          <span class="unit">{{ unitText }}</span>
        </el-form-item>
        <el-form-item
          v-if="form.ruleType === 'RANGE' || form.ruleType === 'DURATION'"
          label="报备驻地"
        >
          <div class="station-row">
            <el-input-number v-model="form.stationLat" :precision="6" :step="0.001" controls-position="right" placeholder="纬度" />
            <el-input-number v-model="form.stationLng" :precision="6" :step="0.001" controls-position="right" placeholder="经度" />
            <el-input-number v-model="form.stationRadius" :min="50" :step="50" controls-position="right" placeholder="半径(米)" />
          </div>
          <div class="hint">RANGE 必填中心坐标；DURATION 可选（配置后驻地内长停留不算违规）</div>
        </el-form-item>
        <el-form-item label="关联条款">
          <el-select
            v-model="form.policyClauseId"
            filterable
            remote
            clearable
            :remote-method="searchClauses"
            :loading="searching"
            placeholder="按语义或编号搜索制度条款"
            style="width: 100%"
          >
            <el-option
              v-for="c in clauseOptions"
              :key="c.clauseId"
              :value="c.clauseId"
              :label="`条款 ${c.clauseCode}：${c.content.slice(0, 30)}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
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
import { Plus, Refresh } from '@element-plus/icons-vue'
import { createRule, deleteRule, getRules, updateRule } from '@/api/rule'
import { searchPolicyClauses } from '@/api/policy'

const loading = ref(false)
const rules = ref([])
const enabled = ref('')
const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const searching = ref(false)
const clauseOptions = ref([])

const form = reactive({
  ruleId: null,
  ruleCode: '',
  ruleName: '',
  ruleType: 'DURATION',
  threshold: 30,
  stationLat: undefined,
  stationLng: undefined,
  stationRadius: 200,
  policyClauseId: null,
  enabled: 1
})

const unitText = computed(() => {
  if (form.ruleType === 'DURATION') return '分钟'
  if (form.ruleType === 'FREQUENCY') return '次/天'
  if (form.ruleType === 'RANGE') return '米'
  return ''
})

async function load() {
  loading.value = true
  try {
    const res = await getRules(enabled.value === '' ? null : enabled.value)
    rules.value = res.data || []
  } finally {
    loading.value = false
  }
}

function stationText(row) {
  if (!row.extraConfig) return '—'
  try {
    const c = JSON.parse(row.extraConfig)
    if (c.station_lat == null) return '—'
    return `${c.station_lat.toFixed(4)}, ${c.station_lng.toFixed(4)}（半径 ${c.station_radius_m ?? 200} 米）`
  } catch {
    return '配置错误'
  }
}

function openCreate() {
  editing.value = false
  Object.assign(form, {
    ruleId: null,
    ruleCode: '',
    ruleName: '',
    ruleType: 'DURATION',
    threshold: 30,
    stationLat: undefined,
    stationLng: undefined,
    stationRadius: 200,
    policyClauseId: null,
    enabled: 1
  })
  clauseOptions.value = []
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  let station = {}
  try {
    station = row.extraConfig ? JSON.parse(row.extraConfig) : {}
  } catch {
    station = {}
  }
  Object.assign(form, {
    ruleId: row.ruleId,
    ruleCode: row.ruleCode,
    ruleName: row.ruleName,
    ruleType: row.ruleType,
    threshold: row.threshold ?? undefined,
    stationLat: station.station_lat,
    stationLng: station.station_lng,
    stationRadius: station.station_radius_m ?? 200,
    policyClauseId: row.policyClauseId,
    enabled: row.enabled
  })
  // 编辑时用条款 ID 无法反查内容，置空待选——保存时重新选择或保留原 ID
  clauseOptions.value = []
  if (row.policyClauseId && row.clauseCode) {
    clauseOptions.value = [{
      clauseId: row.policyClauseId,
      clauseCode: row.clauseCode,
      content: row.clauseContent || '（原条款）'
    }]
  }
  dialogVisible.value = true
}

function onTypeChange() {
  if (form.ruleType === 'DURATION') form.threshold = 30
  else if (form.ruleType === 'FREQUENCY') form.threshold = 5
  else if (form.ruleType === 'RANGE') form.threshold = 500
  else form.threshold = null
}

async function searchClauses(query) {
  if (!query) {
    clauseOptions.value = []
    return
  }
  searching.value = true
  try {
    const res = await searchPolicyClauses(query, 10)
    clauseOptions.value = res.data || []
  } finally {
    searching.value = false
  }
}

function buildPayload() {
  const needStation = form.ruleType === 'RANGE' || form.ruleType === 'DURATION'
  const hasStation = form.stationLat != null && form.stationLng != null
  if (needStation && hasStation) {
    return {
      ...form,
      ruleCode: form.ruleCode.trim(),
      extraConfig: JSON.stringify({
        station_lat: form.stationLat,
        station_lng: form.stationLng,
        station_radius_m: form.stationRadius ?? 200
      })
    }
  }
  if (form.ruleType === 'RANGE' && !hasStation) {
    ElMessage.warning('RANGE 规则必须配置驻地中心坐标')
    throw new Error('station required')
  }
  const { stationLat, stationLng, stationRadius, ...payload } = form
  return { ...payload, ruleCode: form.ruleCode.trim() }
}

async function save() {
  if (!form.ruleCode.trim() || !form.ruleName.trim()) {
    ElMessage.warning('请填写规则编码与名称')
    return
  }
  let payload
  try {
    payload = buildPayload()
  } catch {
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateRule(form.ruleId, payload)
      ElMessage.success('已更新')
    } else {
      await createRule(payload)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row) {
  const target = row.enabled === 1 ? 0 : 1
  await updateRule(row.ruleId, { ...row, enabled: target })
  ElMessage.success(target === 1 ? '已启用' : '已停用')
  await load()
}

async function handleDelete(row) {
  await ElMessageBox.confirm(
    `删除规则「${row.ruleName}」？已有核查结论引用的规则会被拒绝删除，可改为停用。`,
    '删除规则',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await deleteRule(row.ruleId)
  ElMessage.success('已删除')
  await load()
}

function typeLabel(t) {
  return { DURATION: '时长', FREQUENCY: '频次', RANGE: '范围', ABNORMAL: '异常' }[t] || t
}
function typeTag(t) {
  return { DURATION: 'warning', FREQUENCY: 'primary', RANGE: 'success', ABNORMAL: 'danger' }[t] || 'info'
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.spacer {
  flex: 1;
}
.clause-content {
  margin-left: 8px;
  font-size: 12px;
  color: #596579;
}
.station-row {
  display: flex;
  gap: 8px;
}
.hint {
  font-size: 12px;
  color: #596579;
  line-height: 1.6;
}
.unit {
  margin-left: 8px;
  color: #596579;
}
</style>
