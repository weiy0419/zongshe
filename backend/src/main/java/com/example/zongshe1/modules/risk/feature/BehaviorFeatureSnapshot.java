package com.example.zongshe1.modules.risk.feature;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Immutable evidence only; null counts mean unknown, never zero. No JPA entities are retained. */
public record BehaviorFeatureSnapshot(String businessUserId, LocalDateTime observedAt,
        DataQuality quality, Integer historicalOverduePeriods, Integer currentOverduePeriods,
        Long latestRecordId, Long latestPlanId, LatestPayment latestPayment, List<String> evidence) {
    public enum DataQuality { NO_HISTORY, PARTIAL, AVAILABLE, ERROR }
    /** FULL means the single payment demonstrably covered the entire period, not cumulative payments. */
    public enum LatestPayment { FULL, NOT_FULL, UNKNOWN, NONE }

    public BehaviorFeatureSnapshot {
        if (businessUserId == null || businessUserId.isBlank()) throw new IllegalArgumentException("Business user ID required");
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(quality);
        Objects.requireNonNull(latestPayment);
        evidence = List.copyOf(evidence);
        if (historicalOverduePeriods != null && historicalOverduePeriods < 0
                || currentOverduePeriods != null && currentOverduePeriods < 0)
            throw new IllegalArgumentException("Negative overdue count");
        if (historicalOverduePeriods != null && currentOverduePeriods != null
                && historicalOverduePeriods < currentOverduePeriods)
            throw new IllegalArgumentException("Historical count cannot be below current count");
        if (quality == DataQuality.NO_HISTORY && (!Integer.valueOf(0).equals(historicalOverduePeriods)
                || !Integer.valueOf(0).equals(currentOverduePeriods) || latestPayment != LatestPayment.NONE
                || latestRecordId != null || latestPlanId != null))
            throw new IllegalArgumentException("Contradictory no-history evidence");
        if (quality == DataQuality.AVAILABLE && (historicalOverduePeriods == null
                || currentOverduePeriods == null || latestPayment == LatestPayment.UNKNOWN))
            throw new IllegalArgumentException("Available evidence must be complete");
    }
}
