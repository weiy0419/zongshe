package com.example.zongshe1.modules.risk.service.impl;

import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.common.exception.BusinessException;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.risk.entity.RiskReport;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.risk.repository.RiskReportRepository;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.example.zongshe1.modules.product.repository.ProductRepository;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureContextService;
import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.service.RiskRuleEvaluator;
import com.example.zongshe1.modules.risk.service.RiskService;
import com.example.zongshe1.modules.risk.service.RiskAssessmentDedupService;
import com.example.zongshe1.modules.risk.service.RiskReportAssembler;
import com.example.zongshe1.modules.risk.service.RiskDecisionEventService;
import com.example.zongshe1.modules.risk.service.TwoStageRiskOrchestrator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 风控服务实现类
 * 
 * <p>整合所有风控规则评估器，提供统一的风控服务</p>
 * 
 * <p>扩展方式：
 * <ul>
 *   <li>系统会自动注入所有实现RiskRuleEvaluator接口的Bean</li>
 *   <li>按优先级顺序执行所有启用的评估器</li>
 *   <li>如果任何一个评估器返回passed=false，则整体评估失败</li>
 *   <li>综合所有评估器的结果，生成最终的风险评分和等级</li>
 * </ul>
 * </p>
 * 
 * @author 开发团队
 * @since 2025-12-21
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskServiceImpl implements RiskService {

    private final List<RiskRuleEvaluator> riskRuleEvaluators;
    private final LoanApplicationRepository loanApplicationRepository;
    private final UserRepository userRepository;
    private final RiskReportRepository riskReportRepository;
    private final FeatureContextService featureContextService;
    private final CurrentRiskFeatures currentRiskFeatures;
    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final TwoStageRiskOrchestrator twoStageRiskOrchestrator;
    private final RiskAssessmentDedupService assessmentDedupService;
    private final RiskReportAssembler reportAssembler;
    private final RiskDecisionEventService decisionEvents;

    @Override
    @Transactional
    public RiskReportDTO assess(RiskAssessmentRequest request) {
        if (request == null || request.getApplicationId() == null || request.getApplicationId() <= 0) {
            throw new BusinessException("有效的 applicationId 不能为空");
        }
        List<String> enabledModels;
        try {
            enabledModels = com.example.zongshe1.modules.risk.service.EnabledRiskModels.normalize(request.getEnabledModels());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage());
        }
        LoanApplication application = loanApplicationRepository.findById(request.getApplicationId())
                .orElseThrow(() -> new BusinessException("贷款申请不存在，请在申请提交后再评估"));
        if (application.getUser() == null || application.getUser().getId() == null) {
            throw new BusinessException("贷款申请未关联有效用户");
        }
        User user = userRepository.findById(application.getUser().getId())
                .orElseThrow(() -> new BusinessException("申请用户不存在"));
        if (request.getUserId() != null && !request.getUserId().equals(user.getId())) {
            throw new BusinessException("userId 与申请所属用户不一致");
        }
        if (application.getLoanAmount() == null || application.getLoanAmount().signum() <= 0) {
            throw new BusinessException("申请金额必须大于 0");
        }
        // 使用已保存的申请输入计算，拒绝与申请不一致的覆盖值。
        if (request.getLoanAmount() != null
                && request.getLoanAmount().compareTo(application.getLoanAmount()) != 0) {
            throw new BusinessException("loanAmount 与申请金额不一致");
        }
        if (request.getLoanTerm() != null && !request.getLoanTerm().equals(application.getLoanTerm())) {
            throw new BusinessException("loanTerm 与申请期限不一致");
        }

        TwoStageRiskOrchestrator.Result result;
        Map<String, Object> dedup = new LinkedHashMap<>();
        if (request.getProductId() == null) {
            // 现有申请实体没有产品 ID；旧请求可真实评估，但不能用贷款类型伪造去重键。
            log.info("风控评估跳过去重：applicationId={}，原因=MISSING_PRODUCT_ID", application.getId());
            result = twoStageRiskOrchestrator.assess(user, application, enabledModels);
            dedup.put("enabled", false);
            dedup.put("reused", false);
            dedup.put("skipped", "MISSING_PRODUCT_ID");
        } else {
            if (request.getProductId() <= 0 || !productRepository.existsById(request.getProductId())) {
                throw new BusinessException("productId 必须对应真实产品");
            }
            RiskAssessmentDedupService.Lookup lookup = assessmentDedupService.assess(
                    user.getId(), request.getProductId(), application.getLoanAmount(), enabledModels,
                    () -> twoStageRiskOrchestrator.assess(user, application, enabledModels));
            result = lookup.result();
            boolean reused = lookup.reused();
            // Cached credit must never bypass today's admission rules. Behavior is always refreshed.
            if (reused) {
                boolean userChanged = !sameInteger(result.features().get("credit_score"), user.getCreditScore())
                        || !sameInteger(result.features().get("user_status"), user.getUserStatus());
                TwoStageRiskOrchestrator.Result validated = twoStageRiskOrchestrator.revalidate(
                        user, application, enabledModels, result);
                reused = validated == result;
                result = validated;
                if (!reused) dedup.put("skipped", userChanged ? "USER_INPUT_CHANGED" : "LIVE_EVIDENCE_REEVALUATED");
            }
            dedup.put("enabled", true);
            dedup.put("reused", reused);
        }
        RiskReportDTO report = reportAssembler.assemble(
                result,
                null,
                application.getId(),
                user.getId()
        );
        report.getBreakdown().put("dedup", dedup);

        // 同一申请维护一份当前风控报告
        RiskReport entity = riskReportRepository.findForAssessment(application.getId())
                .orElseGet(RiskReport::new);
        String previousDetailsJson = entity.getDetailsJson();

        // A computation cache hit may belong to another application or assessment.
        // Only decorate an identical persisted snapshot; never dirty its managed entity.
        if (entity.getId() != null && Boolean.TRUE.equals(dedup.get("reused"))) {
            report.setReportId(entity.getId());
            RiskReportDTO reusedReport = reusePersistedReport(previousDetailsJson, report);
            if (reusedReport != null) return reusedReport;
        }

        entity.setLoanApplication(application);

        // 兼容旧字段
        entity.setPassed("APPROVED".equals(report.getDecision()));
        entity.setRejectReason(report.getReasonSummary());
        entity.setScoringCardPoints(report.getTotalScore());
        entity.setScoringCardMax(report.getMaxScore());
        entity.setCardVersion(report.getCardVersion());

        try {
            // IDENTITY 新报告先取得真实 ID；此时尚未提交，后续失败必须整笔回滚。
            boolean newReport = entity.getId() == null;
            if (newReport) {
                entity = riskReportRepository.save(entity);
            }
            report.setReportId(entity.getId());
            entity.setDetailsJson(serializeAssessmentReport(report));
            if (!newReport) {
                riskReportRepository.save(entity);
            }
            // 新实体由当前事务管理，脏检查写入完整 JSON；同时在返回前暴露约束冲突。
            riskReportRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            log.warn("风控报告保存违反数据约束：applicationId={}", application.getId(), exception);
            // 不在失败事务中继续查询或伪造成功；数据库唯一约束仍是最终保护。
            throw new BusinessException("RISK_REPORT_SAVE_CONFLICT",
                    "风控报告保存违反数据约束；若同一申请正在并发评估，请稍后重试");
        }

        decisionEvents.afterReportPersisted(previousDetailsJson, report);
        return report;
    }

    private RiskReportDTO reusePersistedReport(String json, RiskReportDTO candidate) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode persisted = objectMapper.readTree(json);
            JsonNode current = objectMapper.readTree(serializeAssessmentReport(candidate));
            // Compare identities, model configuration, evidence, scores and timestamp.
            // Only this request's reuse flag is allowed to differ.
            if (!(persisted.path("breakdown").path("dedup") instanceof ObjectNode oldDedup)
                    || !(current.path("breakdown").path("dedup") instanceof ObjectNode newDedup)) {
                return null;
            }
            oldDedup.remove("reused");
            newDedup.remove("reused");
            if (!persisted.equals(current)) return null;

            RiskReportDTO response = objectMapper.readValue(json, RiskReportDTO.class);
            Map<String, Object> responseDedup = new LinkedHashMap<>();
            ((Map<?, ?>) response.getBreakdown().get("dedup"))
                    .forEach((key, value) -> responseDedup.put((String) key, value));
            responseDedup.put("reused", true);
            response.getBreakdown().put("dedup", responseDedup);
            return response;
        } catch (JsonProcessingException exception) {
            // Legacy/unreadable snapshots cannot establish identity; retain reassessment behavior.
            return null;
        }
    }

    private String serializeAssessmentReport(RiskReportDTO report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            log.error("风控报告序列化失败：applicationId={}", report.getApplicationId(), exception);
            throw new BusinessException("RISK_REPORT_SERIALIZATION_FAILED", "风控报告序列化失败，评估未保存");
        }
    }

    /**
     * Replay persisted results, not today's assessment policy. The assessment entry point
     * builds live features and model configuration is not versioned in the snapshot.
     * Do not traverse the entity's application/user association or persist this DTO.
     */
    @Override
    @Transactional(readOnly = true)
    public RiskReportDTO replay(Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new BusinessException("INVALID_REPORT_ID", "有效的 reportId 不能为空");
        }
        RiskReport entity = riskReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException("RISK_REPORT_NOT_FOUND", "风险报告不存在：" + reportId));
        if (entity.getDetailsJson() == null || entity.getDetailsJson().isBlank()) {
            throw new BusinessException("RISK_REPLAY_UNAVAILABLE", "历史报告缺少持久化结果，无法回放");
        }
        RiskReportDTO report;
        try {
            report = objectMapper.readValue(entity.getDetailsJson(), RiskReportDTO.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RISK_REPLAY_UNAVAILABLE", "历史报告数据解析失败，无法回放");
        }
        if (report == null || report.getDecision() == null || report.getDecision().isBlank()) {
            throw new BusinessException("RISK_REPLAY_UNAVAILABLE", "历史报告缺少决策结果，无法回放");
        }
        // Older reports may lack a JSON reportId; the entity ID is authoritative.
        report.setReportId(entity.getId());
        Map<String, Object> breakdown = report.getBreakdown() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(report.getBreakdown());
        // Older DTOs may retain model context only in snapshot.extra. Never infer
        // missing context from today's defaults or manufacture absent AHP values.
        if (report.getFeatureSnapshot() != null && report.getFeatureSnapshot().getExtra() != null) {
            Map<String, Object> extra = report.getFeatureSnapshot().getExtra();
            for (String key : List.of("enabledModels", "executedModels", "fusionMode",
                    "modelResults", "modelScores", "normalizedModelScores", "ahp")) {
                if (!breakdown.containsKey(key) && extra.containsKey(key)) {
                    breakdown.put(key, extra.get(key));
                }
            }
        }
        breakdown.put("replay", true);
        breakdown.put("replaySourceReportId", entity.getId());
        breakdown.put("replayMode", "PERSISTED_RESULT");
        report.setBreakdown(breakdown);
        return report;
    }

    private static boolean sameInteger(Object value, Integer expected) {
        return value instanceof Number number && expected != null && number.intValue() == expected;
    }

    @Override
    @Transactional
    public Map<String, Object> performRiskAssessment(Long applicationId) {
        log.info("开始执行风险评估：applicationId={}", applicationId);

        // 1. 查询贷款申请和用户信息
        LoanApplication application = loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("贷款申请不存在：" + applicationId));
        
        User user = userRepository.findByUserId(application.getUser().getUserId())
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        FeatureSnapshot featureSnapshot = featureContextService.build(user, application);
        currentRiskFeatures.set(featureSnapshot);
        try {
            log.debug("特征快照已构建：featureCount={}, sources={}",
                    featureSnapshot.getFeatures().size(),
                    featureSnapshot.getSourceResults().keySet());

            List<RiskRuleEvaluator> enabledEvaluators = getEnabledEvaluators();

            List<Map<String, Object>> ruleResults = new ArrayList<>();
            int maxRiskScore = 0;
            int maxRiskLevel = 1;
            boolean allPassed = true;
            String rejectReason = null;

            for (RiskRuleEvaluator evaluator : enabledEvaluators) {
                try {
                    Map<String, Object> ruleResult = evaluator.evaluateRisk(user, application);
                    ruleResult.put("ruleName", evaluator.getRuleName());
                    ruleResults.add(ruleResult);

                    Boolean passed = (Boolean) ruleResult.get("passed");
                    if (passed != null && !passed) {
                        allPassed = false;
                        if (rejectReason == null) {
                            rejectReason = (String) ruleResult.get("reason");
                        }
                    }

                    Integer riskScore = (Integer) ruleResult.get("riskScore");
                    Integer riskLevel = (Integer) ruleResult.get("riskLevel");
                    if (riskScore != null && riskScore > maxRiskScore) {
                        maxRiskScore = riskScore;
                    }
                    if (riskLevel != null && riskLevel > maxRiskLevel) {
                        maxRiskLevel = riskLevel;
                    }

                    log.debug("风控规则评估完成：ruleName={}, passed={}, riskScore={}, riskLevel={}",
                            evaluator.getRuleName(), passed, riskScore, riskLevel);
                } catch (Exception e) {
                    log.error("执行风控规则评估失败：ruleName={}, error={}",
                            evaluator.getRuleName(), e.getMessage(), e);
                    allPassed = false;
                    if (rejectReason == null) {
                        rejectReason = "风控规则评估异常：" + evaluator.getRuleName();
                    }
                }
            }

            RiskReport riskReport = riskReportRepository.findByLoanApplication(application)
                    .orElseGet(RiskReport::new);
            riskReport.setLoanApplication(application);
            riskReport.setRiskScore(maxRiskScore);
            riskReport.setRiskLevel(maxRiskLevel);
            riskReport.setPassed(allPassed);
            riskReport.setRejectReason(rejectReason);
            extractScoringCardDetails(ruleResults, riskReport);
            riskReport.setDetailsJson(toJson(Map.of(
                    "ruleResults", ruleResults,
                    "featureSnapshot", featureSnapshot.getFeatures(),
                    "dataSourceResults", featureSnapshot.getSourceResults()
            )));
            riskReport = riskReportRepository.save(riskReport);

            Map<String, Object> result = new HashMap<>();
            result.put("overallRiskScore", maxRiskScore);
            result.put("overallRiskLevel", maxRiskLevel);
            result.put("passed", allPassed);
            result.put("rejectReason", rejectReason);
            result.put("ruleResults", ruleResults);
            result.put("riskReportId", riskReport.getId());
            result.put("scoringCardPoints", riskReport.getScoringCardPoints());
            result.put("scoringCardMax", riskReport.getScoringCardMax());
            result.put("cardVersion", riskReport.getCardVersion());
            result.put("evaluatedAt", new Date());
            result.put("featureSnapshot", featureSnapshot.getFeatures());
            result.put("dataSourceResults", featureSnapshot.getSourceResults());

            log.info("风险评估完成：applicationId={}, passed={}, riskScore={}, riskLevel={}, ruleCount={}",
                    applicationId, allPassed, maxRiskScore, maxRiskLevel, ruleResults.size());

            return result;
        } finally {
            currentRiskFeatures.clear();
        }
    }

    @Override
    public RiskReport getRiskReportByApplicationId(Long applicationId) {
        LoanApplication application = loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("贷款申请不存在：" + applicationId));
        
        return riskReportRepository.findByLoanApplication(application)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public RiskReportDTO getRiskReportDTOByApplicationId(Long applicationId) {
        RiskReport entity = riskReportRepository
                .findByLoanApplication_Id(applicationId)
                .orElse(null);

        if (entity == null
                || entity.getDetailsJson() == null
                || entity.getDetailsJson().isBlank()) {
            return null;
        }

        try {
            RiskReportDTO report =
                    objectMapper.readValue(entity.getDetailsJson(), RiskReportDTO.class);

            report.setReportId(entity.getId());
            report.setApplicationId(applicationId);

            return report;
        } catch (JsonProcessingException e) {
            log.error("解析新版风控报告失败：applicationId={}", applicationId, e);
            throw new BusinessException("风控报告数据解析失败");
        }
    }

    @Override
    public List<RiskRuleEvaluator> getEnabledEvaluators() {
        return riskRuleEvaluators.stream()
                .filter(RiskRuleEvaluator::isEnabled)
                .sorted(Comparator.comparingInt(RiskRuleEvaluator::getPriority))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public Map<String, Object> triggerRiskAssessment(Long applicationId) {
        return performRiskAssessment(applicationId);
    }

    @Override
    @Transactional
    public Map<String, Object> batchRiskAssessment(List<Long> applicationIds) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> batchResults = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (Long applicationId : applicationIds) {
            try {
                Map<String, Object> assessmentResult = performRiskAssessment(applicationId);
                assessmentResult.put("applicationId", applicationId);
                batchResults.add(assessmentResult);
                successCount++;
            } catch (Exception e) {
                log.error("批量风险评估失败：applicationId={}, error={}", applicationId, e.getMessage(), e);
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("applicationId", applicationId);
                errorResult.put("success", false);
                errorResult.put("error", e.getMessage());
                batchResults.add(errorResult);
                failCount++;
            }
        }

        result.put("totalCount", applicationIds.size());
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("results", batchResults);

        log.info("批量风险评估完成：total={}, success={}, fail={}", 
                applicationIds.size(), successCount, failCount);

        return result;
    }

    private void extractScoringCardDetails(List<Map<String, Object>> ruleResults, RiskReport riskReport) {
        for (Map<String, Object> ruleResult : ruleResults) {
            Object details = ruleResult.get("details");
            if (!(details instanceof Map<?, ?> detailsMap)) {
                continue;
            }
            Object totalPoints = detailsMap.get("totalPoints");
            Object maxPoints = detailsMap.get("maxPoints");
            Object cardVersion = detailsMap.get("cardVersion");
            if (totalPoints instanceof Number total) {
                riskReport.setScoringCardPoints(total.intValue());
            }
            if (maxPoints instanceof Number max) {
                riskReport.setScoringCardMax(max.intValue());
            }
            if (cardVersion != null) {
                riskReport.setCardVersion(String.valueOf(cardVersion));
            }
            break;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("风控详情序列化失败：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> getRiskAssessmentDetail(Long applicationId) {
        LoanApplication application = loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("贷款申请不存在：" + applicationId));

        RiskReport report = riskReportRepository.findByLoanApplication(application).orElse(null);
        if (report == null) {
            return performRiskAssessment(applicationId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("applicationId", applicationId);
        result.put("overallRiskScore", report.getRiskScore());
        result.put("overallRiskLevel", report.getRiskLevel());
        result.put("passed", Boolean.TRUE.equals(report.getPassed()));
        result.put("rejectReason", report.getRejectReason());
        result.put("riskReportId", report.getId());
        result.put("scoringCardPoints", report.getScoringCardPoints());
        result.put("scoringCardMax", report.getScoringCardMax());
        result.put("cardVersion", report.getCardVersion());

        if (report.getDetailsJson() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = objectMapper.readValue(report.getDetailsJson(), Map.class);
                result.put("ruleResults", details.get("ruleResults"));
                result.put("featureSnapshot", details.get("featureSnapshot"));
                result.put("dataSourceResults", details.get("dataSourceResults"));
            } catch (JsonProcessingException e) {
                log.warn("解析风控详情失败：applicationId={}", applicationId);
            }
        }
        return result;
    }
}
