package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.enums.RiskReasonCode;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.risk.feature.*;
import com.example.zongshe1.modules.risk.service.impl.*;
import com.example.zongshe1.modules.risk.service.scoring.ExternalPublicModel;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskWeek5Test {
    private final FeatureContextService features = mock(FeatureContextService.class);
    private final ScoringCardRiskEvaluator woe = mock(ScoringCardRiskEvaluator.class);
    private final ExternalPublicModel external = spy(new ExternalPublicModel());
    private final CurrentRiskFeatures current = new CurrentRiskFeatures();
    private final FeatureSnapshot snapshot = new FeatureSnapshot();
    private final User user = new User();
    private final LoanApplication application = new LoanApplication();
    private final com.example.zongshe1.modules.risk.service.ahp.AhpFusionService ahp = spy(
            new com.example.zongshe1.modules.risk.service.ahp.AhpFusionService(
                    new com.example.zongshe1.modules.risk.service.ahp.AhpMatrixBuilder(),
                    new com.example.zongshe1.modules.risk.service.ahp.AhpWeightCalculator()));
    private TwoStageRiskOrchestrator orchestrator;

    @BeforeEach void setup() {
        RiskRuleEvaluator admission = mock(RiskRuleEvaluator.class);
        when(admission.isEnabled()).thenReturn(true);
        when(admission.evaluateRisk(user, application)).thenReturn(Map.of("passed", true));
        when(woe.isEnabled()).thenReturn(true);
        when(woe.evaluateRisk(user, application)).thenReturn(Map.of("passed", true,
                "details", Map.of("totalPoints", 80, "maxPoints", 100,
                        "cardVersion", "woe-card-v1", "breakdown", List.of())));
        snapshot.put("lpr1y", new BigDecimal("3.0"));
        snapshot.put("lpr5y", new BigDecimal("3.5"));
        snapshot.put("stale", false);
        when(features.build(user, application)).thenReturn(snapshot);
        orchestrator = new TwoStageRiskOrchestrator(List.of(admission, woe), woe, features, current, external, ahp, mock(com.example.zongshe1.modules.risk.feature.BehaviorFeatureQueryService.class), new com.example.zongshe1.modules.risk.service.scoring.BehaviorScoreEngine());
    }

    @AfterEach void cleanup() { current.clear(); }

    @Test void dishonestAlwaysRejectsBeforeCreditIncludingEmptyModels() {
        for (List<String> models : List.of(List.of("WOE", "EXTERNAL"), List.<String>of())) {
            snapshot.put("dishonestHit", true);
            var result = orchestrator.assess(user, application, models);
            assertEquals("REJECTED", result.decision());
            assertEquals("ADMISSION", result.stage());
            assertTrue(result.reasonCodes().contains(RiskReasonCode.DISHONEST_PUBLIC));
            assertEquals(List.of(), result.features().get("executedModels"));
        }
        verify(woe, never()).evaluateRisk(any(), any());
        verify(external, never()).evaluate(any());
        verifyNoInteractions(ahp);
        assertNull(current.get());
    }

    @Test void onlyWoeAndNullDefaultExecuteWoe() {
        var single = orchestrator.assess(user, application, List.of("WOE"));
        assertEquals("APPROVED", single.decision());
        assertEquals("SINGLE", single.features().get("fusionMode"));
        assertFalse(single.features().containsKey("ahp"));
        assertEquals("APPROVED", orchestrator.assess(user, application, null).decision());
        verify(woe, times(2)).evaluateRisk(user, application);
        verify(external, never()).evaluate(any());
        verifyNoInteractions(ahp);
    }

    @Test void onlyExternalExecutesExternal() {
        var result = orchestrator.assess(user, application, List.of("EXTERNAL"));
        assertEquals("APPROVED", result.decision());
        var report = new RiskReportAssembler().assemble(result, 1L, 2L, 3L);
        assertEquals("SINGLE", report.getBreakdown().get("fusionMode"));
        assertEquals(68, report.getTotalScore());
        assertEquals(100, report.getMaxScore());
        assertNull(report.getBreakdown().get("scorecard"));
        assertFalse(report.getBreakdown().containsKey("ahpWeights"));
        assertFalse(report.getBreakdown().containsKey("ahpConsistencyRatio"));
        assertEquals(List.of("EXTERNAL"), result.features().get("executedModels"));
        verifyNoInteractions(ahp);
        verify(external).evaluate(snapshot);
        verify(woe, never()).evaluateRisk(any(), any());
    }

    @Test void bothModelsAreRecordedOnceAndSurviveReportJsonRoundTrip() throws Exception {
        var result = orchestrator.assess(user, application, List.of("WOE", "EXTERNAL", "WOE"));
        assertEquals("APPROVED", result.decision());
        assertEquals("AHP", result.features().get("fusionMode"));
        verify(woe).evaluateRisk(user, application);
        verify(external).evaluate(snapshot);
        assertEquals(List.of("WOE", "EXTERNAL"), result.features().get("enabledModels"));
        var report = new RiskReportAssembler().assemble(result, 1L, 2L, 3L);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(report));
        assertEquals(80, json.at("/breakdown/modelResults/WOE/score").asInt());
        assertEquals(100, json.at("/breakdown/modelResults/EXTERNAL/maxScore").asInt());
        assertEquals(2, json.at("/featureSnapshot/extra/executedModels").size());
        assertTrue(report.getTotalScore() >= 68 && report.getTotalScore() <= 80);
        assertEquals(2, json.at("/breakdown/ahp/weights").size());
        assertEquals(0, json.at("/breakdown/ahp/cr").asDouble());
    }

    @Test void emptyMeansNoCreditModels() {
        var result = orchestrator.assess(user, application, List.of());
        assertEquals("PASS", result.admission());
        assertEquals("MANUAL_REVIEW", result.decision());
        assertEquals(List.of(), result.features().get("enabledModels"));
        var report = new RiskReportAssembler().assemble(result, 1L, 2L, 3L);
        assertEquals("SKIPPED", report.getBreakdown().get("fusionMode"));
        assertEquals("NO_ENABLED_MODELS", ((Map<?, ?>) report.getBreakdown().get("skipped")).get("credit"));
        assertNull(report.getTotalScore());
        verify(woe, never()).evaluateRisk(any(), any());
        verify(external, never()).evaluate(any());
        verifyNoInteractions(ahp);
    }

    @Test void unknownAndNullModelNamesFailBeforeFeatures() {
        for (List<String> models : List.of(List.of("WOE", "ABC"), List.of("woe"), Arrays.asList((String) null))) {
            assertThrows(IllegalArgumentException.class, () -> orchestrator.assess(user, application, models));
        }
        verifyNoInteractions(features);
    }

    @Test void externalIsDeterministicBoundedAndRespondsToRatesAndStaleness() {
        var first = external.evaluate(snapshot);
        assertEquals(first, external.evaluate(snapshot));
        int baseline = (Integer) first.get("score");
        snapshot.put("lpr1y", new BigDecimal("5"));
        assertTrue((Integer) external.evaluate(snapshot).get("score") < baseline);
        snapshot.put("lpr1y", new BigDecimal("3.0"));
        snapshot.put("stale", true);
        assertEquals(baseline - 15, external.evaluate(snapshot).get("score"));
        for (Object rate : Arrays.asList(null, -1, 0, 1, 10, new BigDecimal("1E100"), Double.NaN)) {
            snapshot.put("lpr1y", rate);
            snapshot.put("lpr5y", rate);
            int score = (Integer) external.evaluate(snapshot).get("score");
            assertTrue(score >= 0 && score <= 100);
        }
        snapshot.put("dishonestHit", true);
        var hit = external.evaluate(snapshot);
        snapshot.put("dishonestHit", false);
        assertEquals(hit, external.evaluate(snapshot));
    }

    @Test void publicCacheFeaturesAreIncludedInAssessmentSnapshot() {
        var cache = mock(com.example.zongshe1.modules.crawler.service.ExternalDataCacheService.class);
        var users = mock(com.example.zongshe1.modules.user.repository.UserRepository.class);
        var sources = mock(com.example.zongshe1.modules.risk.datasource.ExternalDataOrchestrator.class);
        when(sources.fetchAll(any())).thenReturn(List.of());
        user.setUserName("张三");
        when(cache.getExactPayload("DISHONEST_PUBLIC", "NAME_张三"))
                .thenReturn(Optional.of(Map.of("dishonestHit", true)));
        when(cache.getLatestPayload("MACRO_LPR", "LPR_CURRENT"))
                .thenReturn(Optional.of(Map.of("lpr1y", 3.0, "lpr5y", 3.5)));
        when(cache.getExactPayload("MACRO_LPR", "LPR_CURRENT")).thenReturn(Optional.empty());
        var context = new FeatureContextService(sources, List.of(), new RiskFeatureQueryService(users, cache));
        var built = context.build(user, application);
        assertEquals(true, built.get("dishonestHit"));
        assertEquals(new BigDecimal("3.0"), built.get("lpr1y"));
        assertEquals(true, built.get("stale"));
        verifyNoInteractions(users);
    }

    @Test void externalSingleRejectsBelowThresholdAndApprovesAtBoundary() {
        snapshot.put("lpr1y", 4);
        snapshot.put("lpr5y", 4);
        assertEquals("APPROVED", orchestrator.assess(user, application, List.of("EXTERNAL")).decision());
        snapshot.put("lpr1y", new BigDecimal("4.2"));
        var rejected = orchestrator.assess(user, application, List.of("EXTERNAL"));
        assertEquals("REJECTED", rejected.decision());
        assertEquals("SINGLE", rejected.features().get("fusionMode"));
        verify(woe, never()).evaluateRisk(any(), any());
    }

    @Test void externalThresholdCanBeConfiguredAndInvalidConfigurationFails() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ExternalPublicModel.class)
                .withPropertyValues("risk.external.pass-min-score=69")
                .run(context -> {
                    var scored = context.getBean(ExternalPublicModel.class).evaluate(snapshot);
                    assertEquals(68, scored.get("score"));
                    assertEquals("REJECTED", scored.get("decision"));
                    var breakdown = (Map<?, ?>) scored.get("breakdown");
                    assertEquals(69, ((Map<?, ?>) breakdown.get("decisionPolicy")).get("passMinScore"));
                });
        for (int threshold : List.of(-1, 101)) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(ExternalPublicModel.class)
                    .withPropertyValues("risk.external.pass-min-score=" + threshold)
                    .run(context -> assertNotNull(context.getStartupFailure()));
        }
    }

    @Test void configuredOffWoeLeavesOneActualModelAndDoesNotInvokeAhp() {
        when(woe.isEnabled()).thenReturn(false);
        var result = orchestrator.assess(user, application, List.of("WOE", "EXTERNAL"));
        assertEquals("APPROVED", result.decision());
        assertEquals("SINGLE", result.features().get("fusionMode"));
        assertEquals(List.of("EXTERNAL"), result.features().get("executedModels"));
        verifyNoInteractions(ahp);
        verify(woe, never()).evaluateRisk(any(), any());
    }
}
