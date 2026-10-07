package com.example.zongshe1.modules.risk.service.scoring;

import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 只消费本次快照；LPR 单位为百分数，3.5 表示 3.5%，分数越高越好。 */
@Component
public class ExternalPublicModel {
    // 演示策略：最多允许扣 40 分；独立于 WOE 分箱阈值，可由部署配置覆盖。
    public static final int DEFAULT_PASS_MIN_SCORE = 60;

    @org.springframework.beans.factory.annotation.Value("${risk.external.pass-min-score:" + DEFAULT_PASS_MIN_SCORE + "}")
    private int passMinScore = DEFAULT_PASS_MIN_SCORE;

    @jakarta.annotation.PostConstruct
    public void validateThreshold() {
        if (passMinScore < 0 || passMinScore > 100) {
            throw new IllegalArgumentException("risk.external.pass-min-score must be within 0..100");
        }
    }

    public Map<String, Object> evaluate(FeatureSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        validateThreshold();
        Map<String, Object> breakdown = new LinkedHashMap<>();
        int oneYear = lprPoints(snapshot.get("lpr1y"), "lpr1y", breakdown);
        int fiveYear = lprPoints(snapshot.get("lpr5y"), "lpr5y", breakdown);
        // 未知新鲜度与过期数据均扣 15 分。
        int penalty = Boolean.FALSE.equals(snapshot.get("stale")) ? 0 : 15;
        breakdown.put("freshness", Map.of("status", Boolean.TRUE.equals(snapshot.get("stale"))
                ? "STALE" : penalty == 0 ? "FRESH" : "UNKNOWN", "points", -penalty));
        int score = Math.max(0, Math.min(100, oneYear + fiveYear - penalty));
        boolean passed = score >= passMinScore;
        breakdown.put("decisionPolicy", Map.of("property", "risk.external.pass-min-score",
                "passMinScore", passMinScore, "comparison", "score >= passMinScore"));
        return Map.of("decision", passed ? "APPROVED" : "REJECTED", "modelName", "EXTERNAL", "score", score, "maxScore", 100,
                "version", "external-public-v1", "breakdown", breakdown);
    }

    private int lprPoints(Object raw, String field, Map<String, Object> breakdown) {
        BigDecimal rate = null;
        try {
            if (raw instanceof Number) rate = new BigDecimal(raw.toString());
        } catch (NumberFormatException ignored) {
            // 非有限数按无效输入处理。
        }
        boolean valid = rate != null && rate.signum() >= 0;
        // 每项最多 50 分；LPR 每增加 1 个百分点减少 5 分。
        int points = valid ? BigDecimal.valueOf(50).subtract(rate.multiply(BigDecimal.valueOf(5)))
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(50))
                .setScale(0, RoundingMode.HALF_UP).intValueExact() : 0;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("rawValue", valid ? rate : null);
        line.put("status", valid ? "AVAILABLE" : "MISSING_OR_INVALID");
        line.put("formula", "clamp(round(50 - 5 * LPR_percent), 0, 50)");
        line.put("points", points);
        breakdown.put(field, line);
        return points;
    }
}
