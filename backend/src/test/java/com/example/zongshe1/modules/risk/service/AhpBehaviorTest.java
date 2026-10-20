package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.service.ahp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AhpBehaviorTest {
    private final AhpMatrixBuilder builder = new AhpMatrixBuilder();
    private final AhpWeightCalculator calculator = new AhpWeightCalculator();
    private FeatureSnapshot snapshot(int overdue, String payment) {
        var snapshot = new FeatureSnapshot();
        snapshot.put("lpr1y", 3.0); snapshot.put("lpr5y", 3.45); snapshot.put("stale", false);
        snapshot.put("credit_score", 720); snapshot.put("credit_overdue_count", 0);
        snapshot.put("behaviorFacts", Map.of("quality", "AVAILABLE", "historicalOverduePeriods", overdue,
                "currentOverduePeriods", 0, "latestPayment", payment));
        return snapshot;
    }
    private AhpResult calculate(List<String> models, FeatureSnapshot snapshot) {
        var input = builder.build(models, snapshot);
        return calculator.calculate(input.modelOrder(), input.matrix(), input.source());
    }
    @ParameterizedTest @ValueSource(strings = {"WOE,EXTERNAL", "WOE,BEHAVIOR", "EXTERNAL,BEHAVIOR", "WOE,EXTERNAL,BEHAVIOR"})
    void allCombinationsBuildReciprocalMatricesWithOnlySelectedModels(String selection) {
        var models = List.of(selection.split(","));
        var snapshot = snapshot(1, "FULL");
        var result = calculate(models, snapshot);
        assertEquals(models.size(), result.matrix().size());
        assertEquals(new HashSet<>(models), result.weights().keySet());
        assertEquals(1.0, result.weights().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        for (int i = 0; i < models.size(); i++) {
            assertEquals(1.0, result.matrix().get(i).get(i));
            for (int j = 0; j < models.size(); j++)
                assertEquals(1.0, result.matrix().get(i).get(j) * result.matrix().get(j).get(i), 1e-12);
        }
        var reverse = new ArrayList<>(models); Collections.reverse(reverse);
        assertEquals(result, calculate(reverse, snapshot));
        Map<String, Object> scores = new LinkedHashMap<>();
        models.forEach(model -> scores.put(model, Map.of("score", 70, "maxScore", 100)));
        var fused = new AhpFusionService(builder, calculator).fuse(scores, snapshot);
        assertEquals(70, ((Map<?, ?>) fused.get("credit")).get("score"));
    }
    @ParameterizedTest @ValueSource(strings = {"WOE,BEHAVIOR", "EXTERNAL,BEHAVIOR", "WOE,EXTERNAL,BEHAVIOR"})
    void moreProvenOverdueAndNotFullPaymentIncreaseBehaviorAttention(String selection) {
        var models = List.of(selection.split(","));
        double previous = 0;
        for (int overdue = 0; overdue <= 3; overdue++) {
            var result = calculate(models, snapshot(overdue, "FULL"));
            assertFalse(result.fallbackUsed());
            double weight = result.weights().get("BEHAVIOR");
            assertTrue(weight > previous); previous = weight;
        }
        var full = calculate(models, snapshot(0, "FULL"));
        var notFull = calculate(models, snapshot(0, "NOT_FULL"));
        assertFalse(notFull.fallbackUsed());
        assertTrue(notFull.weights().get("BEHAVIOR") > full.weights().get("BEHAVIOR"));
    }
    @Test void woeBehaviorPairRespondsToFreshLprAndDiscountsStaleMacroEvidence() {
        var models = List.of("WOE", "BEHAVIOR");
        var snapshot = snapshot(0, "FULL");
        double low = calculate(models, snapshot).weights().get("BEHAVIOR");
        snapshot.put("lpr5y", 4.45);
        double high = calculate(models, snapshot).weights().get("BEHAVIOR");
        assertTrue(high > low);
        snapshot.put("stale", true);
        assertEquals(low, calculate(models, snapshot).weights().get("BEHAVIOR"));
    }
    @Test void noHistoryHasLessEvidenceWeightThanKnownHistory() {
        var snapshot = snapshot(0, "FULL");
        double known = calculate(List.of("WOE", "BEHAVIOR"), snapshot).weights().get("BEHAVIOR");
        snapshot.put("behaviorFacts", Map.of("quality", "NO_HISTORY", "historicalOverduePeriods", 0,
                "currentOverduePeriods", 0, "latestPayment", "NONE"));
        assertTrue(calculate(List.of("WOE", "BEHAVIOR"), snapshot).weights().get("BEHAVIOR") < known);
    }
    @Test void missingLprPreservesExistingExplicitEqualWeightFallbackForThreeModels() {
        var snapshot = snapshot(1, "FULL"); snapshot.put("lpr1y", null); snapshot.put("lpr5y", null);
        var result = calculate(List.of("WOE", "EXTERNAL", "BEHAVIOR"), snapshot);
        assertTrue(result.fallbackUsed()); assertEquals("MISSING_LPR", result.source().get("fallbackReason"));
        result.weights().values().forEach(weight -> assertEquals(1.0 / 3, weight));
    }
    @Test void fusionPreservesCrFailureDiagnosticsAndExistingFallback() {
        var matrix = List.of(List.of(1.0, 9.0, 1.0 / 9), List.of(1.0 / 9, 1.0, 9.0), List.of(9.0, 1.0 / 9, 1.0));
        var names = List.of("BEHAVIOR", "EXTERNAL", "WOE");
        var custom = mock(AhpMatrixBuilder.class);
        when(custom.build(anyList(), any())).thenReturn(new AhpMatrixBuilder.MatrixInput(names, matrix, Map.of()));
        Map<String, Object> scores = new LinkedHashMap<>();
        names.forEach(name -> scores.put(name, Map.of("score", 60, "maxScore", 100)));
        var output = new AhpFusionService(custom, calculator).fuse(scores, snapshot(0, "FULL"));
        var ahp = (Map<?, ?>) output.get("ahp");
        assertEquals(true, ahp.get("fallbackUsed")); assertEquals(false, ahp.get("consistent"));
        assertTrue(((Number) ahp.get("cr")).doubleValue() > .1); assertEquals(matrix, ahp.get("matrix"));
        ((Map<?, ?>) ahp.get("weights")).values().forEach(weight -> assertEquals(1.0 / 3, weight));
    }
    @ParameterizedTest @ValueSource(strings = {"PARTIAL", "ERROR", "UNKNOWN", "DISABLED"})
    void unscorableBehaviorCannotEnterMatrixEvenWithNumericReferenceScore(String quality) {
        var snapshot = snapshot(0, "FULL");
        snapshot.put("behaviorFacts", Map.of("quality", quality, "historicalOverduePeriods", 0,
                "currentOverduePeriods", 0, "latestPayment", "FULL"));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of("WOE", "BEHAVIOR"), snapshot));
    }
}
