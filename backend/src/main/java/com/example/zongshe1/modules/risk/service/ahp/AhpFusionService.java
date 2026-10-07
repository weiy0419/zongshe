package com.example.zongshe1.modules.risk.service.ahp;

import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class AhpFusionService {
    private final AhpMatrixBuilder matrixBuilder;
    private final AhpWeightCalculator calculator;
    // 独立的融合后 0~100 授信线；默认保留至少 60 分资质，非 WOE 原始卡阈值。
    @Value("${risk.ahp.pass-min-score:60}") private int passMinScore = 60;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (passMinScore < 0 || passMinScore > 100) throw new IllegalArgumentException("Invalid AHP pass-min-score");
    }

    public Map<String, Object> fuse(Map<String, Object> modelResults, FeatureSnapshot snapshot) {
        validate();
        if (modelResults.size() < 2) throw new IllegalArgumentException("AHP needs at least two executed models");
        var input = matrixBuilder.build(new ArrayList<>(modelResults.keySet()), snapshot);
        var ahp = calculator.calculate(input.modelOrder(), input.matrix(), input.source());
        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, Double> normalized = new LinkedHashMap<>();
        double weightedScore = 0;
        for (String model : input.modelOrder()) {
            if (!(modelResults.get(model) instanceof Map<?, ?> result)
                    || !(result.get("score") instanceof Number score) || !(result.get("maxScore") instanceof Number max))
                throw new IllegalArgumentException("Missing score/maxScore for " + model);
            double value = score.doubleValue(), maximum = max.doubleValue();
            if (!Double.isFinite(value) || !Double.isFinite(maximum) || maximum <= 0 || value < 0 || value > maximum)
                throw new IllegalArgumentException("Invalid score scale for " + model);
            double scaled = value / maximum * 100;
            raw.put(model, Map.of("score", score, "maxScore", max));
            normalized.put(model, scaled);
            weightedScore += scaled * ahp.weights().get(model);
        }
        // DTO 分数为整数：四舍五入后决策，报告分数与阈值判断保持一致。
        int total = (int) Math.round(weightedScore);
        String decision = total >= passMinScore ? "APPROVED" : "REJECTED";
        return Map.of("fusionMode", "AHP", "modelScores", raw, "normalizedModelScores", normalized,
                "ahp", ahp.toMap(), "credit", Map.of("status", "COMPLETED", "score", total,
                        "maxScore", 100, "unroundedScore", weightedScore, "decision", decision,
                        "passMinScore", passMinScore, "thresholdProperty", "risk.ahp.pass-min-score",
                        "rounding", "HALF_UP"));
    }
}
