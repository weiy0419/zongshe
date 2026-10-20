package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.enums.RiskReasonCode;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureContextService;
import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.feature.BehaviorFeatureQueryService;
import com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot;
import com.example.zongshe1.modules.risk.service.scoring.BehaviorScoreEngine;
import org.springframework.beans.factory.annotation.Value;
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

/** 硬规则准入通过后按请求执行模型，分别保留模型结果。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TwoStageRiskOrchestrator {

    private final List<RiskRuleEvaluator> evaluators;
    private final ScoringCardRiskEvaluator scoringCard;
    private final FeatureContextService featureContextService;
    private final CurrentRiskFeatures currentRiskFeatures;
    private final com.example.zongshe1.modules.risk.service.scoring.ExternalPublicModel externalPublicModel;

    private final com.example.zongshe1.modules.risk.service.ahp.AhpFusionService ahpFusion;
    private final BehaviorFeatureQueryService behaviorFeatures;
    private final BehaviorScoreEngine behaviorEngine;

    // No generic credit threshold exists. Reuse the existing 0..100 application-card convention.
    @Value("${risk.scoring-card.pass-min-points:40}")
    private int behaviorPassMinPoints = 40;

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
        return assess(user, application, enabledModels, null);
    }

    /** Re-run admission on cache hits; only reuse credit if its inputs are still equivalent. */
    public Result revalidate(User user, LoanApplication application, List<String> enabledModels, Result cached) {
        return assess(user, application, enabledModels, Objects.requireNonNull(cached));
    }

    private Result assess(User user, LoanApplication application, List<String> enabledModels, Result cached) {
        Objects.requireNonNull(user, "user 不能为空");
        Objects.requireNonNull(application, "application 不能为空");
        List<String> models = EnabledRiskModels.normalize(enabledModels);
        boolean woeEnabled = models.contains("WOE") && scoringCard.isEnabled();
        FeatureSnapshot previous = currentRiskFeatures.get();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("enabledModels", models);
        Map<String, Object> modelResults = new LinkedHashMap<>();
        List<String> executedModels = new ArrayList<>();
        features.put("modelResults", modelResults);
        features.put("executedModels", executedModels);
        features.put("scorableModels", List.of());
        features.put("fusedModels", List.of());
        features.put("ahpExecuted", false);
        features.put("behaviorDetails", behaviorState(models.contains("BEHAVIOR"), "ADMISSION_NOT_PASSED"));
        String stage = "ADMISSION";
        Map<String, Object> cardA = Map.of("enabled", woeEnabled,
                "skipped", woeEnabled ? "ADMISSION_NOT_PASSED" : "DISABLED");
        try {
            FeatureSnapshot snapshot = featureContextService.build(user, application);
            features.putAll(snapshot.getFeatures());
            features.put("enabledModels", models);
            features.put("modelResults", modelResults);
            features.put("executedModels", executedModels);
            currentRiskFeatures.set(snapshot);
            Map<String, Object> dishonest = BasicRiskRuleEvaluator.evaluateDishonest(snapshot);
            if (!Boolean.TRUE.equals(dishonest.get("passed"))) {
                dishonest.put("stage", stage);
                results.add(dishonest);
                return result(stage, "REJECTED", "REJECTED", reason(dishonest),
                        results, cardA, features, List.of(RiskReasonCode.DISHONEST_PUBLIC));
            }
            List<RiskRuleEvaluator> admissionRules = evaluators.stream()
                    .filter(rule -> !(rule instanceof ScoringCardRiskEvaluator))
                    .filter(rule -> rule instanceof BasicRiskRuleEvaluator
                            || rule instanceof ExternalDataRiskEvaluator || rule.isEnabled())
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
            if (cached != null && reusable(cached, snapshot, models, woeEnabled)) return cached;
            String woeReason = null;
            if (models.contains("EXTERNAL")) {
                executedModels.add("EXTERNAL");
                modelResults.put("EXTERNAL", externalPublicModel.evaluate(snapshot));
            }
            if (woeEnabled) {
                executedModels.add("WOE");
                Map<String, Object> scored = evaluate(scoringCard, user, application, stage);
                results.add(scored);
                woeReason = reason(scored);
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
                modelResults.put("WOE", Map.of("modelName", "WOE", "score", details.get("totalPoints"),
                        "maxScore", details.get("maxPoints"), "version", details.get("cardVersion"),
                        "breakdown", details.get("breakdown"),
                        "decision", Boolean.TRUE.equals(scored.get("passed")) ? "APPROVED" : "REJECTED"));
            }
            if (models.contains("BEHAVIOR")) {
                executedModels.add("BEHAVIOR");
                Map<String, Object> behavior = evaluateBehavior(user, snapshot, features);
                features.put("behaviorDetails", behavior);
                modelResults.put("BEHAVIOR", behavior);
            }
            Map<String, Object> scorable = new LinkedHashMap<>(modelResults);
            boolean behaviorBlocked = models.contains("BEHAVIOR")
                    && !"COMPLETED".equals(((Map<?, ?>) modelResults.get("BEHAVIOR")).get("status"));
            if (behaviorBlocked) scorable.remove("BEHAVIOR");
            features.put("scorableModels", List.copyOf(scorable.keySet()));
            if (behaviorBlocked) {
                features.put("fusionMode", "NOT_EXECUTED");
                features.put("credit", Map.of("status", "SKIPPED", "skipped", "BEHAVIOR_REQUIRES_REVIEW"));
                return result(stage, "PASS", "MANUAL_REVIEW", "行为证据不足或查询失败，需人工复核",
                        results, cardA, features, List.of(RiskReasonCode.MANUAL_REVIEW));
            }
            if (scorable.isEmpty()) {
                features.put("fusionMode", "SKIPPED");
                features.put("credit", Map.of("status", "SKIPPED", "skipped",
                        models.isEmpty() ? "NO_ENABLED_MODELS" : "MODEL_DISABLED"));
                return result(stage, "PASS", "MANUAL_REVIEW", "准入已通过，未执行授信模型，需人工复核",
                        results, cardA, features, List.of(RiskReasonCode.MANUAL_REVIEW));
            }
            if (scorable.size() >= 2) {
                features.putAll(ahpFusion.fuse(scorable, snapshot));
                features.put("fusedModels", List.copyOf(scorable.keySet()));
                features.put("ahpExecuted", true);
            } else {
                String name = scorable.keySet().iterator().next();
                Map<?, ?> single = (Map<?, ?>) scorable.get(name);
                features.put("fusionMode", "SINGLE");
                features.put("credit", Map.of("status", "COMPLETED", "modelName", name,
                        "score", single.get("score"), "maxScore", single.get("maxScore"),
                        "decision", single.get("decision")));
            }
            Map<?, ?> credit = (Map<?, ?>) features.get("credit");
            String decision = (String) credit.get("decision");
            String summary = "WOE".equals(credit.get("modelName")) ? woeReason
                    : "EXTERNAL".equals(credit.get("modelName")) ? "EXTERNAL 单模型得分 " + credit.get("score")
                            + "/100，按模型明细中的配置阈值决策"
                    : "AHP".equals(features.get("fusionMode")) ? "AHP 动态融合得分 " + credit.get("score") + "/100"
                    : "BEHAVIOR 单模型得分 " + credit.get("score") + "/100，按现有申请卡阈值决策";
            return result(stage, "PASS", decision, summary,
                    results, cardA, features, "APPROVED".equals(decision)
                            ? List.of() : List.of(RiskReasonCode.CREDIT_LOW));
        } catch (RuntimeException exception) {
            log.warn("两阶段评估异常：applicationId={}, stage={}", application.getId(), stage, exception);
            if (models.contains("BEHAVIOR") && !executedModels.contains("BEHAVIOR"))
                features.put("behaviorDetails", behaviorState(true,
                        "CREDIT".equals(stage) ? "PRECEDING_MODEL_ERROR" : "ADMISSION_ERROR"));
            features.put("fusionMode", "NOT_EXECUTED");
            features.put("credit", Map.of("status", "SKIPPED", "skipped", "EVALUATION_ERROR"));
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

    private static Map<String, Object> behaviorState(boolean enabled, String status) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("modelName", "BEHAVIOR");
        state.put("enabled", enabled);
        state.put("executed", false);
        state.put("status", enabled ? status : "DISABLED");
        state.put("quality", "UNKNOWN");
        state.put("score", null);
        state.put("version", null);
        state.put("reasons", List.of(enabled ? status : "DISABLED"));
        return state;
    }

    private Map<String, Object> evaluateBehavior(User user, FeatureSnapshot snapshot, Map<String, Object> features) {
        Map<String, Object> state = behaviorState(true, "ERROR");
        state.put("executed", true);
        state.put("maxScore", BehaviorScoreEngine.MAX_SCORE);
        state.put("version", BehaviorScoreEngine.VERSION);
        try {
            BehaviorFeatureSnapshot facts = behaviorFeatures.query(user.getUserId());
            if (facts == null) {
                state.put("status", "UNKNOWN");
                state.put("reasons", List.of("MISSING_BEHAVIOR_FACTS"));
                return state;
            }
            // Store primitives/maps for stable JSON and cache round trips, not live entities.
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("businessUserId", facts.businessUserId());
            evidence.put("observedAt", facts.observedAt().toString());
            evidence.put("quality", facts.quality().name());
            evidence.put("historicalOverduePeriods", facts.historicalOverduePeriods());
            evidence.put("currentOverduePeriods", facts.currentOverduePeriods());
            evidence.put("latestRecordId", facts.latestRecordId());
            evidence.put("latestPlanId", facts.latestPlanId());
            evidence.put("latestPayment", facts.latestPayment().name());
            evidence.put("evidence", facts.evidence());
            snapshot.put("behaviorFacts", evidence);
            features.put("behaviorFacts", evidence);
            if (!Objects.equals(user.getUserId(), facts.businessUserId()))
                throw new IllegalStateException("Behavior evidence belongs to another user");
            BehaviorScoreEngine.Result score = behaviorEngine.evaluate(facts);
            state.put("quality", facts.quality().name());
            state.put("version", score.version());
            state.put("reasons", score.reasons());
            boolean eligible = (facts.quality() == BehaviorFeatureSnapshot.DataQuality.NO_HISTORY
                    || facts.quality() == BehaviorFeatureSnapshot.DataQuality.AVAILABLE)
                    && facts.latestPayment() != BehaviorFeatureSnapshot.LatestPayment.UNKNOWN
                    && score.score() != null;
            state.put("status", eligible ? "COMPLETED" : facts.quality().name());
            state.put("score", eligible ? score.score() : null);
            state.put("referenceScore", score.score());
            if (eligible) {
                if (behaviorPassMinPoints < 0 || behaviorPassMinPoints > 100)
                    throw new IllegalStateException("Invalid application-card threshold");
                state.put("decision", score.score() >= behaviorPassMinPoints ? "APPROVED" : "REJECTED");
                state.put("decisionPolicy", Map.of("property", "risk.scoring-card.pass-min-points",
                        "passMinScore", behaviorPassMinPoints, "source", "EXISTING_APPLICATION_CARD_CONVENTION"));
            }
        } catch (RuntimeException exception) {
            log.warn("Behavior evaluation failed: userId={}", user.getUserId(), exception);
            state.put("status", "ERROR");
            state.put("quality", "ERROR");
            state.put("score", null);
            state.put("reasons", List.of("BEHAVIOR_EVALUATION_ERROR"));
        }
        return state;
    }

    private static boolean reusable(Result cached, FeatureSnapshot snapshot, List<String> models, boolean woeEnabled) {
        // Behavior evidence is mutable: always query/score it again after admission on a cache hit.
        if (models.contains("BEHAVIOR") || !"PASS".equals(cached.admission())
                || !Objects.equals(models, cached.features().get("enabledModels"))
                || !Objects.equals(woeEnabled, cached.cardA().get("enabled"))) return false;
        for (var entry : snapshot.getFeatures().entrySet()) {
            Object old = cached.features().get(entry.getKey());
            if (entry.getKey().equals("loan_amount")) {
                if (!(old instanceof Number) || !(entry.getValue() instanceof Number)
                        || amountBand((Number) old) != amountBand((Number) entry.getValue())) return false;
            } else if (old instanceof Number a && entry.getValue() instanceof Number b) {
                if (new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) != 0) return false;
            } else if (!Objects.equals(old, entry.getValue())) return false;
        }
        // Detect removed input keys too. Metadata keys are not model inputs.
        for (String key : List.of("credit_score", "user_status", "loan_term", "dishonestHit", "lpr1y", "lpr5y",
                "stale", "required_source_failed", "credit_overdue_count", "telecom_online_months")) {
            if (cached.features().get(key) != null && !snapshot.getFeatures().containsKey(key)) return false;
        }
        return true;
    }

    private static int amountBand(Number amount) {
        java.math.BigDecimal value = new java.math.BigDecimal(amount.toString());
        return value.compareTo(new java.math.BigDecimal("50000")) <= 0 ? 0
                : value.compareTo(new java.math.BigDecimal("200000")) <= 0 ? 1 : 2;
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
