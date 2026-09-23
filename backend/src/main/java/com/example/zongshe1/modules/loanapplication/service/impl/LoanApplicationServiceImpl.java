package com.example.zongshe1.modules.loanapplication.service.impl;

import com.example.zongshe1.modules.loanapplication.dto.LoanApplicationDTO;
import com.example.zongshe1.modules.loanapplication.dto.api.LoanApplicationSubmitDTO;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.repay.entity.RepayPlan;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.repay.repository.RepayPlanRepository;
import com.example.zongshe1.modules.risk.repository.RiskReportRepository;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.example.zongshe1.service.ContractService;
import com.example.zongshe1.modules.risk.service.CreditScoreCalculator;
import com.example.zongshe1.modules.loanapplication.service.LoanApplicationService;
import com.example.zongshe1.modules.risk.service.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final UserRepository userRepository;
    private final RepayPlanRepository repayPlanRepository;
    private final RiskService riskService;
    private final RiskReportRepository riskReportRepository;
    private final List<CreditScoreCalculator> creditScoreCalculators;
    private final ContractService contractService; // 加回 final！这样构造函数刚好是 7 个参数

    @Autowired(required = false)
    private RestTemplate restTemplate; // 只有 restTemplate 用 @Autowired(required = false)s

    @Override
    public List<LoanApplicationDTO> getApplications(String status, String loanType, String search) {
        List<LoanApplication> applications;

        if (status != null && !status.equals("all") && !status.isEmpty()) {
            applications = loanApplicationRepository.findByStatus(status);
        } else if (loanType != null && !loanType.equals("all") && !loanType.isEmpty()) {
            applications = loanApplicationRepository.findByLoanType(loanType);
        } else {
            applications = loanApplicationRepository.findAll();
        }

        // 应用搜索过滤
        if (search != null && !search.trim().isEmpty()) {
            String searchTerm = search.toLowerCase();
            applications = applications.stream()
                    .filter(app -> app.getApplicantName().toLowerCase().contains(searchTerm) ||
                            app.getApplicationNo().toLowerCase().contains(searchTerm) ||
                            app.getPhoneNumber().contains(searchTerm))
                    .collect(Collectors.toList());
        }

        return applications.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public LoanApplicationDTO getApplicationById(Long id) {
        return loanApplicationRepository.findById(id)
                .map(this::convertToDTO)
                .orElse(null);
    }

    @Override
        @Transactional
        public boolean approveApplication(Long id, String remark) {
            return loanApplicationRepository.findById(id)
                    .map(application -> {
                        application.setStatus("approved");
                        application.setAuditRemark(remark);
                        application.setAuditTime(LocalDateTime.now());
                        loanApplicationRepository.save(application);
                        
                        updateCreditByEvent(application, "APPROVE");
                        
                        // 增加非空校验，防止 contractService 未注入时直接抛 NPE
                        if (contractService != null) {
                            try {
                                contractService.createContractForApplication(application.getId());
                            } catch (Exception ex) {
                                log.warn("审批通过后自动生成合同失败：applicationId={}", id, ex);
                            }
                        } else {
                            log.warn("ContractService 未注入，跳过自动生成合同步骤");
                        }

                        ensureRepayPlansForApplication(application);
                        return true;
                    })
                    .orElse(false);
        }

        /**
         * 确保为申请生成还款计划（示例/辅助方法）
         */
        private void ensureRepayPlansForApplication(LoanApplication application) {
            // 如果你有还款计划生成的服务，可以在这里调用，例如：
            // repayPlanService.generatePlans(application);
            log.info("为贷款申请 ID: {} 初始化还款计划逻辑", application.getId());
        }

    @Override
    @Transactional
    public boolean rejectApplication(Long id, String remark) {
        return loanApplicationRepository.findById(id)
                .map(application -> {
                    application.setStatus("rejected");
                    application.setAuditRemark(remark);
                    application.setAuditTime(LocalDateTime.now());
                    loanApplicationRepository.save(application);
                    updateCreditByEvent(application, "REJECT");
                    return true;
                })
                .orElse(false);
    }

    @Override
    public Map<String, Object> getStatistics() {
        List<LoanApplication> allApplications = loanApplicationRepository.findAll();

        Map<String, Object> statistics = new HashMap<>();

        // 统计申请数量
        long total = allApplications.size();
        long approved = allApplications.stream().filter(a -> "approved".equals(a.getStatus())).count();
        long pending = allApplications.stream().filter(a -> "pending".equals(a.getStatus())).count();
        long rejected = allApplications.stream().filter(a -> "rejected".equals(a.getStatus())).count();

        // 统计申请金额
        BigDecimal totalAmount = allApplications.stream()
                .map(LoanApplication::getLoanAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算百分比
        double approvedPercent = total > 0 ? (double) approved / total * 100 : 0;
        double pendingPercent = total > 0 ? (double) pending / total * 100 : 0;
        double rejectedPercent = total > 0 ? (double) rejected / total * 100 : 0;

        statistics.put("total", total);
        statistics.put("approved", approved);
        statistics.put("pending", pending);
        statistics.put("rejected", rejected);
        statistics.put("totalAmount", totalAmount);
        statistics.put("approvedPercent", Math.round(approvedPercent));
        statistics.put("pendingPercent", Math.round(pendingPercent));
        statistics.put("rejectedPercent", Math.round(rejectedPercent));

        // 按贷款类型统计
        Map<String, Long> byType = allApplications.stream()
                .collect(Collectors.groupingBy(LoanApplication::getLoanType, Collectors.counting()));
        statistics.put("byType", byType);

        // 最近6个月的申请趋势
        Map<String, Map<String, Long>> trendData = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 5; i >= 0; i--) {
            LocalDateTime month = now.minusMonths(i);
            String monthKey = month.getYear() + "-" + String.format("%02d", month.getMonthValue());

            Map<String, Long> monthStats = new HashMap<>();
            monthStats.put("approved", 0L);
            monthStats.put("pending", 0L);
            monthStats.put("rejected", 0L);

            // 这里简化处理，实际应该按月份查询
            trendData.put(monthKey, monthStats);
        }
        statistics.put("trend", trendData);

        return statistics;
    }

    @Override
    @Transactional
    public Map<String, Object> submitApplication(String userId, LoanApplicationSubmitDTO dto) {
        Map<String, Object> result = new HashMap<>();
        try {
        // 1. 查找用户
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 2. 检查用户状态
        if (user.getUserStatus() != 1) {
        result.put("success", false);
        result.put("message", "用户账户状态异常，无法申请贷款");
        return result;
        }

        // 3. 检查贷款资格
        if (user.getCreditScore() < 450) {
        result.put("success", false);
        result.put("message", "信用分不足，无法申请贷款");
        return result;
        }

        // 4. 生成申请单号
        String applicationNo = "LA-" + System.currentTimeMillis();

        // 5. 创建贷款申请（从 dto 中安全提取字段）
        LoanApplication application = new LoanApplication();
        application.setApplicationNo(applicationNo);
        application.setApplicantName(user.getUserName() != null ? user.getUserName() : "未设置");
        application.setIdCardNumber(user.getIdCardNumber() != null ? user.getIdCardNumber() : "");
        application.setPhoneNumber(user.getPhoneNumber());

        // 字段映射改造：
        application.setLoanType("个人消费贷"); // 也可以通过 dto.getProductId() 去查询 Product 表获取具体类型
        application.setLoanAmount(dto.getAmount());
        application.setLoanTerm(dto.getTermMonths());
        application.setApplyTime(LocalDateTime.now());
        application.setStatus("pending");
        application.setDescription(dto.getUsage());
        application.setUser(user);

        // 6. 保存申请并更新信用积分事件
        loanApplicationRepository.save(application);
        updateCreditByEvent(application, "SUBMIT");

        // 7. 触发风控评估（对接薛雨宸策略 + 岳炜杰风控评估）
        Map<String, Object> riskAssessment = null;
        try {
        // 7.1 调用薛雨宸的接口获取启用的风控模型[cite: 2, 3, 4]
        String policyUrl = "http://localhost:8080/api/ops/risk-policy";
        Map<String, Object> policyResponse = restTemplate.getForObject(policyUrl, Map.class);
        log.info("获取风控策略成功: {}", policyResponse);

        // 7.2 调用岳炜杰的风控评估接口[cite: 2, 3, 4]
        String assessUrl = "http://localhost:8080/api/risk/assess";
        Map<String, Object> assessRequest = new HashMap<>();
        assessRequest.put("applicationId", application.getId());
        if (policyResponse != null && policyResponse.containsKey("enabledModels")) {
            assessRequest.put("enabledModels", policyResponse.get("enabledModels"));
        }

        riskAssessment = restTemplate.postForObject(assessUrl, assessRequest, Map.class);
        log.info("风控评估结果: {}", riskAssessment);

        } catch (Exception riskEx) {
        // 容错机制：接口超时或调不通时，不阻断申贷流程，标记为 RISK_PENDING
        log.error("调用风控评估接口超时或失败，转为风控待处理状态: applicationId={}", application.getId(), riskEx);
        application.setStatus("RISK_PENDING");
        loanApplicationRepository.save(application);
        }

        // 8. 预留发送 loan.applied MQ 消息
        sendLoanAppliedMQ(application);

        // 9. 返回结果
        result.put("success", true);
        result.put("applicationNo", applicationNo);
        result.put("applicationId", application.getId());
        result.put("message", "贷款申请提交成功");
        if (riskAssessment != null) {
        result.put("riskAssessment", riskAssessment);
        result.put("riskPassed", riskAssessment.get("passed"));
        }

        log.info("贷款申请提交成功：userId={}, applicationNo={}", userId, applicationNo);

        } catch (Exception e) {
        log.error("提交贷款申请失败", e);
        result.put("success", false);
        result.put("message", "提交申请失败：" + e.getMessage());
        }

        return result;
        }

    @Override
    public List<LoanApplicationDTO> getApplicationsByUserId(String userId) {
        List<LoanApplication> applications = loanApplicationRepository.findByUserIdOrderByApplyTimeDesc(userId);
        return applications.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private LoanApplicationDTO convertToDTO(LoanApplication application) {
        LoanApplicationDTO dto = new LoanApplicationDTO();
        dto.setId(application.getId());
        dto.setApplicationNo(application.getApplicationNo());
        dto.setApplicantName(application.getApplicantName());
        dto.setIdCardNumber(application.getIdCardNumber());
        dto.setPhoneNumber(application.getPhoneNumber());
        dto.setLoanType(application.getLoanType());
        dto.setLoanAmount(application.getLoanAmount());
        dto.setLoanTerm(application.getLoanTerm());
        dto.setApplyTime(application.getApplyTime());
        dto.setStatus(application.getStatus());
        dto.setStatusText(loanStatusText(application.getStatus()));
        dto.setAuditRemark(application.getAuditRemark());
        dto.setAuditTime(application.getAuditTime());
        dto.setIncomeInfo(application.getIncomeInfo());
        dto.setDescription(application.getDescription());

        riskReportRepository.findByLoanApplication_Id(application.getId()).ifPresent(report -> {
            dto.setRiskScore(report.getRiskScore());
            dto.setRiskLevel(report.getRiskLevel());
            dto.setRiskPassed(report.getPassed());
            dto.setScoringCardPoints(report.getScoringCardPoints());
            dto.setScoringCardMax(report.getScoringCardMax());
            dto.setRejectReason(report.getRejectReason());
        });
        return dto;
    }

    private String loanStatusText(String status) {
        if (status == null || status.isBlank()) {
            return "未知";
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "pending" -> "审核中";
            case "approved" -> "已通过";
            case "rejected" -> "已拒绝";
            case "disbursed" -> "已放款";
            case "settled", "paid" -> "已还清";
            default -> status;
        };
    }

    private void updateCreditByEvent(LoanApplication application, String eventType) {
        if (application.getUser() == null) {
            return;
        }
        creditScoreCalculators.stream()
                .min((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .ifPresent(calculator -> calculator.updateCreditScoreByLoanEvent(
                        application.getUser().getUserId(), application, eventType));
    }

    /**
 * 预留发送申贷成功 MQ 消息[cite: 2]
 */
private void sendLoanAppliedMQ(LoanApplication application) {
    try {
        // TODO: rabbitTemplate.convertAndSend("loan.exchange", "loan.applied", application.getApplicationNo());
        log.info("[MQ 预留] 成功发送申贷消息 loan.applied，申请单号: {}", application.getApplicationNo());
    } catch (Exception e) {
        log.warn("[MQ 预留] 发送申贷消息失败", e);
    }
}
}