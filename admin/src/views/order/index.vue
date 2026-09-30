<template>
  <div>
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>
        「疑似违规」= 程序判定命中规则且 LLM 引用的条款通过回查，已自动生成整改工单；
        「待复核」= 条款引用无效或轨迹级异常，需人工判断后手动下发工单。
        工单闭环只能由管理员操作，员工侧仅有提交说明与申诉两个动作。
      </template>
    </el-alert>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <!-- ============ 整改工单 ============ -->
        <el-tab-pane label="整改工单" name="orders">
          <div class="toolbar">
            <el-select v-model="orderStatus" placeholder="工单状态" clearable style="width: 150px" @change="loadOrders">
              <el-option label="待处理" :value="0" />
              <el-option label="处理中" :value="1" />
              <el-option label="已闭环" :value="2" />
              <el-option label="申诉中" :value="3" />
            </el-select>
            <el-button :icon="Refresh" @click="loadOrders">刷新</el-button>
          </div>

          <el-table v-loading="ordersLoading" :data="orders" stripe>
            <el-table-column prop="orderId" label="ID" width="70" />
            <el-table-column prop="username" label="员工" width="100" />
            <el-table-column prop="content" label="整改内容" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="orderTag(row.status)">{{ orderLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="处理说明" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.handleNote || '—' }}</template>
            </el-table-column>
            <el-table-column label="申诉理由" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.appealReason || '—' }}</template>
            </el-table-column>
            <el-table-column label="复核意见" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.reviewNote || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 1" link type="success" @click="handleClose(row)">
                  确认整改
                </el-button>
                <el-button v-if="row.status === 3" link type="warning" @click="openReview(row)">
                  复核申诉
                </el-button>
                <span v-if="row.status === 0" class="hint-text">等待员工处理</span>
                <span v-if="row.status === 2" class="hint-text">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 核查结论 ============ -->
        <el-tab-pane label="核查结论" name="checks">
          <div class="toolbar">
            <el-select v-model="checkResult" placeholder="核查结果" clearable style="width: 150px" @change="loadChecks">
              <el-option label="合规" :value="0" />
              <el-option label="疑似违规" :value="1" />
              <el-option label="待复核" :value="2" />
            </el-select>
            <el-button :icon="Refresh" @click="loadChecks">刷新</el-button>
            <el-button type="warning" plain :loading="scanning" @click="handleScan">
              扫描异常轨迹
            </el-button>
          </div>

          <el-table v-loading="checksLoading" :data="checks" stripe>
            <el-table-column prop="checkId" label="ID" width="70" />
            <el-table-column prop="username" label="员工" width="100" />
            <el-table-column prop="ruleName" label="规则" min-width="130" show-overflow-tooltip />
            <el-table-column prop="workDate" label="日期" width="105" />
            <el-table-column label="结果" width="95">
              <template #default="{ row }">
                <el-tag size="small" :type="resultTag(row.result)">{{ resultLabel(row.result) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="evidence" label="证据" min-width="200" show-overflow-tooltip />
            <el-table-column prop="aiReason" label="AI 说明" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.aiReason || '—' }}</template>
            </el-table-column>
            <el-table-column label="条款引用" width="130">
              <template #default="{ row }">
                <template v-if="row.citedClauseCode">
                  <el-tag size="small" :type="row.clauseVerified === 1 ? 'success' : 'danger'">
                    {{ row.citedClauseCode }}{{ row.clauseVerified === 1 ? ' ✓' : ' ✗' }}
                  </el-tag>
                </template>
                <el-tag v-else size="small" type="info">无引用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.result === 2"
                  link
                  type="primary"
                  @click="handleCreateOrder(row)"
                >
                  下发工单
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="reviewVisible" title="复核申诉" width="460px">
      <el-form label-width="90px">
        <el-form-item label="复核结论">
          <el-radio-group v-model="reviewForm.accept">
            <el-radio :value="false">维持原结论</el-radio>
            <el-radio :value="true">接受申诉</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="复核意见">
          <el-input v-model="reviewForm.reviewNote" type="textarea" :rows="3" placeholder="复核依据与意见（留痕）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReview">提交复核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  closeOrder,
  createCheckOrder,
  getChecks,
  getOrders,
  reviewOrder,
  scanAbnormalTracks
} from '@/api/order'

const tab = ref('orders')

// ---- 工单 ----
const ordersLoading = ref(false)
const orders = ref([])
const orderStatus = ref('')

// ---- 核查结论 ----
const checksLoading = ref(false)
const checks = ref([])
const checkResult = ref('')
const scanning = ref(false)

const reviewVisible = ref(false)
const reviewForm = reactive({ orderId: null, accept: false, reviewNote: '' })

async function loadOrders() {
  ordersLoading.value = true
  try {
    const res = await getOrders(orderStatus.value === '' ? null : orderStatus.value)
    orders.value = res.data || []
  } finally {
    ordersLoading.value = false
  }
}

async function loadChecks() {
  checksLoading.value = true
  try {
    const res = await getChecks(
      checkResult.value === '' ? {} : { result: checkResult.value }
    )
    checks.value = res.data || []
  } finally {
    checksLoading.value = false
  }
}

async function handleClose(row) {
  const { value } = await ElMessageBox.prompt(
    `确认工单 #${row.orderId} 整改到位并闭环？此操作由管理员发起，员工无法自行闭环。`,
    '确认整改',
    {
      type: 'warning',
      confirmButtonText: '确认闭环',
      cancelButtonText: '取消',
      inputPlaceholder: '复核意见（可空）'
    }
  )
  await closeOrder(row.orderId, value || null)
  ElMessage.success('已闭环')
  await loadOrders()
}

function openReview(row) {
  reviewForm.orderId = row.orderId
  reviewForm.accept = false
  reviewForm.reviewNote = ''
  reviewVisible.value = true
}

async function submitReview() {
  await reviewOrder(reviewForm.orderId, reviewForm.accept, reviewForm.reviewNote || null)
  ElMessage.success(reviewForm.accept ? '已接受申诉' : '已维持原结论')
  reviewVisible.value = false
  await loadOrders()
}

async function handleScan() {
  scanning.value = true
  try {
    const res = await scanAbnormalTracks()
    const created = res.data?.created ?? 0
    ElMessage.success(created > 0 ? `扫描完成，新增 ${created} 条异常结论（待复核）` : '扫描完成，未发现异常轨迹')
    tab.value = 'checks'
    await loadChecks()
  } finally {
    scanning.value = false
  }
}

async function handleCreateOrder(row) {
  await ElMessageBox.confirm(
    `人工复核结论 #${row.checkId} 后下发整改工单？证据：${row.evidence || '无'}`,
    '下发工单',
    { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
  )
  await createCheckOrder(row.checkId)
  ElMessage.success('工单已下发')
  await loadChecks()
}

function orderLabel(s) {
  return { 0: '待处理', 1: '处理中', 2: '已闭环', 3: '申诉中' }[s] ?? s
}
function orderTag(s) {
  return { 0: 'warning', 1: 'primary', 2: 'success', 3: 'danger' }[s] ?? 'info'
}
function resultLabel(r) {
  return { 0: '合规', 1: '疑似违规', 2: '待复核' }[r] ?? r
}
function resultTag(r) {
  return { 0: 'success', 1: 'warning', 2: 'danger' }[r] ?? 'info'
}

onMounted(() => {
  loadOrders()
  loadChecks()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.hint-text {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
