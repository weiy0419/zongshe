package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.risk.feature.*;
import com.example.zongshe1.modules.risk.service.ahp.*;
import com.example.zongshe1.modules.risk.service.impl.*;
import com.example.zongshe1.modules.risk.service.scoring.*;
import com.example.zongshe1.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskWeek7Test {
    private final FeatureContextService features = mock(FeatureContextService.class);
    private final BehaviorFeatureQueryService history = mock(BehaviorFeatureQueryService.class);
    private final BehaviorScoreEngine behavior = spy(new BehaviorScoreEngine());
    private final ScoringCardRiskEvaluator woe = mock(ScoringCardRiskEvaluator.class);
    private final ExternalPublicModel external = spy(new ExternalPublicModel());
    private final AhpFusionService ahp = spy(new AhpFusionService(new AhpMatrixBuilder(), new AhpWeightCalculator()));
    private final CurrentRiskFeatures current = new CurrentRiskFeatures();
    private final BasicRiskRuleEvaluator admission = spy(new BasicRiskRuleEvaluator());
    private final User user = new User();
    private final LoanApplication application = new LoanApplication();
    private TwoStageRiskOrchestrator orchestrator;
    private FeatureSnapshot snapshot;

    @BeforeEach void setup() {
        user.setId(2L); user.setUserId("BUSINESS-2"); user.setCreditScore(720); user.setUserStatus(1);
        application.setId(101L); application.setUser(user); application.setLoanAmount(new BigDecimal("10000"));
        ReflectionTestUtils.setField(admission, "minCreditScoreForLoan", 450);
        when(woe.isEnabled()).thenReturn(true);
        when(woe.evaluateRisk(user, application)).thenReturn(Map.of("passed", true,
                "details", Map.of("totalPoints", 80, "maxPoints", 100, "cardVersion", "woe-test", "breakdown", List.of())));
        snapshot = new FeatureSnapshot();
        snapshot.put("credit_score", 720); snapshot.put("user_status", 1);
        snapshot.put("loan_amount", application.getLoanAmount());
        snapshot.put("lpr1y", 3.0); snapshot.put("lpr5y", 3.45); snapshot.put("stale", false);
        when(features.build(user, application)).thenAnswer(i -> {
            FeatureSnapshot copy = new FeatureSnapshot(); copy.putAll(snapshot.getFeatures()); return copy;
        });
        when(history.query("BUSINESS-2")).thenReturn(facts(DataQuality.NO_HISTORY, 0, LatestPayment.NONE));
        orchestrator = new TwoStageRiskOrchestrator(List.of(admission, woe), woe, features, current,
                external, ahp, history, behavior);
    }
    @AfterEach void cleanup() { assertNull(current.get()); }
    private BehaviorFeatureSnapshot facts(DataQuality quality, Integer historical, LatestPayment payment) {
        return new BehaviorFeatureSnapshot("BUSINESS-2", LocalDateTime.of(2026, 1, 1, 0, 0),
                quality, historical, quality == DataQuality.ERROR ? null : 0, null, null, payment, List.of("TEST_EVIDENCE"));
    }
    private List<String> models(String selection) { return selection.isEmpty() ? List.of() : List.of(selection.split(",")); }

    @ParameterizedTest
    @ValueSource(strings = {"", "WOE", "EXTERNAL", "BEHAVIOR", "WOE,EXTERNAL", "WOE,BEHAVIOR", "EXTERNAL,BEHAVIOR", "WOE,EXTERNAL,BEHAVIOR"})
    void allEightSelectionsHaveCorrectModelCountAndNoDisabledZeroScores(String selection) throws Exception {
        List<String> enabled = models(selection);
        var result = orchestrator.assess(user, application, enabled);
        var report = new RiskReportAssembler().assemble(result, 1L, 101L, 2L);
        assertEquals("PASS", result.admission());
        assertEquals(new HashSet<>(enabled), new HashSet<>((List<?>) result.features().get("scorableModels")));
        assertEquals(enabled.size(), ((Map<?, ?>) result.features().get("modelResults")).size());
        if (enabled.isEmpty()) {
            assertEquals("MANUAL_REVIEW", result.decision()); assertNull(report.getTotalScore());
            assertEquals("SKIPPED", result.features().get("fusionMode"));
        } else if (enabled.size() == 1) {
            assertEquals("SINGLE", result.features().get("fusionMode"));
            assertEquals(enabled.contains("WOE") ? 80 : enabled.contains("BEHAVIOR") ? 50 : 68, report.getTotalScore());
            assertEquals("APPROVED", result.decision());
        } else {
            assertEquals("AHP", result.features().get("fusionMode"));
            Map<?, ?> details = (Map<?, ?>) result.features().get("ahp");
            assertEquals(enabled.size(), ((List<?>) details.get("matrix")).size());
            Map<?, ?> weights = (Map<?, ?>) details.get("weights");
            assertEquals(new HashSet<>(enabled), weights.keySet());
            assertEquals(1, weights.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).sum(), 1e-12);
            verify(ahp).fuse(anyMap(), any());
        }
        assertEquals(enabled.size() >= 2, result.features().get("ahpExecuted"));
        if (enabled.size() < 2) verifyNoInteractions(ahp);
        if (enabled.contains("BEHAVIOR")) {
            verify(history).query("BUSINESS-2"); verify(behavior).evaluate(any());
            var state = (Map<?, ?>) report.getBreakdown().get("behaviorDetails");
            assertEquals("NO_HISTORY", state.get("quality")); assertEquals(50, report.getBreakdown().get("behavior"));
            assertTrue(report.getFeatureSnapshot().getExtra().containsKey("behaviorFacts"));
        } else {
            verifyNoInteractions(history, behavior);
            assertNull(report.getBreakdown().get("behavior"));
            assertEquals("DISABLED", ((Map<?, ?>) report.getBreakdown().get("skipped")).get("behavior"));
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(report));
        assertEquals(json.at("/breakdown/behaviorDetails"), json.at("/featureSnapshot/extra/behaviorDetails"));
        if (enabled.size() >= 2) assertEquals(json.at("/breakdown/ahp"), json.at("/featureSnapshot/extra/ahp"));
    }

    @ParameterizedTest @ValueSource(strings = {"BEHAVIOR", "WOE,BEHAVIOR", "EXTERNAL,BEHAVIOR", "WOE,EXTERNAL,BEHAVIOR"})
    void partialEvidenceCannotDisappearAndAllowApproval(String selection) {
        when(history.query(anyString())).thenReturn(facts(DataQuality.PARTIAL, null, LatestPayment.FULL));
        var result = orchestrator.assess(user, application, models(selection));
        var report = new RiskReportAssembler().assemble(result, 1L, 101L, 2L);
        assertEquals("MANUAL_REVIEW", result.decision()); assertNull(report.getTotalScore());
        Map<?, ?> state = (Map<?, ?>) report.getBreakdown().get("behaviorDetails");
        assertEquals("PARTIAL", state.get("quality")); assertEquals(60, state.get("referenceScore"));
        assertNull(state.get("score")); assertEquals(true, state.get("executed"));
        assertFalse(((List<?>) result.features().get("scorableModels")).contains("BEHAVIOR"));
        assertEquals(List.of(), result.features().get("fusedModels")); verifyNoInteractions(ahp);
    }
    @ParameterizedTest @ValueSource(strings = {"ERROR", "THROWN", "NULL", "UNKNOWN"})
    void missingAndFailedBehaviorCannotBecomeZeroOrNormalNeutral(String mode) {
        switch (mode) {
            case "ERROR" -> when(history.query(anyString())).thenReturn(facts(DataQuality.ERROR, null, LatestPayment.UNKNOWN));
            case "THROWN" -> when(history.query(anyString())).thenThrow(new IllegalStateException("unavailable"));
            case "NULL" -> when(history.query(anyString())).thenReturn(null);
            case "UNKNOWN" -> when(history.query(anyString())).thenReturn(facts(DataQuality.PARTIAL, null, LatestPayment.UNKNOWN));
        }
        var result = orchestrator.assess(user, application, List.of("WOE", "EXTERNAL", "BEHAVIOR"));
        assertEquals("MANUAL_REVIEW", result.decision());
        var state = (Map<?, ?>) result.features().get("behaviorDetails");
        assertNull(state.get("score")); assertNotEquals("COMPLETED", state.get("status"));
        assertFalse(((List<?>) result.features().get("scorableModels")).contains("BEHAVIOR")); verifyNoInteractions(ahp);
    }
    @ParameterizedTest @ValueSource(strings = {"", "BEHAVIOR", "WOE,EXTERNAL,BEHAVIOR"})
    void dishonestHardRejectionRunsBeforeModels(String selection) {
        snapshot.put("dishonestHit", true);
        var result = orchestrator.assess(user, application, models(selection));
        assertEquals("REJECTED", result.decision()); assertEquals("ADMISSION", result.stage());
        verifyNoInteractions(history, behavior, external, ahp); verify(woe, never()).evaluateRisk(any(), any());
    }
    @Test void creditAdmissionRejectionCannotBeOverriddenByBehavior() {
        user.setCreditScore(300);
        assertEquals("REJECTED", orchestrator.assess(user, application, List.of("BEHAVIOR")).decision());
        verifyNoInteractions(history, behavior);
    }
    @Test void externalHardRuleStillRunsWithEmptyModelsAndItsLegacySwitchOff() {
        var externalRule = new ExternalDataRiskEvaluator(current);
        ReflectionTestUtils.setField(externalRule, "enabled", false);
        ReflectionTestUtils.setField(externalRule, "maxOverdueCount", 2);
        var withExternalRule = new TwoStageRiskOrchestrator(List.of(admission, externalRule, woe),
                woe, features, current, external, ahp, history, behavior);
        snapshot.put("credit_overdue_count", 3);
        assertEquals("REJECTED", withExternalRule.assess(user, application, List.of()).decision());
        verifyNoInteractions(history, behavior, external, ahp);
    }
    @Test void behaviorSingleUsesExistingCardThresholdWithInclusiveBoundary() {
        when(history.query(anyString())).thenReturn(facts(DataQuality.AVAILABLE, 0, LatestPayment.NOT_FULL));
        assertEquals("APPROVED", orchestrator.assess(user, application, List.of("BEHAVIOR")).decision()); // 40
        ReflectionTestUtils.setField(orchestrator, "behaviorPassMinPoints", 41);
        var result = orchestrator.assess(user, application, List.of("BEHAVIOR"));
        assertEquals("REJECTED", result.decision());
        assertEquals(41, ((Map<?, ?>) ((Map<?, ?>) result.features().get("behaviorDetails")).get("decisionPolicy")).get("passMinScore"));
        verifyNoInteractions(ahp);
    }
    @Test void configuredOffWoeDoesNotForceExternalBehaviorIntoSingleMode() {
        when(woe.isEnabled()).thenReturn(false);
        var result = orchestrator.assess(user, application, List.of("WOE", "EXTERNAL", "BEHAVIOR"));
        assertEquals("AHP", result.features().get("fusionMode"));
        assertEquals(List.of("EXTERNAL", "BEHAVIOR"), result.features().get("scorableModels"));
        verify(woe, never()).evaluateRisk(any(), any());
    }
    @Test void enabledModelFailureNeverFallsBackToOtherSuccessfulModels() {
        when(woe.evaluateRisk(any(), any())).thenThrow(new IllegalStateException("card failure"));
        var result = orchestrator.assess(user, application, List.of("WOE", "EXTERNAL", "BEHAVIOR"));
        assertEquals("MANUAL_REVIEW", result.decision()); verifyNoInteractions(ahp);
        assertEquals("PRECEDING_MODEL_ERROR", ((Map<?, ?>) result.features().get("behaviorDetails")).get("status"));
    }
    @Test void cacheHitRerunsAdmissionAndDoesNotReuseAfterDishonestHit() {
        var cached = orchestrator.assess(user, application, List.of("WOE"));
        assertSame(cached, orchestrator.revalidate(user, application, List.of("WOE"), cached));
        verify(admission, times(2)).evaluateRisk(user, application); verify(woe).evaluateRisk(user, application);
        snapshot.put("dishonestHit", true);
        assertEquals("REJECTED", orchestrator.revalidate(user, application, List.of("WOE"), cached).decision());
        verifyNoInteractions(history);
    }
    @Test void cachedBehaviorAlwaysReadsCurrentEvidenceAndPartialBlocksApproval() {
        var cached = orchestrator.assess(user, application, List.of("BEHAVIOR"));
        when(history.query(anyString())).thenReturn(facts(DataQuality.PARTIAL, null, LatestPayment.UNKNOWN));
        assertEquals("MANUAL_REVIEW", orchestrator.revalidate(user, application, List.of("BEHAVIOR"), cached).decision());
        verify(history, times(2)).query("BUSINESS-2");
    }
    @Test void changedPublicSnapshotRecalculatesCachedAhp() {
        var cached = orchestrator.assess(user, application, List.of("WOE", "EXTERNAL"));
        snapshot.put("lpr5y", 6.0);
        var next = orchestrator.revalidate(user, application, List.of("WOE", "EXTERNAL"), cached);
        assertNotSame(cached, next); assertNotEquals(cached.features().get("ahp"), next.features().get("ahp"));
        verify(ahp, times(2)).fuse(anyMap(), any()); verifyNoInteractions(history);
    }
    @Test void changedAvailableRepaymentFactsRecalculateCachedFusion() {
        var models = List.of("WOE", "EXTERNAL", "BEHAVIOR");
        when(history.query(anyString())).thenReturn(facts(DataQuality.AVAILABLE, 0, LatestPayment.FULL));
        var cached = orchestrator.assess(user, application, models);
        when(history.query(anyString())).thenReturn(facts(DataQuality.AVAILABLE, 2, LatestPayment.NOT_FULL));
        var next = orchestrator.revalidate(user, application, models, cached);
        assertNotSame(cached, next);
        assertEquals(60, ((Map<?, ?>) cached.features().get("behaviorDetails")).get("score"));
        assertEquals(10, ((Map<?, ?>) next.features().get("behaviorDetails")).get("score"));
        assertNotEquals(new RiskReportAssembler().assemble(cached, 1L, 101L, 2L).getTotalScore(),
                new RiskReportAssembler().assemble(next, 1L, 101L, 2L).getTotalScore());
        verify(history, times(2)).query("BUSINESS-2");
        verify(ahp, times(2)).fuse(anyMap(), any());
    }
    @Test void modelNormalizationKeepsDefaultsEmptyDedupAndValidation() {
        assertEquals(List.of("WOE"), EnabledRiskModels.normalize(null));
        assertEquals(List.of(), EnabledRiskModels.normalize(List.of()));
        assertEquals(List.of("BEHAVIOR"), EnabledRiskModels.normalize(List.of("BEHAVIOR", "BEHAVIOR")));
        for (List<String> invalid : List.of(List.of("behavior"), List.of("ABC"), Arrays.asList((String) null)))
            assertThrows(IllegalArgumentException.class, () -> orchestrator.assess(user, application, invalid));
        verifyNoInteractions(history, features);
    }
}
