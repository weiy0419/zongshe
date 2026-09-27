import { request } from './client'

function requireId(value, label) {
  if (value === null || value === undefined || String(value).trim() === '') {
    throw new Error(`${label}不能为空`)
  }
  return encodeURIComponent(String(value).trim())
}

function firstValue(source, ...keys) {
  if (!source || typeof source !== 'object') return undefined
  for (const key of keys) {
    const value = source[key]
    if (value !== null && value !== undefined && value !== '') return value
  }
  return undefined
}

function ensureObject(value, fallbackMessage) {
  if (!value || typeof value !== 'object') {
    throw new Error(fallbackMessage)
  }
  return value
}

function normalizeRiskReport(report) {
  if (!report || typeof report !== 'object') return report

  const passed = firstValue(report, 'passed', 'riskPassed')
  const rejectReason = firstValue(report, 'rejectReason')
  const legacyScore = firstValue(report, 'score', 'riskScore', 'overallRiskScore')
  const legacyLevel = firstValue(report, 'level', 'riskLevel', 'overallRiskLevel')
  const suggestion = firstValue(report, 'suggestion', 'advice')
    ?? (rejectReason || (passed === true ? '建议通过' : passed === false ? '建议拒绝' : undefined))

  return {
    ...report,
    ...(legacyScore !== undefined ? { score: legacyScore } : {}),
    ...(legacyLevel !== undefined ? { level: legacyLevel } : {}),
    ...(suggestion !== undefined ? { suggestion } : {})
  }
}

/**
 * 获取已生成的风险报告摘要。该接口不会主动触发风险重评。
 * 无风险报告时返回 null。
 */
export async function getRiskReport(applicationId) {
  const id = requireId(applicationId, '贷款申请ID')
  const response = ensureObject(
    await request(`/api/risk/applications/${id}/report`),
    '风险报告返回格式异常'
  )

  if (response.success === false) {
    const message = String(response.message || '').trim()
    if (message === '暂无风控报告' || message.includes('暂无')) return null
    throw new Error(message || '风险报告加载失败')
  }

  return normalizeRiskReport(ensureObject(response.data, '风险报告返回格式异常'))
}

/**
 * 获取指定用户的信誉分评估。
 */
export async function getCreditEvaluation(userId) {
  const id = requireId(userId, '用户ID')
  const response = ensureObject(
    await request(`/api/credit/users/${id}/evaluation`),
    '信誉分返回格式异常'
  )

  if (response.success === false) {
    throw new Error(response.message || '信誉分加载失败')
  }

  const evaluation = response.data && typeof response.data === 'object'
    ? response.data
    : response
  return {
    ...evaluation,
    score: firstValue(evaluation, 'score', 'creditScore'),
    level: firstValue(evaluation, 'level', 'creditLevel')
  }
}
