<template>
  <section class="loan-page" aria-labelledby="loan-detail-title">
    <header class="page-header">
      <div>
        <h1 id="loan-detail-title">贷款申请详情</h1>
        <p>查看贷款申请信息与风险评估摘要。</p>
      </div>
      <router-link class="secondary-btn back-link" :to="{ name: 'admin-loan-applications' }">
        返回申请列表
      </router-link>
    </header>

    <section v-if="loading" class="content-card empty-state" role="status" aria-live="polite">
      <h2>正在加载申请详情</h2>
      <p>请稍候...</p>
    </section>

    <section v-else-if="!isValidApplicationId" class="content-card empty-state" role="alert">
      <h2>申请 ID 无效</h2>
      <p>当前地址中的申请 ID 不正确，请返回列表重新选择。</p>
    </section>

    <section v-else-if="errorMessage" class="content-card empty-state error-state" role="alert">
      <h2>申请详情加载失败</h2>
      <p>{{ errorMessage }}</p>
      <button class="secondary-btn retry-btn" type="button" @click="loadApplication">重新加载</button>
    </section>

    <template v-else-if="application">
      <section class="content-card detail-summary" aria-label="申请概览">
        <div>
          <span class="field-label">申请编号</span>
          <p class="summary-number">{{ applicationNumber }}</p>
        </div>
        <span class="status-tag" :class="statusClass(application.status)">
          {{ loanStatusText(application.status) }}
        </span>
      </section>

      <div class="detail-grid">
        <section class="content-card" aria-labelledby="user-info-title">
          <header class="card-heading"><h2 id="user-info-title">用户信息</h2></header>
          <dl class="detail-fields">
            <div><dt>用户姓名</dt><dd>{{ applicantName }}</dd></div>
            <div><dt>用户编号</dt><dd>{{ displayValue(application.userId) }}</dd></div>
            <div><dt>联系电话</dt><dd>{{ phoneNumber }}</dd></div>
          </dl>
        </section>

        <section class="content-card" aria-labelledby="loan-info-title">
          <header class="card-heading"><h2 id="loan-info-title">贷款申请信息</h2></header>
          <dl class="detail-fields">
            <div><dt>贷款金额</dt><dd class="amount-value">{{ formatLoanAmount(loanAmount) }}</dd></div>
            <div><dt>贷款期限</dt><dd>{{ formatLoanTerm(loanTerm) }}</dd></div>
            <div><dt>申请时间</dt><dd><time :datetime="dateTimeValue(applyTime)">{{ displayDateTime(applyTime) }}</time></dd></div>
            <div><dt>当前状态</dt><dd>{{ loanStatusText(application.status) }}</dd></div>
          </dl>
        </section>
      </div>

      <section class="content-card approval-panel" aria-labelledby="approval-title">
        <div>
          <h2 id="approval-title">管理员操作</h2>
          <p v-if="canReviewApplication">请结合申请信息与风险报告完成审批。</p>
          <p v-else>当前申请状态为“{{ loanStatusText(application.status) }}”，无需重复审批。</p>
        </div>
        <div v-if="canReviewApplication" class="approval-actions">
          <button class="approval-btn approve-btn" type="button" :disabled="actionSubmitting" @click="openDecision('approve')">
            通过申请
          </button>
          <button class="approval-btn reject-btn" type="button" :disabled="actionSubmitting" @click="openDecision('reject')">
            拒绝申请
          </button>
        </div>
        <p v-if="actionSuccessMessage" class="action-message success-message" role="status">
          {{ actionSuccessMessage }}
        </p>
      </section>

      <section class="risk-report" aria-labelledby="risk-report-title">
        <header class="risk-report-header">
          <h2 id="risk-report-title">风险评估报告</h2>
          <p>以下内容来自已生成的风险报告，不会触发重新评估。</p>
        </header>

        <div v-if="riskLoading" class="content-card risk-no-report" role="status" aria-live="polite">
          正在加载风险报告...
        </div>

        <div v-else-if="riskErrorMessage" class="content-card risk-no-report risk-error" role="alert">
          <p>{{ riskErrorMessage }}</p>
          <button class="secondary-btn retry-btn" type="button" @click="loadRiskReport">重新加载风险报告</button>
        </div>

        <template v-else-if="riskReport">
          <div class="risk-overview">
            <section class="content-card risk-score-card" aria-labelledby="risk-score-title">
              <h3 id="risk-score-title" class="risk-card-title">综合风险分</h3>
              <div class="risk-score">
                <strong>{{ displayScore(riskReport.score) }}</strong><span>/ 100 分</span>
              </div>
              <span class="risk-level" :class="riskLevel.className">
                {{ riskLevel.text }}
              </span>
              <p class="risk-scale-note">风险分与等级均来自风险报告接口。</p>
            </section>

            <section class="content-card risk-dimensions-card" aria-labelledby="risk-dimensions-title">
              <h3 id="risk-dimensions-title" class="risk-card-title">风险维度</h3>
              <div v-if="riskDimensions.length" class="risk-dimension-list">
                <div v-for="dimension in riskDimensions" :key="dimension.key" class="risk-dimension">
                  <div class="risk-dimension-heading">
                    <span>{{ dimension.label }}</span>
                    <strong>{{ dimension.score }} <small>/ {{ dimension.max }}</small></strong>
                  </div>
                  <progress :value="dimension.score" :max="dimension.max" :aria-label="dimension.label">
                    {{ dimension.score }} / {{ dimension.max }}
                  </progress>
                </div>
              </div>
              <p v-else class="risk-inline-empty">详情接口暂未返回风险维度数据。</p>
            </section>
          </div>

          <div class="risk-analysis">
            <section class="content-card risk-factors-card" aria-labelledby="risk-factors-title">
              <h3 id="risk-factors-title" class="risk-card-title">风险因素</h3>
              <div v-if="hasRiskFactors" class="risk-factor-groups">
                <div class="risk-factor-group positive">
                  <h4>正向因素</h4>
                  <ul v-if="riskFactors.positive.length">
                    <li v-for="factor in riskFactors.positive" :key="factor">{{ factor }}</li>
                  </ul>
                  <p v-else class="risk-inline-empty">暂无正向因素</p>
                </div>
                <div class="risk-factor-group negative">
                  <h4>风险因素</h4>
                  <ul v-if="riskFactors.negative.length">
                    <li v-for="factor in riskFactors.negative" :key="factor">{{ factor }}</li>
                  </ul>
                  <p v-else class="risk-inline-empty">暂无风险因素</p>
                </div>
              </div>
              <p v-else class="risk-inline-empty">详情接口暂未返回风险因素。</p>
            </section>

            <section class="content-card risk-decision-card" aria-labelledby="risk-decision-title">
              <h3 id="risk-decision-title" class="risk-card-title">审批建议</h3>
              <dl class="risk-decision-fields">
                <div><dt>建议结果</dt><dd>{{ riskSuggestion }}</dd></div>
                <div><dt>建议额度</dt><dd class="risk-decision-amount">{{ formatLoanAmount(riskDecisionAmount) }}</dd></div>
              </dl>
              <p class="risk-decision-note">此处仅展示接口数据，不执行审批或修改申请状态。</p>
            </section>
          </div>
        </template>

        <div v-else class="content-card risk-no-report" role="status">
          当前申请暂无风险报告。
        </div>
      </section>

      <Teleport to="body">
        <div v-if="pendingAction" class="decision-overlay" @click.self="closeDecision">
          <form class="decision-dialog" role="dialog" aria-modal="true" aria-labelledby="decision-dialog-title" @submit.prevent="submitDecision">
            <h2 id="decision-dialog-title">确认{{ pendingActionLabel }}贷款申请</h2>
            <p>申请编号：{{ applicationNumber }}</p>
            <label class="remark-field">
              <span>审批备注</span>
              <textarea v-model="remark" rows="4" maxlength="500" :disabled="actionSubmitting" placeholder="请输入审批备注（选填）"></textarea>
              <small>{{ remark.length }} / 500</small>
            </label>
            <p v-if="actionErrorMessage" class="action-message error-message" role="alert">
              {{ actionErrorMessage }}
            </p>
            <div class="decision-actions">
              <button class="secondary-btn" type="button" :disabled="actionSubmitting" @click="closeDecision">取消</button>
              <button
                class="approval-btn"
                :class="pendingAction === 'approve' ? 'approve-btn' : 'reject-btn'"
                type="submit"
                :disabled="actionSubmitting"
              >
                {{ actionSubmitting ? '提交中...' : `确认${pendingActionLabel}` }}
              </button>
            </div>
          </form>
        </div>
      </Teleport>
    </template>

    <section v-else class="content-card empty-state" role="status">
      <h2>未找到贷款申请</h2>
      <p>接口未返回有效的申请详情，请返回列表重新选择。</p>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  approveLoanApplication,
  getLoanApplicationDetail,
  normalizeLoanApplicationStatus,
  rejectLoanApplication
} from '../../../api/loan'
import { getRiskReport } from '../../../api/risk'

