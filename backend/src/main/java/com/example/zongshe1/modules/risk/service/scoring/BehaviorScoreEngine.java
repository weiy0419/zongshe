package com.example.zongshe1.modules.risk.service.scoring;

import com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure scoring: no repositories, user updates, admission decisions or live data access. */
@Component
public class BehaviorScoreEngine {
    public static final String VERSION = "behavior-v1";
    public static final int NEUTRAL_SCORE = 50;
    public static final int MAX_SCORE = 100;
    public static final int OVERDUE_PENALTY = 15;
    public static final int FULL_PAYMENT_BONUS = 10;
    public static final int NOT_FULL_PAYMENT_PENALTY = 10;

    public record Result(Integer score, int maxScore, String version,
                         BehaviorFeatureSnapshot.DataQuality quality, List<String> reasons) {
        public Result { reasons = List.copyOf(reasons); }
    }

    public Result evaluate(BehaviorFeatureSnapshot facts) {
        Objects.requireNonNull(facts, "Behavior facts required");
        List<String> reasons = new ArrayList<>(facts.evidence());
        if (facts.quality() == BehaviorFeatureSnapshot.DataQuality.ERROR) {
            reasons.add("QUERY_ERROR_NOT_SCORED");
            return new Result(null, MAX_SCORE, VERSION, facts.quality(), reasons);
        }
        if (facts.quality() == BehaviorFeatureSnapshot.DataQuality.NO_HISTORY) {
            reasons.add("NO_HISTORY_NEUTRAL");
            return new Result(NEUTRAL_SCORE, MAX_SCORE, VERSION, facts.quality(), reasons);
        }
        long score = NEUTRAL_SCORE;
        // Current overdue is a separate, proven lower bound, not a fabricated historical total.
        Integer count = facts.historicalOverduePeriods();
        if (count != null) reasons.add("HISTORICAL_OVERDUE_PERIODS=" + count);
        else {
            reasons.add("HISTORICAL_OVERDUE_UNKNOWN_NEUTRAL");
            count = facts.currentOverduePeriods();
            reasons.add(count == null ? "CURRENT_OVERDUE_UNKNOWN_NEUTRAL" : "CURRENT_OVERDUE_PERIODS=" + count);
        }
        if (count != null) score -= (long) count * OVERDUE_PENALTY;
        switch (facts.latestPayment()) {
            case FULL -> { score += FULL_PAYMENT_BONUS; reasons.add("LATEST_SINGLE_PAYMENT_FULL"); }
            case NOT_FULL -> { score -= NOT_FULL_PAYMENT_PENALTY; reasons.add("LATEST_SINGLE_PAYMENT_NOT_FULL"); }
            case UNKNOWN -> reasons.add("LATEST_PAYMENT_UNKNOWN_NEUTRAL");
            case NONE -> reasons.add("NO_PAYMENT_NEUTRAL");
        }
        return new Result((int) Math.max(0, Math.min(MAX_SCORE, score)), MAX_SCORE, VERSION, facts.quality(), reasons);
    }
}
