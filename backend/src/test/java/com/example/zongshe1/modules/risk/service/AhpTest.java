package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.service.ahp.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AhpTest {
    private final AhpMatrixBuilder builder = new AhpMatrixBuilder();
    private final AhpWeightCalculator calculator = new AhpWeightCalculator();
    private final AhpFusionService fusion = new AhpFusionService(builder, calculator);

    private FeatureSnapshot snapshot() {
        var snapshot = new FeatureSnapshot();
        snapshot.put("lpr1y", 3.0);
        snapshot.put("lpr5y", 3.45);
        snapshot.put("stale", false);
        snapshot.put("credit_score", 720);
        snapshot.put("credit_overdue_count", 0);
        return snapshot;
    }
    private AhpResult calculate(FeatureSnapshot snapshot) {
        var input = builder.build(List.of("WOE", "EXTERNAL"), snapshot);
        return calculator.calculate(input.modelOrder(), input.matrix(), input.source());
    }
    private void validWeights(AhpResult result) {
        assertEquals(1, result.weights().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        assertTrue(result.weights().values().stream().allMatch(w -> Double.isFinite(w) && w > 0));
    }
    @Test void twoByTwoIsReciprocalDeterministicAndConsistent() {
        var first = calculate(snapshot());
        assertEquals(first, calculate(snapshot()));
        assertEquals(List.of("EXTERNAL", "WOE"), first.modelOrder());
        assertEquals(2, first.lambdaMax());
        assertEquals(0, first.ci());
        assertEquals(0, first.cr());
        assertTrue(first.consistent());
        assertFalse(first.fallbackUsed());
        validWeights(first);
        assertEquals(1, first.matrix().get(0).get(0));
        assertEquals(1, first.matrix().get(0).get(1) * first.matrix().get(1).get(0), 1e-12);
        assertEquals(1.0 / 3, first.weights().get("EXTERNAL"), 1e-12);
    }
    @Test void higherLprIncreasesExternalWeightStaleDiscountsReliabilityAndCreditRiskIncreasesWoe() {
        var snapshot = snapshot();
        double baseline = calculate(snapshot).weights().get("EXTERNAL");
        snapshot.put("lpr5y", 5.0);
        double higher = calculate(snapshot).weights().get("EXTERNAL");
        assertTrue(higher > baseline);
        snapshot.put("stale", true);
        double stale = calculate(snapshot).weights().get("EXTERNAL");
        assertTrue(stale < higher);
        snapshot.put("credit_score", 500);
        snapshot.put("credit_overdue_count", 2);
        assertTrue(calculate(snapshot).weights().get("EXTERNAL") < stale);
        var before = calculate(snapshot);
        snapshot.put("dishonestHit", true);
        assertEquals(before, calculate(snapshot));
    }
    @Test void missingLprExplicitlyFallsBackButStaleHistoricalLprStillBuildsMatrix() {
        var missing = calculate(new FeatureSnapshot());
        assertTrue(missing.fallbackUsed());
        assertEquals("MISSING_LPR", missing.source().get("fallbackReason"));
        assertEquals(.5, missing.weights().get("EXTERNAL"));
        var old = snapshot(); old.put("stale", true);
        assertFalse(calculate(old).fallbackUsed());
    }
    @Test void consistentThreeByThreeReturnsExpectedWeights() {
        var result = calculator.calculate(List.of("A", "B", "C"), List.of(
                List.of(1.0, 2.0, 4.0), List.of(.5, 1.0, 2.0), List.of(.25, .5, 1.0)), Map.of());
        validWeights(result);
        assertEquals(4.0 / 7, result.weights().get("A"), 1e-12);
        assertEquals(3, result.lambdaMax(), 1e-12);
        assertEquals(0, result.cr(), 1e-12);
        assertFalse(result.fallbackUsed());
    }
    @Test void inconsistentThreeByThreeKeepsOriginalDiagnosticsAndFallsBackToEqualWeights() {
        var result = calculator.calculate(List.of("A", "B", "C"), List.of(
                List.of(1.0, 9.0, 1.0 / 9), List.of(1.0 / 9, 1.0, 9.0),
                List.of(9.0, 1.0 / 9, 1.0)), Map.of());
        validWeights(result);
        assertTrue(result.cr() > .1);
        assertTrue(result.lambdaMax() > 3);
        assertFalse(result.consistent());
        assertTrue(result.fallbackUsed());
        assertTrue(result.explanation().contains("CR_EXCEEDS"));
        result.weights().values().forEach(w -> assertEquals(1.0 / 3, w));
    }
    @Test void genericCalculatorSupportsOneAndFourDimensionsAndRejectsInvalidMatrices() {
        assertEquals(1, calculator.calculate(List.of("A"), List.of(List.of(1.0)), Map.of()).weights().get("A"));
        var rows = Collections.nCopies(4, List.of(1.0, 1.0, 1.0, 1.0));
        validWeights(calculator.calculate(List.of("A", "B", "C", "D"), rows, Map.of()));
        for (var matrix : List.of(List.of(List.of(1.0, 3.0), List.of(.5, 1.0)),
                List.of(List.of(1.0, Double.NaN), List.of(1.0, 1.0)),
                List.of(List.of(1.0, 0.0), List.of(1.0, 1.0)), List.of(List.of(1.0)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> calculator.calculate(List.of("A", "B"), matrix, Map.of()));
        }
    }
    private Map<String, Object> scores(int woe, int max, int external) {
        return Map.of("WOE", Map.of("score", woe, "maxScore", max),
                "EXTERNAL", Map.of("score", external, "maxScore", 100));
    }
    @Test void fusionNormalizesScalesAndFormsBothDecisions() {
        var result = fusion.fuse(scores(160, 200, 50), snapshot());
        assertEquals(80.0, ((Map<?, ?>) result.get("normalizedModelScores")).get("WOE"));
        var credit = (Map<?, ?>) result.get("credit");
        assertEquals(70, credit.get("score"));
        assertEquals("APPROVED", credit.get("decision"));
        assertEquals("REJECTED", ((Map<?, ?>) fusion.fuse(scores(20, 200, 10), snapshot()).get("credit")).get("decision"));
        assertThrows(IllegalArgumentException.class, () -> fusion.fuse(scores(80, 0, 50), snapshot()));
        assertThrows(IllegalArgumentException.class, () -> fusion.fuse(scores(201, 200, 50), snapshot()));
        assertThrows(IllegalArgumentException.class, () -> fusion.fuse(Map.of("WOE", Map.of("score", 80, "maxScore", 100)), snapshot()));
    }
    @Test void configurableFusionThresholdAndMatrixParametersAreApplied() {
        new ApplicationContextRunner().withUserConfiguration(AhpFusionService.class, AhpMatrixBuilder.class, AhpWeightCalculator.class)
                .withPropertyValues("risk.ahp.pass-min-score=71", "risk.ahp.lpr-baseline=6")
                .run(context -> {
                    var result = context.getBean(AhpFusionService.class).fuse(scores(160, 200, 50), snapshot());
                    assertEquals("REJECTED", ((Map<?, ?>) result.get("credit")).get("decision"));
                    var source = (Map<?, ?>) ((Map<?, ?>) result.get("ahp")).get("source");
                    assertEquals(6.0, ((Map<?, ?>) source.get("parameters")).get("lprBaseline"));
                });
    }
    @Test void requestOrderDoesNotChangeWeightsOrFusedScore() {
        var forward = new LinkedHashMap<String, Object>();
        forward.put("WOE", Map.of("score", 80, "maxScore", 100));
        forward.put("EXTERNAL", Map.of("score", 50, "maxScore", 100));
        var reverse = new LinkedHashMap<String, Object>();
        reverse.put("EXTERNAL", forward.get("EXTERNAL")); reverse.put("WOE", forward.get("WOE"));
        assertEquals(fusion.fuse(forward, snapshot()), fusion.fuse(reverse, snapshot()));
    }
}