const route = useRoute()
const application = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const riskReport = ref(null)
const riskLoading = ref(false)
const riskErrorMessage = ref('')
const pendingAction = ref('')
const remark = ref('')
const actionSubmitting = ref(false)
const actionErrorMessage = ref('')
const actionSuccessMessage = ref('')

const routeApplicationId = computed(() => {
  const value = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
  return String(value ?? '').trim()
})

const isValidApplicationId = computed(() => /^\d+$/.test(routeApplicationId.value) && Number(routeApplicationId.value) > 0)

const applicationNumber = computed(() => displayValue(firstValue(application.value, 'applicationNo', 'applicationNumber', 'id')))
const applicantName = computed(() => displayValue(firstValue(application.value, 'applicantName', 'userName', 'realName')))
const phoneNumber = computed(() => displayValue(firstValue(application.value, 'phoneNumber', 'phone', 'mobile')))
const loanAmount = computed(() => firstValue(application.value, 'loanAmount', 'amount'))
const loanTerm = computed(() => firstValue(application.value, 'loanTerm', 'term'))
const applyTime = computed(() => firstValue(application.value, 'applyTime', 'appliedAt', 'createdAt', 'createTime'))
const canReviewApplication = computed(() => normalizeLoanApplicationStatus(application.value?.status) === 'PENDING')
const pendingActionLabel = computed(() => pendingAction.value === 'approve' ? '通过' : '拒绝')

