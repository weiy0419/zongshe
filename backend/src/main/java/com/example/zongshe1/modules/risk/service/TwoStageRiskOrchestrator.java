package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.enums.RiskReasonCode;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureContextService;
import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.service.impl.BasicRiskRuleEvaluator;
import com.example.zongshe1.modules.risk.service.impl.ExternalDataRiskEvaluator;
import com.example.zongshe1.modules.risk.service.impl.ScoringCardRiskEvaluator;
import com.example.zongshe1.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 仅编排、算分，不读写申请状态或报告。现有非评分卡规则作为准入规则。
 * 第四周：硬规则准入通过后，按启用集合执行 WOE 申请卡授信。
 * 旧外部数据硬规则仍用于准入，不代表外部公示授信模型已实现。
 * 新服务尚未被 Controller / RiskService 调用，不改变旧评估链。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TwoStageRiskOrchestrator {

    private final List<RiskRuleEvaluator> evaluators;
    private final ScoringCardRiskEvaluator scoringCard;
    private final FeatureContextService featureContextService;
    private final CurrentRiskFeatures currentRiskFeatures;

    /** 无申请 ID / 报告 ID，可供去重层缓存计算结果，持久化由后续接入层负责。 */
    public record Result(String stage, String admission, String decision, String reasonSummary,
                         List<RiskReasonCode> reasonCodes, List<Map<String, Object>> ruleResults,
                         Map<String, Object> cardA, Map<String, Object> features,
                         LocalDateTime assessedAt) {
    }

    public Result assess(User user, LoanApplication application) {
        return assess(user, application, List.of("WOE"));
    }

    /** 未提供策略时默认 WOE；显式空集合仅执行准入，不自动补回授信模型。 */
    public Result assess(User user, LoanApplication application, List<String> enabledModels) {
        Objects.requireNonNull(user, "user 不能为空");
        Objects.requireNonNull(application, "application 不能为空");
        List<String> models = enabledModels == null ? List.of("WOE") : new ArrayList<>(enabledModels);
        if (models.stream().anyMatch(model -> !"WOE".equals(model))) {
            throw new IllegalArgumentException("第四周仅支持 WOE 授信模型，其他模型尚未实现");
        }
        models = models.stream().distinct().toList();
        boolean woeEnabled = models.contains("WOE") && scoringCard.isEnabled();
        FeatureSnapshot previous = currentRiskFeatures.get();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("enabledModels", models);
        String stage = "ADMISSION";
        Map<String, Object> cardA = Map.of("enabled", woeEnabled,
                "skipped", woeEnabled ? "ADMISSION_NOT_PASSED" : "DISABLED");
        try {
            FeatureSnapshot snapshot = featureContextService.build(user, application);
            features.putAll(snapshot.getFeatures());
            features.put("enabledModels", models);
            currentRiskFeatures.set(snapshot);
            List<RiskRuleEvaluator> admissionRules = evaluators.stream()
                    .filter(rule -> !(rule instanceof ScoringCardRiskEvaluator))
                    .filter(RiskRuleEvaluator::isEnabled)
                    .sorted(Comparator.comparingInt(RiskRuleEvaluator::getPriority)).toList();
            if (admissionRules.isEmpty()) {
                throw new IllegalStateException("没有启用的准入规则");
            }
            for (RiskRuleEvaluator rule : admissionRules) {
                Map<String, Object> evaluated = evaluate(rule, user, application, stage);
                results.add(evaluated);
                if (!Boolean.TRUE.equals(evaluated.get("passed"))) {
                    List<RiskReasonCode> codes = admissionReasonCodes(rule, evaluated, user, features);
                    evaluated.put("reasonCodes", codes);
                    return result(stage, "REJECTED", "REJECTED", reason(evaluated),
                            results, cardA, features, codes);
                }
            }
            stage = "CREDIT";
            if (!woeEnabled) {
                cardA = Map.of("enabled", false, "skipped", "DISABLED");
                return result(stage, "PASS", "MANUAL_REVIEW", "准入已通过，WOE 申请卡未启用，授信未执行，需人工复核",
                        results, cardA, features, List.of(RiskReasonCode.MANUAL_REVIEW));
            }
            Map<String, Object> scored = evaluate(scoringCard, user, application, stage);
            results.add(scored);
            if (!(scored.get("details") instanceof Map<?, ?> details)
                    || !(details.get("totalPoints") instanceof Number)
                    || !(details.get("maxPoints") instanceof Number)
                    || !(details.get("cardVersion") instanceof String)) {
                throw new IllegalStateException("申请卡结果缺少分数或版本");
            }
            cardA = new LinkedHashMap<>();
            cardA.put("enabled", true);
            cardA.put("score", details.get("totalPoints"));
            cardA.put("maxScore", details.get("maxPoints"));
            cardA.put("version", details.get("cardVersion"));
            cardA.put("breakdown", details.get("breakdown"));
            boolean passed = Boolean.TRUE.equals(scored.get("passed"));
            return result(stage, "PASS", passed ? "APPROVED" : "REJECTED", reason(scored),
                    results, cardA, features, passed ? List.of() : List.of(RiskReasonCode.CREDIT_LOW));
        } catch (RuntimeException exception) {
            log.warn("两阶段评估异常：applicationId={}, stage={}", application.getId(), stage, exception);
            cardA = Map.of("enabled", woeEnabled, "skipped",
                    !woeEnabled ? "DISABLED"
                            : "CREDIT".equals(stage) ? "EVALUATION_ERROR" : "ADMISSION_NOT_PASSED");
            return result(stage, "CREDIT".equals(stage) ? "PASS" : "MANUAL_REVIEW",
                    "MANUAL_REVIEW", "风控评估异常，需人工复核", results, cardA, features,
                    List.of(RiskReasonCode.MANUAL_REVIEW));
        } finally {
            if (previous == null) currentRiskFeatures.clear();
            else currentRiskFeatures.set(previous);
        }
    }

    private Map<String, Object> evaluate(RiskRuleEvaluator rule, User user,
                                        LoanApplication application, String stage) {
        Map<String, Object> result = new LinkedHashMap<>(rule.evaluateRisk(user, application));
        if (!(result.get("passed") instanceof Boolean)) {
            throw new IllegalStateException("规则缺少 passed：" + rule.getRuleName());
        }
        result.put("ruleName", rule.getRuleName());
        result.put("stage", stage);
        return result;
    }

    private static List<RiskReasonCode> admissionReasonCodes(RiskRuleEvaluator rule,
                                                            Map<String, Object> evaluated,
                                                            User user, Map<String, Object> features) {
        List<RiskReasonCode> codes = new ArrayList<>();
        if (evaluated.get("reasonCodes") instanceof List<?> values) {
            for (Object value : values) {
                RiskReasonCode code = reasonCode(value);
                if (code != null && !codes.contains(code)) codes.add(code);
            }
        }
        RiskReasonCode code = reasonCode(evaluated.get("reasonCode"));
        if (code != null && !codes.contains(code)) codes.add(code);
        if (!codes.isEmpty()) return List.copyOf(codes);

        // 兼容现有规则：正常用户在基础规则中被拒，只可能是信用分不足。
        // 状态异常时不猜测命中了哪条分支，也不复制规则阈值或匹配中文文案。
        if (rule instanceof BasicRiskRuleEvaluator && Integer.valueOf(1).equals(user.getUserStatus())
                && user.getCreditScore() != null) {
            return List.of(RiskReasonCode.CREDIT_LOW);
        }
        // 旧外部规则在关键源未失败时的拒绝分支为逾期过高。
        if (rule instanceof ExternalDataRiskEvaluator
                && Boolean.FALSE.equals(features.get("required_source_failed"))
                && features.get("credit_overdue_count") instanceof Number overdue
                && overdue.intValue() > 0) {
            return List.of(RiskReasonCode.OVERDUE_HIGH);
        }
        // 现有枚举未覆盖用户状态异常、源不可用等情况：保留拒绝结论和原始说明，标记需复核。
        return List.of(RiskReasonCode.MANUAL_REVIEW);
    }

    private static RiskReasonCode reasonCode(Object value) {
        if (value instanceof RiskReasonCode code) return code;
        if (value instanceof String text) {
            try {
                return RiskReasonCode.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                // 未知原因码交由兼容映射或人工复核兜底。
            }
        }
        return null;
    }

    private static String reason(Map<String, Object> evaluated) {
        Object value = evaluated.get("reason");
        return value == null ? "风控规则评估完成" : value.toString();
    }

    private static Result result(String stage, String admission, String decision, String reason,
                                 List<Map<String, Object>> rules, Map<String, Object> cardA,
                                 Map<String, Object> features, List<RiskReasonCode> codes) {
        return new Result(stage, admission, decision, reason, List.copyOf(codes), List.copyOf(rules),
                new LinkedHashMap<>(cardA), new LinkedHashMap<>(features), LocalDateTime.now());
    }
}
