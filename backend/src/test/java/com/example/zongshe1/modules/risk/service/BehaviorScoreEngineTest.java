package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot;
import com.example.zongshe1.modules.risk.service.scoring.BehaviorScoreEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorScoreEngineTest {
    private final BehaviorScoreEngine engine = new BehaviorScoreEngine();
    private BehaviorFeatureSnapshot facts(DataQuality quality, Integer history, Integer current, LatestPayment payment) {
        return new BehaviorFeatureSnapshot("U-17", LocalDateTime.of(2026, 1, 1, 12, 0), quality,
                history, current, null, null, payment, List.of("TEST_EVIDENCE"));
    }
    @Test void newUserHasExplicitNeutralScore() {
        var result = engine.evaluate(facts(DataQuality.NO_HISTORY, 0, 0, LatestPayment.NONE));
        assertEquals(50, result.score());
        assertEquals(DataQuality.NO_HISTORY, result.quality());
        assertEquals(BehaviorScoreEngine.VERSION, result.version());
    }
    @Test void historicalOverdueIsNotDoubleCountedWithCurrentOverdue() {
        assertEquals(20, engine.evaluate(facts(DataQuality.AVAILABLE, 2, 1, LatestPayment.NONE)).score());
    }
    @Test void provenCurrentOverduePenalizesWithoutInventingHistory() {
        var result = engine.evaluate(facts(DataQuality.PARTIAL, null, 1, LatestPayment.UNKNOWN));
        assertEquals(35, result.score());
        assertTrue(result.reasons().contains("HISTORICAL_OVERDUE_UNKNOWN_NEUTRAL"));
    }
    @Test void fullSinglePaymentIsRewarded() {
        assertEquals(60, engine.evaluate(facts(DataQuality.AVAILABLE, 0, 0, LatestPayment.FULL)).score());
    }
    @Test void notFullSinglePaymentIsPenalized() {
        assertEquals(40, engine.evaluate(facts(DataQuality.PARTIAL, null, 0, LatestPayment.NOT_FULL)).score());
    }
    @Test void missingEvidenceIsNeutralButNotReliable() {
        var result = engine.evaluate(facts(DataQuality.PARTIAL, null, null, LatestPayment.UNKNOWN));
        assertEquals(50, result.score());
        assertEquals(DataQuality.PARTIAL, result.quality());
    }
    @Test void errorNeverProducesNeutralScore() {
        var result = engine.evaluate(facts(DataQuality.ERROR, null, null, LatestPayment.UNKNOWN));
        assertNull(result.score());
        assertEquals(DataQuality.ERROR, result.quality());
    }
    @Test void countBoundaryDoesNotOverflowAndScoreStaysBounded() {
        for (int count : new int[]{0, 1, 3, 4, Integer.MAX_VALUE}) {
            for (LatestPayment payment : LatestPayment.values()) {
                int score = engine.evaluate(facts(DataQuality.AVAILABLE, count, 0,
                        payment == LatestPayment.UNKNOWN ? LatestPayment.NONE : payment)).score();
                assertTrue(score >= 0 && score <= 100);
            }
        }
        assertEquals(0, engine.evaluate(facts(DataQuality.AVAILABLE, Integer.MAX_VALUE, 0, LatestPayment.FULL)).score());
        assertEquals(5, engine.evaluate(facts(DataQuality.AVAILABLE, 3, 0, LatestPayment.NONE)).score());
    }
    @Test void contradictoryFactsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> facts(DataQuality.AVAILABLE, -1, 0, LatestPayment.NONE));
        assertThrows(IllegalArgumentException.class, () -> facts(DataQuality.AVAILABLE, 0, 1, LatestPayment.NONE));
        assertThrows(IllegalArgumentException.class, () -> facts(DataQuality.NO_HISTORY, 0, 0, LatestPayment.FULL));
        assertThrows(IllegalArgumentException.class, () -> facts(DataQuality.AVAILABLE, null, 0, LatestPayment.NONE));
    }
    @Test void factsAndResultsRoundTripWithoutEntitiesOrLiveQueries() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var input = facts(DataQuality.PARTIAL, null, 0, LatestPayment.UNKNOWN);
        assertEquals(input, mapper.readValue(mapper.writeValueAsString(input), BehaviorFeatureSnapshot.class));
        var result = engine.evaluate(input);
        assertEquals(result, mapper.readValue(mapper.writeValueAsString(result), BehaviorScoreEngine.Result.class));
        assertThrows(UnsupportedOperationException.class, () -> result.reasons().add("changed"));
    }
}