const riskLevel = computed(() => riskLevelDisplay(riskReport.value?.level))

const riskDimensions = computed(() => {
  if (!riskReport.value) return []
  const dimensions = []
  addDimension(dimensions, 'scoring-card', '评分卡得分', firstValue(riskReport.value, 'scoringCardPoints'), firstValue(riskReport.value, 'scoringCardMax') || 100)
  addDimension(dimensions, 'credit', '信用评分', firstValue(riskReport.value, 'creditScore'), 100)
  addDimension(dimensions, 'behavior', '行为评分', firstValue(riskReport.value, 'behaviorScore'), 100)
  addDimension(dimensions, 'external', '外部数据评分', firstValue(riskReport.value, 'externalScore', 'externalDataScore'), 100)
  return dimensions
})

const riskFactors = computed(() => {
  if (!riskReport.value) return { positive: [], negative: [] }
  const factors = firstValue(riskReport.value, 'factors')
  const positive = toStringList(firstValue(riskReport.value, 'positiveFactors') ?? factors?.positive)
  const negative = toStringList(firstValue(riskReport.value, 'negativeFactors', 'riskFactors') ?? factors?.negative)
  const rejectReason = firstValue(riskReport.value, 'rejectReason')
  if (hasValue(rejectReason) && !negative.includes(String(rejectReason))) negative.push(String(rejectReason))
  return { positive, negative }
})

