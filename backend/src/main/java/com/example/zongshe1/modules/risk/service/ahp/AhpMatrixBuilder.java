package com.example.zongshe1.modules.risk.service.ahp;

import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** 先求每个模型的信息重要度，再逐对映射 Saaty 标度；新增模型只需扩展重要度规则。 */
@Component
public class AhpMatrixBuilder {
    @Value("${risk.ahp.lpr-baseline:3.45}") private double lprBaseline = 3.45;
    @Value("${risk.ahp.lpr-step:0.5}") private double lprStep = .5;
    @Value("${risk.ahp.credit-risk-cutoff:600}") private int creditRiskCutoff = 600;
    @Value("${risk.ahp.stale-discount:1}") private int staleDiscount = 1;

    public record MatrixInput(List<String> modelOrder, List<List<Double>> matrix, Map<String, Object> source) {}

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!Double.isFinite(lprBaseline) || lprBaseline < 0 || !Double.isFinite(lprStep)
                || lprStep <= 0 || creditRiskCutoff < 0 || staleDiscount < 0 || staleDiscount > 8)
            throw new IllegalArgumentException("Invalid AHP matrix configuration");
    }

    public MatrixInput build(List<String> enabledModels, FeatureSnapshot snapshot) {
        validate();
        // 字典序固定行列顺序，不依赖请求或 Map 的迭代顺序。
        List<String> models = enabledModels.stream().distinct().sorted().toList();
        if (models.size() < 2) throw new IllegalArgumentException("AHP needs at least two models");
        Double one = number(snapshot.get("lpr1y")), five = number(snapshot.get("lpr5y"));
        Double credit = number(snapshot.get("credit_score")), overdue = number(snapshot.get("credit_overdue_count"));
        boolean missing = one == null && five == null;
        double lpr = Math.max(one == null ? 0 : one, five == null ? 0 : five);
        int external = 1 + (int) Math.min(6, Math.ceil(Math.max(0, lpr - lprBaseline) / lprStep));
        if (!Boolean.FALSE.equals(snapshot.get("stale"))) external = Math.max(1, external - staleDiscount);
        int woe = 1 + (credit == null ? 0 : 1 + (credit < creditRiskCutoff ? 2 : 0))
                + (overdue == null ? 0 : (int) Math.min(2, Math.floor(overdue)));
        Map<?, ?> behaviorFacts = snapshot.get("behaviorFacts") instanceof Map<?, ?> facts ? facts : Map.of();
        int behavior = 0;
        if (models.contains("BEHAVIOR")) {
            Object quality = behaviorFacts.get("quality");
            Double history = number(behaviorFacts.get("historicalOverduePeriods"));
            Double current = number(behaviorFacts.get("currentOverduePeriods"));
            Object payment = behaviorFacts.get("latestPayment");
            if (!("NO_HISTORY".equals(quality) || "AVAILABLE".equals(quality))
                    || history == null || current == null || history < current
                    || !("FULL".equals(payment) || "NOT_FULL".equals(payment) || "NONE".equals(payment)))
                throw new IllegalArgumentException("BEHAVIOR requires complete or explicit no-history evidence");
            // More proven adverse history increases attention to behavior, not its credit score.
            // Fresh macro pressure also increases attention to repayment capacity, including WOE+BEHAVIOR.
            int macroPressure = Boolean.FALSE.equals(snapshot.get("stale")) ? Math.min(2, external - 1) : 0;
            behavior = "NO_HISTORY".equals(quality) ? 1
                    : 2 + (int) Math.min(3, history) + ("NOT_FULL".equals(payment) ? 2 : 0) + macroPressure;
        }
        Map<String, Integer> importance = new LinkedHashMap<>();
        for (String model : models) {
            importance.put(model, switch (model) {
                case "WOE" -> woe;
                case "EXTERNAL" -> external;
                case "BEHAVIOR" -> behavior;
                default -> throw new IllegalArgumentException("No AHP importance rule for " + model);
            });
        }
        List<List<Double>> matrix = new ArrayList<>();
        for (String row : models) {
            List<Double> values = new ArrayList<>();
            for (String col : models) {
                int delta = missing ? 0 : importance.get(row) - importance.get(col);
                double scale = Math.min(9, 1 + Math.abs(delta));
                values.add(delta >= 0 ? scale : 1 / scale);
            }
            matrix.add(List.copyOf(values));
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("lpr1y", one); source.put("lpr5y", five);
        source.put("stale", snapshot.get("stale"));
        source.put("credit_score", credit); source.put("credit_overdue_count", overdue);
        source.put("parameters", Map.of("lprBaseline", lprBaseline, "lprStep", lprStep,
                "creditRiskCutoff", creditRiskCutoff, "staleDiscount", staleDiscount));
        source.put("importance", importance);
        source.put("formula", "EXTERNAL=1+min(6,ceil(max(0,max(LPR)-baseline)/step)), stale/unknown discounts reliability; "
                + "WOE=1+creditAvailable+2*lowCredit+min(2,overdue); pair=1+abs(importance difference), reciprocal if negative");
        source.put("fallbackReason", missing ? "MISSING_LPR" : "NONE");
        source.put("version", "snapshot-saaty-v1");
        if (models.contains("BEHAVIOR")) {
            source.put("behaviorFacts", new LinkedHashMap<>(behaviorFacts));
            source.put("behaviorFormula", "NO_HISTORY=1; AVAILABLE=2+min(3,historicalOverduePeriods)"
                    + "+2*latestNotFull+fresh*min(2,EXTERNAL_importance-1); no unproven history is used");
            source.put("version", "snapshot-saaty-behavior-v2");
        }
        return new MatrixInput(models, List.copyOf(matrix), source);
    }

    private static Double number(Object raw) {
        if (!(raw instanceof Number number)) return null;
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= 0 ? value : null;
    }
}
