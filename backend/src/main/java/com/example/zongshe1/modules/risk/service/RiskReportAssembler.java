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
 * totalScore 为 WOE 申请卡资质分（越高越好），不是旧接口的 overallRiskScore。
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

        // 兼容现有 Result.cardA()；当前结果只提供 WOE 启用标记，不推测请求策略。
        var enabledModels = new ArrayList<String>();
        if (Boolean.TRUE.equals(cardA.get("enabled"))) {
            enabledModels.add("WOE");
        }
        Map<String, String> skipped = new LinkedHashMap<>();
        if (cardA.get("skipped") instanceof String reason) {
            skipped.put("scorecard", reason);
        } else if (report.getTotalScore() == null) {
            skipped.put("scorecard", "NOT_EXECUTED");
        }
        if (result.ruleResults().stream().noneMatch(rule -> "ADMISSION".equals(rule.get("stage")))) {
            skipped.put("rules", "NOT_EXECUTED");
        }
        skipped.put("external", "NOT_IMPLEMENTED");
        skipped.put("behavior", "NOT_IMPLEMENTED");

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("stage", result.stage());
        breakdown.put("admission", result.admission());
        breakdown.put("scorecard", skipped.containsKey("scorecard") ? null : report.getTotalScore());
        breakdown.put("scorecardVersion", report.getCardVersion());
        breakdown.put("scorecardDetails", cardA);
        breakdown.put("rules", skipped.containsKey("rules") ? null : result.admission());
        // 公示原始特征保留在 featureSnapshot，不能冒充外部模型分数。
        breakdown.put("external", null);
        breakdown.put("behavior", null);
        breakdown.put("enabledModels", enabledModels);
        breakdown.put("skipped", skipped);
        breakdown.put("suggestLimit", null);
        breakdown.put("ruleResults", new ArrayList<>(result.ruleResults()));
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
