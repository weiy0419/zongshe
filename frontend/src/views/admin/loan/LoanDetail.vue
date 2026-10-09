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
          <div>
            <h2 id="risk-report-title">风险评估报告</h2>
            <p>以下内容来自已生成的风险报告，不会触发重新评估。</p>
          </div>
          <button
            v-if="hasReplayReportId"
            class="secondary-btn replay-btn"
            type="button"
            :disabled="replayLoading"
            @click="loadReplayReport"
          >
            {{ replayLoading ? '回放中...' : '历史回放' }}
          </button>
        </header>

        <div v-if="riskLoading" class="content-card risk-no-report" role="status" aria-live="polite">
          正在加载风险报告...
        </div>

        <div v-else-if="riskErrorMessage" class="content-card risk-no-report risk-error" role="alert">
          <p>{{ riskErrorMessage }}</p>
          <button class="secondary-btn retry-btn" type="button" @click="loadRiskReport">重新加载风险报告</button>
        </div>

        <template v-else-if="riskReport">
          <template v-if="hasNewRiskReport">
            <div class="risk-summary-grid">
              <section class="content-card risk-decision-summary" aria-labelledby="risk-decision-summary-title">
                <h3 id="risk-decision-summary-title" class="risk-card-title">决策摘要</h3>
                <div class="decision-primary">
                  <span>最终决策</span>
                  <strong class="decision-badge" :class="decisionDisplay.className">
                    {{ decisionDisplay.text }}
                  </strong>
                </div>
                <dl class="risk-summary-fields">
                  <div><dt>融合模式</dt><dd>{{ displayValue(fusionMode) }}</dd></div>
                  <div><dt>{{ totalScoreLabel }}</dt><dd>{{ displayScore(riskReport.totalScore) }} / {{ displayScore(riskReport.maxScore) }}</dd></div>
                  <div><dt>准入结果</dt><dd>{{ displayReportValue(riskBreakdown.admission) }}</dd></div>
                  <div><dt>卡版本</dt><dd>{{ displayValue(riskReport.cardVersion) }}</dd></div>
                  <div><dt>评估时间</dt><dd>{{ displayDateTime(riskReport.assessedAt) }}</dd></div>
                  <div><dt>建议额度</dt><dd>{{ formatOptionalAmount(riskBreakdown.suggestLimit) }}</dd></div>
                  <div class="summary-field-wide">
                    <dt>实际执行模型</dt>
                    <dd v-if="executedModelsDisplay.kind === 'missing'">-</dd>
                    <dd v-else-if="executedModelsDisplay.kind === 'empty'">无</dd>
                    <dd v-else class="tag-list">
                      <span v-for="model in executedModelsDisplay.values" :key="model" class="report-tag model-tag">{{ model }}</span>
                    </dd>
                  </div>
                  <div class="summary-field-wide">
                    <dt>当前启用模型</dt>
                    <dd v-if="enabledModelsDisplay.kind === 'missing'">-</dd>
                    <dd v-else-if="enabledModelsDisplay.kind === 'empty'">未启用授信模型</dd>
                    <dd v-else class="tag-list">
                      <span v-for="model in enabledModelsDisplay.values" :key="model" class="report-tag model-tag">{{ model }}</span>
                    </dd>
                  </div>
                  <div class="summary-field-wide"><dt>风险原因摘要</dt><dd>{{ displayValue(riskReport.reasonSummary) }}</dd></div>
                  <div class="summary-field-wide">
                    <dt>原因码</dt>
                    <dd v-if="reasonCodesDisplay.kind === 'missing'">-</dd>
                    <dd v-else-if="reasonCodesDisplay.kind === 'empty'">无</dd>
                    <dd v-else class="tag-list">
                      <span v-for="code in reasonCodesDisplay.values" :key="code" class="report-tag reason-code-tag">
                        {{ code }}
                      </span>
                    </dd>
                  </div>
                </dl>
              </section>

              <section class="content-card woe-card" aria-labelledby="woe-card-title">
                <h3 id="woe-card-title" class="risk-card-title">WOE申请卡</h3>
                <div class="woe-score">
                  <span>WOE原始得分</span>
                  <strong>{{ displayScore(woeScore) }}</strong>
                  <small>/ {{ displayScore(woeMaxScore) }}</small>
                </div>
                <dl class="compact-fields">
                  <div><dt>申请卡结果</dt><dd>{{ displayReportValue(riskBreakdown.scorecard) }}</dd></div>
                  <div><dt>申请卡版本</dt><dd>{{ displayValue(riskBreakdown.scorecardVersion ?? riskReport.cardVersion) }}</dd></div>
                  <template v-if="scorecardDetails">
                    <div><dt>模型开关</dt><dd>{{ enabledText(scorecardDetails.enabled) }}</dd></div>
                    <div><dt>详情得分</dt><dd>{{ displayScore(scorecardDetails.score) }}</dd></div>
                    <div><dt>详情满分</dt><dd>{{ displayScore(scorecardDetails.maxScore) }}</dd></div>
                    <div><dt>详情版本</dt><dd>{{ displayValue(scorecardDetails.version) }}</dd></div>
                  </template>
                </dl>
                <p class="risk-scale-note">该分数是申请卡资质分，分数越高代表资质越好。</p>
              </section>
            </div>

            <section class="content-card model-score-card" aria-labelledby="model-score-title">
              <h3 id="model-score-title" class="risk-card-title">模型评分明细</h3>
              <div v-if="modelScoreRows.length" class="model-score-grid">
                <article v-for="model in modelScoreRows" :key="model.name" class="model-score-item">
                  <strong>{{ model.name }}</strong>
                  <dl>
                    <div><dt>原始模型分</dt><dd>{{ displayScore(model.score) }}<span v-if="hasValue(model.maxScore)"> / {{ displayScore(model.maxScore) }}</span></dd></div>
                    <div><dt>归一化模型分</dt><dd>{{ formatDecimal(model.normalizedScore, 2) }}</dd></div>
                  </dl>
                </article>
              </div>
              <p v-else class="risk-inline-empty">当前报告未返回模型评分明细。</p>
            </section>

            <section v-if="ahpDetails" class="content-card ahp-card" aria-labelledby="ahp-title">
              <div class="ahp-heading">
                <div>
                  <h3 id="ahp-title" class="risk-card-title">AHP 动态融合</h3>
                  <p>展示本次评估实际使用的模型权重及一致性结果。</p>
                </div>
                <span class="fusion-badge">AHP</span>
              </div>

              <div class="ahp-summary-grid">
                <div><span>模型顺序</span><strong>{{ ahpModelOrder.length ? ahpModelOrder.join(' → ') : '-' }}</strong></div>
                <div><span>一致性比率 CR</span><strong>{{ formatDecimal(ahpDetails.cr, 4) }}</strong></div>
                <div><span>一致性检查</span><strong :class="ahpDetails.consistent === false ? 'status-warning' : 'status-success'">{{ consistencyText(ahpDetails.consistent) }}</strong></div>
                <div><span>权重回退</span><strong :class="ahpDetails.fallbackUsed === true ? 'status-warning' : 'status-success'">{{ fallbackText(ahpDetails.fallbackUsed) }}</strong></div>
              </div>

              <div class="ahp-content-grid">
                <div>
                  <h4>模型权重</h4>
                  <div v-if="ahpWeightRows.length" class="weight-list">
                    <div v-for="weight in ahpWeightRows" :key="weight.name" class="weight-row">
                      <span>{{ weight.name }}</span>
                      <div class="weight-track" aria-hidden="true"><i :style="{ width: weight.percentWidth }"></i></div>
                      <strong>{{ formatWeight(weight.value) }}</strong>
                    </div>
                  </div>
                  <p v-else class="risk-inline-empty">未返回权重数据。</p>
                </div>

                <div>
                  <h4>权重来源</h4>
                  <dl v-if="ahpSourceRows.length" class="source-fields">
                    <div v-for="item in ahpSourceRows" :key="item.key">
                      <dt>{{ item.label }}</dt><dd>{{ item.value }}</dd>
                    </div>
                  </dl>
                  <p v-else class="risk-inline-empty">未返回权重来源。</p>
                </div>
              </div>

              <div v-if="ahpMatrixRows.length" class="ahp-matrix-wrap">
                <h4>判断矩阵</h4>
                <div class="table-scroll">
                  <table class="ahp-matrix-table">
                    <thead><tr><th>模型</th><th v-for="model in ahpModelOrder" :key="model">{{ model }}</th></tr></thead>
                    <tbody>
                      <tr v-for="row in ahpMatrixRows" :key="row.name">
                        <th>{{ row.name }}</th><td v-for="(value, index) in row.values" :key="`${row.name}-${index}`">{{ formatDecimal(value, 4) }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
              <p class="ahp-explanation"><strong>计算说明：</strong>{{ displayValue(ahpDetails.explanation) }}</p>
            </section>

            <section class="content-card feature-snapshot-card" aria-labelledby="feature-snapshot-title">
              <div class="snapshot-heading">
                <div>
                  <h3 id="feature-snapshot-title" class="risk-card-title">评估特征快照</h3>
                  <p>以下为本次风险评估持久化时使用的只读特征。</p>
                </div>
                <div class="freshness-list">
                  <span :class="['freshness-badge', reportFreshness.className]">报告：{{ reportFreshness.text }}</span>
                  <span :class="['freshness-badge', snapshotFreshness.className]">快照：{{ snapshotFreshness.text }}</span>
                </div>
              </div>
              <dl v-if="featureSnapshot" class="snapshot-grid">
                <div v-for="field in snapshotFields" :key="field.key">
                  <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
                </div>
              </dl>
              <p v-else class="risk-inline-empty">当前报告未返回特征快照。</p>
            </section>

            <div class="risk-model-grid">
              <section class="content-card model-status-card" aria-labelledby="model-status-title">
                <h3 id="model-status-title" class="risk-card-title">模型启用状态</h3>
                <div class="enabled-models">
                  <span>当前启用模型</span>
                  <div v-if="enabledModelsDisplay.kind === 'values'" class="tag-list">
                    <span v-for="model in enabledModelsDisplay.values" :key="model" class="report-tag model-tag">
                      {{ model }}
                    </span>
                  </div>
                  <strong v-else-if="enabledModelsDisplay.kind === 'empty'">未启用授信模型</strong>
                  <strong v-else>-</strong>
                </div>
                <ul class="model-status-list">
                  <li v-for="model in modelStatuses" :key="model.key">
                    <span>{{ model.label }}</span>
                    <strong :class="model.className">{{ model.text }}</strong>
                  </li>
                </ul>
              </section>

              <section class="content-card rules-card" aria-labelledby="rules-title">
                <h3 id="rules-title" class="risk-card-title">硬规则 / 两阶段</h3>
                <dl class="compact-fields rule-summary-fields">
                  <div><dt>评估阶段</dt><dd>{{ displayReportValue(riskBreakdown.stage) }}</dd></div>
                  <div><dt>准入结果</dt><dd>{{ displayReportValue(riskBreakdown.admission) }}</dd></div>
                  <div><dt>规则结果</dt><dd>{{ displayReportValue(riskBreakdown.rules) }}</dd></div>
                </dl>
                <div v-if="ruleResults.length" class="rule-result-list">
                  <article v-for="(rule, index) in ruleResults" :key="`${rule.stage || 'rule'}-${index}`" class="rule-result-item">
                    <div>
                      <span>{{ displayValue(rule.stage) }}</span>
                      <strong>{{ displayValue(rule.ruleName) }}</strong>
                    </div>
                    <span class="rule-result-status" :class="rulePassedDisplay(rule.passed).className">
                      {{ rulePassedDisplay(rule.passed).text }}
                    </span>
                    <p>{{ displayValue(rule.reason) }}</p>
                  </article>
                </div>
                <p v-else class="risk-inline-empty">暂无规则明细。</p>
              </section>
            </div>

            <aside v-if="riskDedup" class="dedup-note" aria-label="风险评估去重状态">
              <strong>{{ dedupDisplay.title }}</strong>
              <span>{{ dedupDisplay.detail }}</span>
            </aside>

            <section v-if="hasReplayReportId" class="replay-section" aria-labelledby="replay-title">
              <header>
                <div>
                  <h3 id="replay-title">历史报告回放</h3>
                  <p>历史回放基于已持久化报告，不重新获取当前特征或重新执行模型。</p>
                </div>
              </header>
              <div v-if="replayLoading" class="replay-state" role="status" aria-live="polite">正在读取历史报告...</div>
              <div v-else-if="replayError" class="replay-state replay-error" role="alert">{{ replayError }}</div>
              <dl v-else-if="replayResult" class="replay-grid">
                <div><dt>来源报告 ID</dt><dd>{{ displayValue(replayBreakdown.replaySourceReportId ?? replayResult.reportId) }}</dd></div>
                <div><dt>回放模式</dt><dd>{{ displayValue(replayBreakdown.replayMode) }}</dd></div>
                <div><dt>最终决策</dt><dd>{{ replayDecision.text }}</dd></div>
                <div><dt>最终总分</dt><dd>{{ displayScore(replayResult.totalScore) }} / {{ displayScore(replayResult.maxScore) }}</dd></div>
                <div><dt>原评估时间</dt><dd>{{ displayDateTime(replayResult.assessedAt) }}</dd></div>
                <div><dt>融合模式</dt><dd>{{ displayValue(replayBreakdown.fusionMode) }}</dd></div>
                <div class="replay-field-wide"><dt>启用模型</dt><dd>{{ replayEnabledModels }}</dd></div>
              </dl>
              <p v-else class="replay-state">点击“历史回放”读取当前 reportId 对应的持久化结果。</p>
            </section>
          </template>

          <template v-else>
            <div class="legacy-report-note">以下为历史旧版风险报告。</div>
            <div class="risk-overview">
              <section class="content-card risk-score-card" aria-labelledby="legacy-risk-score-title">
                <h3 id="legacy-risk-score-title" class="risk-card-title">旧版风险分</h3>
                <div class="risk-score">
                  <strong>{{ displayScore(riskReport.score) }}</strong><span>/ 100 分</span>
                </div>
                <span class="risk-level" :class="riskLevel.className">
                  {{ riskLevel.text }}
                </span>
                <dl class="legacy-summary-fields">
                  <div><dt>风控结论</dt><dd>{{ legacyPassedText }}</dd></div>
                  <div><dt>拒绝原因</dt><dd>{{ displayValue(riskReport.rejectReason) }}</dd></div>
                </dl>
              </section>

              <section class="content-card risk-dimensions-card" aria-labelledby="legacy-risk-dimensions-title">
                <h3 id="legacy-risk-dimensions-title" class="risk-card-title">旧版评分维度</h3>
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
                <p v-else class="risk-inline-empty">旧报告暂未返回评分维度数据。</p>
              </section>
            </div>

            <div class="risk-analysis">
              <section class="content-card risk-factors-card" aria-labelledby="legacy-risk-factors-title">
                <h3 id="legacy-risk-factors-title" class="risk-card-title">旧版风险因素</h3>
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
                <p v-else class="risk-inline-empty">旧报告暂未返回风险因素。</p>
              </section>

              <section class="content-card risk-decision-card" aria-labelledby="legacy-risk-decision-title">
                <h3 id="legacy-risk-decision-title" class="risk-card-title">旧版审批建议</h3>
                <dl class="risk-decision-fields">
                  <div><dt>建议结果</dt><dd>{{ riskSuggestion }}</dd></div>
                  <div><dt>建议额度</dt><dd class="risk-decision-amount">{{ formatLoanAmount(riskDecisionAmount) }}</dd></div>
                </dl>
                <p class="risk-decision-note">旧版报告仅用于兼容历史数据。</p>
              </section>
            </div>
          </template>
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
import { getRiskReport, replayRiskReport } from '../../../api/risk'

const route = useRoute()
const application = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const riskReport = ref(null)
const riskLoading = ref(false)
const riskErrorMessage = ref('')
const replayResult = ref(null)
const replayLoading = ref(false)
const replayError = ref('')
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

const riskBreakdown = computed(() => isRecord(riskReport.value?.breakdown) ? riskReport.value.breakdown : {})
const hasNewRiskReport = computed(() => {
  const report = riskReport.value
  if (!report) return false
  return hasValue(report.decision)
    || hasValue(report.totalScore)
    || hasValue(report.maxScore)
    || hasOwn(report, 'reasonCodes')
    || isRecord(report.breakdown)
    || isRecord(report.featureSnapshot)
})
const decisionDisplay = computed(() => decisionText(riskReport.value?.decision))
const reasonCodesDisplay = computed(() => listFieldDisplay(riskReport.value, 'reasonCodes'))
const enabledModelsDisplay = computed(() => listFieldDisplay(riskBreakdown.value, 'enabledModels'))
const executedModelsDisplay = computed(() => listFieldDisplay(riskBreakdown.value, 'executedModels'))
const fusionMode = computed(() => hasValue(riskBreakdown.value.fusionMode)
  ? String(riskBreakdown.value.fusionMode).toLocaleUpperCase()
  : '')
const totalScoreLabel = computed(() => fusionMode.value === 'AHP' ? '最终融合得分' : '最终模型得分')
const scorecardDetails = computed(() => isRecord(riskBreakdown.value.scorecardDetails)
  ? riskBreakdown.value.scorecardDetails
  : null)
const modelResults = computed(() => isRecord(riskBreakdown.value.modelResults) ? riskBreakdown.value.modelResults : {})
const woeModelResult = computed(() => isRecord(modelResults.value.WOE) ? modelResults.value.WOE : null)
const woeScore = computed(() => firstValue(scorecardDetails.value, 'score') ?? firstValue(woeModelResult.value, 'score'))
const woeMaxScore = computed(() => firstValue(scorecardDetails.value, 'maxScore') ?? firstValue(woeModelResult.value, 'maxScore'))
const modelScoreRows = computed(() => {
  const scores = isRecord(riskBreakdown.value.modelScores) ? riskBreakdown.value.modelScores : {}
  const normalizedScores = isRecord(riskBreakdown.value.normalizedModelScores)
    ? riskBreakdown.value.normalizedModelScores
    : {}
  const names = [...new Set([...Object.keys(scores), ...Object.keys(normalizedScores), ...Object.keys(modelResults.value)])]

  return names.map(name => {
    const raw = hasOwn(scores, name) ? scores[name] : modelResults.value[name]
    return {
      name,
      score: isRecord(raw) ? raw.score : raw,
      maxScore: isRecord(raw) ? raw.maxScore : undefined,
      normalizedScore: normalizedScores[name]
    }
  })
})
const ahpDetails = computed(() => isRecord(riskBreakdown.value.ahp) ? riskBreakdown.value.ahp : null)
const ahpModelOrder = computed(() => Array.isArray(ahpDetails.value?.modelOrder)
  ? ahpDetails.value.modelOrder.filter(hasValue).map(String)
  : [])
const ahpWeightRows = computed(() => {
  const weights = isRecord(ahpDetails.value?.weights) ? ahpDetails.value.weights : {}
  const orderedNames = ahpModelOrder.value.length ? ahpModelOrder.value : Object.keys(weights)
  return orderedNames
    .filter(name => hasValue(weights[name]))
    .map(name => {
      const value = Number(weights[name])
      return {
        name,
        value: Number.isFinite(value) ? value : undefined,
        percentWidth: Number.isFinite(value) ? `${Math.max(0, Math.min(100, value * 100))}%` : '0%'
      }
    })
})
const ahpSourceRows = computed(() => {
  if (!isRecord(ahpDetails.value?.source)) return []
  const labels = {
    lpr1y: '一年期 LPR',
    lpr5y: '五年期 LPR',
    stale: '来源数据新鲜度',
    credit_score: '信用分',
    credit_overdue_count: '逾期次数',
    parameters: '计算参数',
    importance: '模型重要度',
    formula: '权重公式',
    fallbackReason: '回退原因',
    version: '来源版本'
  }
  return Object.entries(ahpDetails.value.source).map(([key, value]) => ({
    key,
    label: labels[key] || key,
    value: key === 'stale' ? freshnessStatus(value).text : formatStructuredValue(value)
  }))
})
const ahpMatrixRows = computed(() => {
  if (!Array.isArray(ahpDetails.value?.matrix) || !ahpModelOrder.value.length) return []
  return ahpDetails.value.matrix
    .filter(Array.isArray)
    .slice(0, ahpModelOrder.value.length)
    .map((values, index) => ({
      name: ahpModelOrder.value[index] || `模型 ${index + 1}`,
      values: values.slice(0, ahpModelOrder.value.length)
    }))
})
const featureSnapshot = computed(() => isRecord(riskReport.value?.featureSnapshot)
  ? riskReport.value.featureSnapshot
  : null)
const reportFreshness = computed(() => freshnessStatus(riskReport.value?.stale))
const snapshotFreshness = computed(() => freshnessStatus(featureSnapshot.value?.stale))
const snapshotFields = computed(() => {
  const snapshot = featureSnapshot.value || {}
  return [
    { key: 'creditScore', label: '信用分', value: displayValue(snapshot.creditScore) },
    { key: 'overdueCount', label: '逾期次数', value: displayValue(snapshot.overdueCount) },
    { key: 'monthlyIncome', label: '月收入', value: formatOptionalCurrency(snapshot.monthlyIncome) },
    { key: 'loanAmount', label: '贷款金额', value: formatOptionalCurrency(snapshot.loanAmount) },
    { key: 'loanTerm', label: '贷款期限', value: hasValue(snapshot.loanTerm) ? `${snapshot.loanTerm} 个月` : '-' },
    { key: 'dishonestHit', label: '失信命中', value: booleanResultText(snapshot.dishonestHit, '已命中', '未命中') },
    { key: 'dishonestName', label: '失信主体', value: displayValueOrDash(snapshot.dishonestName) },
    { key: 'courtName', label: '执行法院', value: displayValueOrDash(snapshot.courtName) },
    { key: 'publishDate', label: '公示时间', value: displayDateTimeOrDash(snapshot.publishDate) },
    { key: 'lpr1y', label: '一年期 LPR', value: displayValueOrDash(snapshot.lpr1y) },
    { key: 'lpr5y', label: '五年期 LPR', value: displayValueOrDash(snapshot.lpr5y) },
    { key: 'lprPublishDate', label: 'LPR 发布时间', value: displayDateTimeOrDash(snapshot.lprPublishDate) },
    { key: 'stale', label: '快照新鲜度', value: snapshotFreshness.value.text }
  ]
})
const hasReplayReportId = computed(() => hasNewRiskReport.value
  && /^\d+$/.test(String(riskReport.value?.reportId ?? ''))
  && Number(riskReport.value.reportId) > 0)
const replayBreakdown = computed(() => isRecord(replayResult.value?.breakdown) ? replayResult.value.breakdown : {})
const replayDecision = computed(() => decisionText(replayResult.value?.decision))
const replayEnabledModels = computed(() => {
  const models = replayBreakdown.value.enabledModels
  return Array.isArray(models) && models.length ? models.filter(hasValue).map(String).join('、') : '-'
})
const ruleResults = computed(() => Array.isArray(riskBreakdown.value.ruleResults)
  ? riskBreakdown.value.ruleResults.filter(isRecord)
  : [])
const riskDedup = computed(() => isRecord(riskBreakdown.value.dedup) ? riskBreakdown.value.dedup : null)
const dedupDisplay = computed(() => dedupStatus(riskDedup.value))
const modelStatuses = computed(() => [
  modelStatus('woe', 'WOE申请卡', riskBreakdown.value.scorecard),
  modelStatus('rules', '硬规则', riskBreakdown.value.rules),
  modelStatus('external', '外部数据', riskBreakdown.value.external, riskBreakdown.value.skipped?.external),
  modelStatus('behavior', '行为模型', riskBreakdown.value.behavior, riskBreakdown.value.skipped?.behavior)
])
const riskLevel = computed(() => riskLevelDisplay(riskReport.value?.level))
const legacyPassedText = computed(() => {
  const passed = firstValue(riskReport.value, 'passed', 'riskPassed')
  if (passed === true) return '通过'
  if (passed === false) return '未通过'
  return '—'
})

const riskDimensions = computed(() => {
  if (!riskReport.value) return []
  const dimensions = []
  addDimension(dimensions, 'scoring-card', '旧版评分卡', firstValue(riskReport.value, 'scoringCardPoints'), firstValue(riskReport.value, 'scoringCardMax') || 100)
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

function hasOwn(source, key) {
  return Boolean(source && Object.prototype.hasOwnProperty.call(source, key))
}

function isRecord(value) {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function displayValue(value) {
  return hasValue(value) ? String(value) : '—'
}

function displayReportValue(value) {
  if (!hasValue(value)) return '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.length ? value.map(String).join('、') : '无'
  if (isRecord(value)) return '已返回'
  return String(value)
}

function displayValueOrDash(value) {
  return hasValue(value) ? String(value) : '-'
}

function displayDateTimeOrDash(value) {
  return hasValue(value) ? displayDateTime(value) : '-'
}

function formatOptionalCurrency(value) {
  return hasValue(value) ? formatLoanAmount(value) : '-'
}

function formatDecimal(value, digits = 2) {
  const number = Number(value)
  return hasValue(value) && Number.isFinite(number) ? number.toFixed(digits) : '-'
}

function formatWeight(value) {
  const number = Number(value)
  return hasValue(value) && Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : '-'
}

function formatStructuredValue(value, depth = 0) {
  if (!hasValue(value)) return '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.length
    ? value.map(item => formatStructuredValue(item, depth + 1)).join('、')
    : '无'
  if (isRecord(value)) {
    if (depth >= 2) return '已返回'
    const entries = Object.entries(value)
    return entries.length
      ? entries.map(([key, item]) => `${key}: ${formatStructuredValue(item, depth + 1)}`).join('；')
      : '无'
  }
  return String(value)
}

function consistencyText(value) {
  if (value === true) return '一致性通过'
  if (value === false) return '一致性未通过'
  return '-'
}

function fallbackText(value) {
  if (value === true) return '已使用回退权重'
  if (value === false) return '未使用回退权重'
  return '-'
}

function booleanResultText(value, truthyText, falsyText) {
  if (value === true) return truthyText
  if (value === false) return falsyText
  return '-'
}

function freshnessStatus(value) {
  if (value === true) return { text: '数据可能过期', className: 'stale' }
  if (value === false) return { text: '数据正常', className: 'fresh' }
  return { text: '未提供', className: 'unknown' }
}

function decisionText(value) {
  const normalized = String(value ?? '').trim().toLocaleUpperCase()
  const mapping = {
    APPROVED: { text: '通过', className: 'approved' },
    REJECTED: { text: '拒绝', className: 'rejected' },
    MANUAL_REVIEW: { text: '转人工', className: 'manual' }
  }
  return mapping[normalized] || {
    text: hasValue(value) ? String(value) : '-',
    className: 'unknown'
  }
}

function listFieldDisplay(source, key) {
  if (!hasOwn(source, key)) return { kind: 'missing', values: [] }
  if (!Array.isArray(source[key])) return { kind: 'missing', values: [] }
  const values = source[key].filter(hasValue).map(String)
  return values.length ? { kind: 'values', values } : { kind: 'empty', values: [] }
}

function enabledText(value) {
  if (value === true) return '已启用'
  if (value === false) return '未启用'
  return '-'
}

function modelStatus(key, label, value, skippedReason) {
  if (hasValue(value)) return { key, label, text: '已执行', className: 'completed' }
  if (String(skippedReason || '').toLocaleUpperCase() === 'NOT_IMPLEMENTED') {
    return { key, label, text: '暂未实现', className: 'unavailable' }
  }
  return { key, label, text: '未执行', className: 'pending' }
}

function dedupStatus(dedup) {
  if (!dedup) return { title: '', detail: '' }
  if (String(dedup.skipped || '').toLocaleUpperCase() === 'MISSING_PRODUCT_ID') {
    return {
      title: '本次未启用去重（缺少产品信息）',
      detail: '本次评估已直接重新计算。'
    }
  }
  if (dedup.enabled === true) {
    return {
      title: '去重机制：已启用',
      detail: dedup.reused === true ? '本次评估：复用近期结果' : '本次评估：重新计算'
    }
  }
  return {
    title: '去重机制：未启用',
    detail: dedup.reused === true ? '本次评估：复用近期结果' : '本次评估：重新计算'
  }
}

function rulePassedDisplay(value) {
  if (value === true) return { text: '通过', className: 'passed' }
  if (value === false) return { text: '未通过', className: 'failed' }
  return { text: '未知', className: 'unknown' }
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

function formatOptionalAmount(value) {
  return hasValue(value) ? formatLoanAmount(value) : '-'
}

function dateTimeValue(value) {
  return hasValue(value) ? String(value).replace(' ', 'T') : ''
}

function displayDateTime(value) {
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value
    const pad = number => String(number).padStart(2, '0')
    return `${year}-${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}:${pad(second)}`
  }
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
  replayResult.value = null
  replayError.value = ''
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

async function loadReplayReport() {
  if (!hasReplayReportId.value || replayLoading.value) return

  replayResult.value = null
  replayError.value = ''
  replayLoading.value = true
  try {
    replayResult.value = await replayRiskReport(riskReport.value.reportId)
  } catch (error) {
    replayError.value = error?.message || '历史风险报告回放失败，请稍后重试'
  } finally {
    replayLoading.value = false
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