const hasRiskFactors = computed(() => Boolean(riskFactors.value.positive.length || riskFactors.value.negative.length))
const riskSuggestion = computed(() => displayValue(riskReport.value?.suggestion))
const riskDecisionAmount = computed(() => firstValue(riskReport.value, 'recommendedAmount', 'approvedAmount', 'suggestedAmount'))

function firstValue(source, ...keys) {
  if (!source || typeof source !== 'object') return undefined
  for (const key of keys) {
    if (hasValue(source[key])) return source[key]
  }
  return undefined
}

function hasValue(value) {
  return value !== null && value !== undefined && value !== ''
}

function displayValue(value) {
  return hasValue(value) ? String(value) : '—'
}

function loanStatusText(status) {
  const labels = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    DISBURSED: '已放款',
    SETTLED: '已结清'
  }
  const normalized = normalizeLoanApplicationStatus(status)
  return labels[normalized] || normalized || '未知状态'
}

function statusClass(status) {
  const normalized = normalizeLoanApplicationStatus(status)
  return ['PENDING', 'APPROVED', 'REJECTED'].includes(normalized)
    ? normalized.toLocaleLowerCase()
    : ''
}

function formatLoanAmount(value) {
  const amount = Number(value)
  if (!hasValue(value) || !Number.isFinite(amount)) return '—'
  return `¥${amount.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`
}

function formatLoanTerm(value) {
  return hasValue(value) ? `${value} 个月` : '—'
}

function dateTimeValue(value) {
  return hasValue(value) ? String(value).replace(' ', 'T') : ''
}

