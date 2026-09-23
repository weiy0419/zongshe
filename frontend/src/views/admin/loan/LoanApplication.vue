<template>
  <section class="loan-page" aria-labelledby="loan-list-title">
    <header class="page-header">
      <div>
        <h1 id="loan-list-title">贷款申请管理</h1>
        <p>查看贷款申请与用户信息。</p>
      </div>
    </header>

    <form class="filter-card" aria-label="贷款申请筛选" @submit.prevent="applyFilters">
      <label class="filter-field">
        <span>用户姓名</span>
        <input v-model="filters.name" type="search" placeholder="请输入用户姓名" maxlength="100">
      </label>
      <label class="filter-field">
        <span>申请状态</span>
        <select v-model="filters.status">
          <option value="">全部</option>
          <option v-for="(label, status) in statusLabels" :key="status" :value="status">{{ label }}（{{ status }}）</option>
        </select>
      </label>
      <div class="filter-actions">
        <button class="primary-btn" type="submit" :disabled="loading">搜索</button>
        <button class="secondary-btn" type="button" :disabled="loading" @click="resetFilters">重置</button>
      </div>
    </form>

    <section class="content-card" aria-labelledby="applications-title">
      <header class="card-heading">
        <h2 id="applications-title">申请列表</h2>
        <span v-if="!loading && !errorMessage" class="result-count" role="status">共 {{ applications.length }} 条申请</span>
      </header>
      <div class="table-scroll" tabindex="0" role="region" aria-label="贷款申请表格">
        <table>
          <thead>
            <tr>
              <th scope="col">申请编号</th>
              <th scope="col">用户姓名</th>
              <th scope="col">申请金额</th>
              <th scope="col">贷款期限</th>
              <th scope="col">申请状态</th>
              <th scope="col">申请时间</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="7" class="empty-state" role="status">正在加载贷款申请...</td>
            </tr>
            <tr v-else-if="errorMessage">
              <td colspan="7" class="empty-state">
                <div class="state-content error-state" role="alert">
                  <span>{{ errorMessage }}</span>
                  <button class="secondary-btn" type="button" @click="loadApplications">重新加载</button>
                </div>
              </td>
            </tr>
            <template v-else>
              <tr v-for="application in applications" :key="application.id">
                <td class="application-number">{{ application.applicationNo }}</td>
                <td>{{ applicationUserName(application) }}</td>
                <td>{{ formatLoanAmount(application.loanAmount ?? application.amount) }}</td>
                <td>{{ application.loanTerm ?? application.term ?? '—' }} 个月</td>
                <td><span class="status-tag" :class="statusClass(application.status)">{{ loanStatusText(application.status) }}</span></td>
                <td><time :datetime="dateTimeValue(application.applyTime)">{{ displayDateTime(application.applyTime) }}</time></td>
                <td>
                  <router-link class="text-link" :to="{ name: 'admin-loan-detail', params: { id: application.id } }"
                    :aria-label="`查看${applicationUserName(application)}的申请详情`">查看详情</router-link>
                </td>
              </tr>
            </template>
            <tr v-if="!loading && !errorMessage && applications.length === 0">
              <td colspan="7" class="empty-state">暂无符合条件的贷款申请。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getLoanApplications, normalizeLoanApplicationStatus } from '../../../api/loan'

const statusLabels = Object.freeze({
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
})

const applications = ref([])
const loading = ref(false)
const errorMessage = ref('')
const filters = reactive({ name: '', status: '' })

function loanStatusText(status) {
  const normalized = normalizeLoanApplicationStatus(status)
  return statusLabels[normalized] || normalized || '未知状态'
}

function statusClass(status) {
  const normalized = normalizeLoanApplicationStatus(status)
  return ['PENDING', 'APPROVED', 'REJECTED'].includes(normalized)
    ? normalized.toLocaleLowerCase()
    : ''
}

function formatLoanAmount(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '—'
  return `¥${amount.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`
}

function applicationUserName(application) {
  return application.applicantName || application.userName || '—'
}

function dateTimeValue(value) {
  return value ? String(value).replace(' ', 'T') : ''
}

function displayDateTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function requestFilters() {
  const status = filters.status.trim()
  const search = filters.name.trim()
  return {
    ...(status ? { status } : {}),
    ...(search ? { search } : {})
  }
}

async function loadApplications() {
  if (loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    applications.value = await getLoanApplications(requestFilters())
  } catch (error) {
    applications.value = []
    errorMessage.value = error?.message || '贷款申请列表加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  loadApplications()
}

function resetFilters() {
  filters.name = ''
  filters.status = ''
  loadApplications()
}

onMounted(() => {
  loadApplications()
})
</script>

<style scoped src="./loan.css"></style>
<style scoped>
.state-content {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 14px;
}

.error-state {
  color: #b91c1c;
}

button:disabled {
  cursor: not-allowed;
  opacity: .65;
}
</style>
