import { request } from './client'

const SUPPORTED_FILTER_STATUSES = new Set([
  'pending',
  'approved',
  'rejected',
  'disbursed',
  'settled'
])

function requireApplicationId(id) {
  if (id === null || id === undefined || String(id).trim() === '') {
    throw new Error('贷款申请ID不能为空')
  }
  return encodeURIComponent(String(id).trim())
}

function ensureSuccess(response, fallbackMessage) {
  if (!response || typeof response !== 'object') {
    throw new Error(fallbackMessage)
  }
  if (response.success === false) {
    throw new Error(response.message || fallbackMessage)
  }
  return response
}

/**
 * 后端状态为小写，管理员页面状态为大写；统一在 API 边界转换。
 */
export function normalizeLoanApplicationStatus(status) {
  if (status === null || status === undefined) return ''
  return String(status).trim().toLocaleUpperCase()
}

function normalizeLoanApplication(application) {
  if (!application || typeof application !== 'object') return application
  return {
    ...application,
    status: normalizeLoanApplicationStatus(application.status)
  }
}

function createListQuery(filters = {}) {
  const params = new URLSearchParams()
  const status = normalizeLoanApplicationStatus(filters.status).toLocaleLowerCase()
  const loanType = String(filters.loanType || '').trim()
  const search = String(filters.search || '').trim()

  if (status && status !== 'all' && SUPPORTED_FILTER_STATUSES.has(status)) {
    params.set('status', status)
  }
  if (loanType && loanType.toLocaleLowerCase() !== 'all') {
    params.set('loanType', loanType)
  }
  if (search) params.set('search', search)

  const query = params.toString()
  return query ? `?${query}` : ''
}

/**
 * 获取管理员贷款申请列表。
 * filters: { status?, loanType?, search? }
 */
export async function getLoanApplications(filters = {}) {
  const response = ensureSuccess(
    await request(`/api/loan-applications${createListQuery(filters)}`),
    '贷款申请列表加载失败'
  )
  if (!Array.isArray(response.data)) {
    throw new Error('贷款申请列表返回格式异常')
  }
  return response.data.map(normalizeLoanApplication)
}

export async function getLoanApplicationDetail(id) {
  const applicationId = requireApplicationId(id)
  const response = ensureSuccess(
    await request(`/api/loan-applications/${applicationId}`),
    '贷款申请详情加载失败'
  )
  if (!response.data || typeof response.data !== 'object') {
    throw new Error('贷款申请详情返回格式异常')
  }
  return normalizeLoanApplication(response.data)
}

export async function getLoanApplicationStatistics() {
  const response = ensureSuccess(
    await request('/api/loan-applications/statistics'),
    '贷款申请统计加载失败'
  )
  return response.data || {}
}

export async function approveLoanApplication(id, remark = '') {
  return submitLoanApplicationDecision(id, 'approve', remark, '贷款申请审批通过失败')
}

export async function rejectLoanApplication(id, remark = '') {
  return submitLoanApplicationDecision(id, 'reject', remark, '贷款申请审批拒绝失败')
}

async function submitLoanApplicationDecision(id, action, remark, fallbackMessage) {
  const applicationId = requireApplicationId(id)
  return ensureSuccess(
    await request(`/api/loan-applications/${applicationId}/${action}`, {
      method: 'PUT',
      body: JSON.stringify({ remark: String(remark || '').trim() })
    }),
    fallbackMessage
  )
}

/**
 * 后端没有通用状态更新接口，只支持审批通过和审批拒绝。
 */
export function updateLoanApplicationStatus(id, status, remark = '') {
  const normalizedStatus = normalizeLoanApplicationStatus(status)
  if (normalizedStatus === 'APPROVED') {
    return approveLoanApplication(id, remark)
  }
  if (normalizedStatus === 'REJECTED') {
    return rejectLoanApplication(id, remark)
  }
  return Promise.reject(new Error('后端仅支持将贷款申请更新为 APPROVED 或 REJECTED'))
}