function displayDateTime(value) {
  return hasValue(value) ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function displayScore(value) {
  const score = Number(value)
  return hasValue(value) && Number.isFinite(score) ? score : '—'
}

function riskLevelDisplay(level) {
  const normalized = String(level ?? '').trim().toLocaleUpperCase()
  const mapping = {
    '1': { text: '低风险', className: 'low' },
    LOW: { text: '低风险', className: 'low' },
    '2': { text: '中风险', className: 'medium' },
    MEDIUM: { text: '中风险', className: 'medium' },
    '3': { text: '高风险', className: 'high' },
    '4': { text: '高风险', className: 'high' },
    HIGH: { text: '高风险', className: 'high' }
  }
  return mapping[normalized] || { text: hasValue(level) ? `风险等级 ${level}` : '暂无风险等级', className: '' }
}

function toStringList(value) {
  if (Array.isArray(value)) return value.filter(hasValue).map(String)
  return hasValue(value) ? [String(value)] : []
}

function addDimension(target, key, label, scoreValue, maxValue) {
  const score = Number(scoreValue)
  const max = Number(maxValue)
  if (!hasValue(scoreValue) || !Number.isFinite(score) || !Number.isFinite(max) || max <= 0) return
  target.push({ key, label, score, max })
}

async function loadApplication() {
  application.value = null
  errorMessage.value = ''
  if (!isValidApplicationId.value) {
    loading.value = false
    return
  }

  loading.value = true
  try {
    application.value = await getLoanApplicationDetail(routeApplicationId.value)
  } catch (error) {
    errorMessage.value = error?.message || '贷款申请详情加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function loadRiskReport() {
  riskReport.value = null
  riskErrorMessage.value = ''
  if (!isValidApplicationId.value) {
    riskLoading.value = false
    return
  }

  riskLoading.value = true
  try {
    riskReport.value = await getRiskReport(routeApplicationId.value)
  } catch (error) {
    riskErrorMessage.value = error?.message || '风险报告加载失败，请稍后重试'
  } finally {
    riskLoading.value = false
  }
}

function openDecision(action) {
  if (!canReviewApplication.value || actionSubmitting.value) return
  pendingAction.value = action
  remark.value = ''
  actionErrorMessage.value = ''
  actionSuccessMessage.value = ''
}

function closeDecision() {
  if (actionSubmitting.value) return
  pendingAction.value = ''
  remark.value = ''
  actionErrorMessage.value = ''
}

async function submitDecision() {
  if (!pendingAction.value || actionSubmitting.value || !canReviewApplication.value) return

  const action = pendingAction.value
  const nextStatus = action === 'approve' ? 'APPROVED' : 'REJECTED'
  const requestAction = action === 'approve' ? approveLoanApplication : rejectLoanApplication
  actionSubmitting.value = true
  actionErrorMessage.value = ''
  actionSuccessMessage.value = ''

  try {
    const response = await requestAction(routeApplicationId.value, remark.value.trim())
    application.value = { ...application.value, status: nextStatus }
    actionSuccessMessage.value = response?.message || `贷款申请已${pendingActionLabel.value}`
    pendingAction.value = ''
    remark.value = ''
  } catch (error) {
    actionErrorMessage.value = error?.message || `贷款申请${pendingActionLabel.value}失败，请稍后重试`
  } finally {
    actionSubmitting.value = false
  }
}

onMounted(() => {
  loadApplication()
  loadRiskReport()
})
</script>

<style scoped src="./loan.css"></style>
<style scoped src="./risk.css"></style>
<style scoped>
.error-state h2,
.error-state p {
  color: #b91c1c;
}

.retry-btn {
  margin-top: 18px;
}

.risk-inline-empty {
  margin: 0;
  color: var(--loan-muted);
  font-size: 14px;
}

.risk-error {
  color: #b91c1c;
}

.risk-error p {
  margin: 0;
}

.approval-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
  margin-bottom: 24px;
  padding: 24px;
}

.approval-panel h2 {
  margin: 0 0 6px;
  font-size: 18px;
}

.approval-panel p {
  margin: 0;
  color: var(--loan-muted);
  font-size: 14px;
}

.approval-actions,
.decision-actions {
  display: flex;
  gap: 12px;
}

.approval-btn {
  min-height: 42px;
  padding: 9px 18px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: #fff;
  cursor: pointer;
  font-family: inherit;
  font-size: 14px;
}

.approve-btn {
  background: #08794a;
}

.approve-btn:hover:not(:disabled) {
  background: #06623c;
}

.reject-btn {
  background: #c23030;
}

.reject-btn:hover:not(:disabled) {
  background: #a62121;
}

.approval-btn:disabled,
.decision-dialog button:disabled,
.decision-dialog textarea:disabled {
  cursor: not-allowed;
  opacity: .65;
}

.approval-panel .success-message {
  flex-basis: 100%;
  color: #08794a;
}

.decision-overlay {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(15, 32, 55, .48);
}

.decision-dialog {
  box-sizing: border-box;
  width: min(100%, 520px);
  padding: 26px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 18px 50px rgba(15, 32, 55, .2);
  font-family: Arial, 'Microsoft YaHei', sans-serif;
}

.decision-dialog h2 {
  margin: 0 0 8px;
  color: #142239;
  font-size: 21px;
}

.decision-dialog > p {
  margin: 0 0 20px;
  color: #687991;
  font-size: 14px;
}

.remark-field {
  display: grid;
  gap: 8px;
  color: #142239;
  font-size: 14px;
}

.remark-field textarea {
  box-sizing: border-box;
  width: 100%;
  resize: vertical;
  padding: 11px 12px;
  border: 1px solid #dce5f0;
  border-radius: 6px;
  color: #142239;
  font: inherit;
  line-height: 1.5;
}

.remark-field textarea:focus {
  outline: none;
  border-color: #1677ff;
  box-shadow: 0 0 0 2px rgba(22, 119, 255, .12);
}

.remark-field small {
  color: #687991;
  text-align: right;
}

.decision-dialog .error-message {
  margin: 14px 0 0;
  color: #b91c1c;
}

.decision-actions {
  justify-content: flex-end;
  margin-top: 24px;
}

@media (max-width: 640px) {
  .approval-panel {
    align-items: stretch;
    flex-direction: column;
    padding: 18px;
  }

  .approval-actions button,
  .decision-actions button {
    flex: 1;
  }

  .decision-dialog {
    padding: 22px 18px;
  }
}
</style>
