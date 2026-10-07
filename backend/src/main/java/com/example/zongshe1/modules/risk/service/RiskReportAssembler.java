package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.dto.FeatureSnapshotDTO;
import com.example.zongshe1.common.dto.RiskReportDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 只负责现有 DTO 的组装，不生成 ID、不查询外网、不写数据库。
 * 单模型 totalScore 为该模型资质分（越高越好）；双模型为 AHP 融合分数。
 * 输出申请卡、硬规则、外部公示和行为分；未执行模型用 null 分数及 skipped 原因说明。
 */
@Component
public class RiskReportAssembler {

    /** reportId 在尚未落库时允许为空；其他 ID 由接入层绑定当前申请。 */
    public RiskReportDTO assemble(TwoStageRiskOrchestrator.Result result,
                                  Long reportId, Long applicationId, Long userId) {
        Objects.requireNonNull(result, "评估结果不能为空");
        if (applicationId == null || applicationId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("申请 ID 和用户主键必须有效");
        }
        RiskReportDTO report = new RiskReportDTO();
        report.setReportId(reportId);
        report.setApplicationId(applicationId);
        report.setUserId(userId);
        report.setDecision(result.decision());
        report.setReasonSummary(result.reasonSummary());
        report.setReasonCodes(new ArrayList<>(result.reasonCodes()));
        report.setAssessedAt(result.assessedAt());

        Map<String, Object> cardA = new LinkedHashMap<>(result.cardA());
        cardA.putIfAbsent("score", null);
        cardA.putIfAbsent("version", null);
        report.setTotalScore(integer(cardA.get("score")));
        report.setMaxScore(integer(cardA.get("maxScore")));
        report.setCardVersion(cardA.get("version") instanceof String version ? version : null);

        FeatureSnapshotDTO snapshot = snapshot(userId, result.features());
        report.setFeatureSnapshot(snapshot);
        report.setStale(snapshot.getStale());

        Object enabledModels = result.features().getOrDefault("enabledModels", java.util.List.of());
        Map<?, ?> modelResults = result.features().get("modelResults") instanceof Map<?, ?> models
                ? models : Map.of();
        Map<String, String> skipped = new LinkedHashMap<>();
        if (cardA.get("skipped") instanceof String reason) {
            skipped.put("scorecard", reason);
        } else if (report.getTotalScore() == null) {
            skipped.put("scorecard", "NOT_EXECUTED");
        }
        if (result.ruleResults().stream().noneMatch(rule -> "ADMISSION".equals(rule.get("stage")))) {
            skipped.put("rules", "NOT_EXECUTED");
        }
        if (!modelResults.containsKey("EXTERNAL")) {
            skipped.put("external", enabledModels instanceof java.util.List<?> models && models.contains("EXTERNAL")
                    ? "ADMISSION".equals(result.stage()) ? "ADMISSION_NOT_PASSED" : "EVALUATION_ERROR"
                    : "DISABLED");
        }
        skipped.put("behavior", "NOT_IMPLEMENTED");

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("stage", result.stage());
        breakdown.put("admission", result.admission());
        breakdown.put("scorecard", skipped.containsKey("scorecard") ? null : report.getTotalScore());
        breakdown.put("scorecardVersion", report.getCardVersion());
        breakdown.put("scorecardDetails", cardA);
        breakdown.put("rules", skipped.containsKey("rules") ? null : result.admission());
        // 公示原始特征保留在 featureSnapshot，不能冒充外部模型分数。
        breakdown.put("external", modelResults.get("EXTERNAL") instanceof Map<?, ?> external
                ? external.get("score") : null);
        breakdown.put("modelResults", modelResults);
        breakdown.put("executedModels", result.features().getOrDefault("executedModels", java.util.List.of()));
        breakdown.put("behavior", null);
        breakdown.put("enabledModels", enabledModels);
        breakdown.put("skipped", skipped);
        breakdown.put("suggestLimit", null);
        breakdown.put("ruleResults", new ArrayList<>(result.ruleResults()));
        breakdown.put("fusionMode", result.features().getOrDefault("fusionMode", "NOT_EXECUTED"));
        Object credit = result.features().get("credit");
        breakdown.put("credit", credit);
        if (credit instanceof Map<?, ?> creditResult) {
            if (creditResult.get("skipped") instanceof String reason) skipped.put("credit", reason);
            if ("AHP".equals(breakdown.get("fusionMode")) || ("SINGLE".equals(breakdown.get("fusionMode"))
                    && "EXTERNAL".equals(creditResult.get("modelName")))) {
                report.setTotalScore(integer(creditResult.get("score")));
                report.setMaxScore(integer(creditResult.get("maxScore")));
            }
        }
        for (String key : java.util.List.of("modelScores", "normalizedModelScores", "ahp")) {
            if (result.features().containsKey(key)) breakdown.put(key, result.features().get(key));
        }
        report.setBreakdown(breakdown);
        return report;
    }

    private static FeatureSnapshotDTO snapshot(Long userId, Map<String, Object> features) {
        FeatureSnapshotDTO snapshot = new FeatureSnapshotDTO();
        snapshot.setUserId(userId);
        snapshot.setCreditScore(integer(features.get("credit_score")));
        snapshot.setOverdueCount(integer(features.get("credit_overdue_count")));
        snapshot.setLoanAmount(decimal(features.get("loan_amount")));
        snapshot.setLoanTerm(integer(features.get("loan_term")));
        snapshot.setDishonestHit(features.get("dishonestHit") instanceof Boolean hit ? hit : null);
        snapshot.setLpr1y(decimal(features.get("lpr1y")));
        snapshot.setLpr5y(decimal(features.get("lpr5y")));
        // 当前评估上下文尚未携带公示新鲜度，不把未知状态写成 false。
        snapshot.setStale(features.get("stale") instanceof Boolean stale ? stale : null);
        snapshot.setExtra(new LinkedHashMap<>(features));
        return snapshot;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }
}
