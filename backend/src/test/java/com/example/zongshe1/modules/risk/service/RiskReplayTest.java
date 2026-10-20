package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.common.exception.BusinessException;
import com.example.zongshe1.common.exception.GlobalExceptionHandler;
import com.example.zongshe1.modules.crawler.service.DataCrawlerService;
import com.example.zongshe1.modules.crawler.service.ExternalDataCacheService;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.product.repository.ProductRepository;
import com.example.zongshe1.modules.risk.controller.RiskController;
import com.example.zongshe1.modules.risk.datasource.ExternalDataOrchestrator;
import com.example.zongshe1.modules.risk.entity.RiskReport;
import com.example.zongshe1.modules.risk.feature.*;
import com.example.zongshe1.modules.risk.repository.RiskReportRepository;
import com.example.zongshe1.modules.risk.service.ahp.*;
import com.example.zongshe1.modules.risk.service.impl.*;
import com.example.zongshe1.modules.risk.service.scoring.ExternalPublicModel;
import com.example.zongshe1.modules.risk.service.scoring.impl.WoeScoringCardEngine;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RiskReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RiskReportRepository reports = mock(RiskReportRepository.class);
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ExternalDataCacheService cache = mock(ExternalDataCacheService.class);
    private final DataCrawlerService crawler = mock(DataCrawlerService.class);
    private final ExternalDataOrchestrator sources = mock(ExternalDataOrchestrator.class);
    private final RiskFeatureQueryService query = spy(new RiskFeatureQueryService(users, cache));
    private final FeatureContextService features = spy(new FeatureContextService(sources, List.of(), query));
    private final CurrentRiskFeatures current = spy(new CurrentRiskFeatures());
    private final RiskAssessmentDedupService dedup = mock(RiskAssessmentDedupService.class);
    private final BehaviorFeatureQueryService behaviorQuery = mock(BehaviorFeatureQueryService.class);
    private final com.example.zongshe1.modules.risk.service.scoring.BehaviorScoreEngine behaviorEngine =
            spy(new com.example.zongshe1.modules.risk.service.scoring.BehaviorScoreEngine());
    private final ExternalPublicModel external = spy(new ExternalPublicModel());
    private final AhpFusionService ahp = spy(new AhpFusionService(new AhpMatrixBuilder(), new AhpWeightCalculator()));
    private final Map<Long, RiskReport> stored = new HashMap<>();
    private final Map<String, Object> liveLpr = new HashMap<>(Map.of("lpr1y", 3.0, "lpr5y", 3.5));
    private boolean liveDishonest;
    private final User user = new User();
    private final LoanApplication application = new LoanApplication();
    private ScoringCardRiskEvaluator woe;
    private BasicRiskRuleEvaluator admission;
    private TwoStageRiskOrchestrator orchestrator;
    private RiskServiceImpl service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        user.setId(2L);
        user.setUserId("BUSINESS-2");
        when(behaviorQuery.query("BUSINESS-2")).thenReturn(new BehaviorFeatureSnapshot("BUSINESS-2",
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0), BehaviorFeatureSnapshot.DataQuality.NO_HISTORY,
                0, 0, null, null, BehaviorFeatureSnapshot.LatestPayment.NONE, List.of("NO_PLANS_OR_PAYMENTS")));
        user.setUserName("历史用户");
        user.setCreditScore(720);
        user.setUserStatus(1);
        application.setId(101L);
        application.setUser(user);
        application.setLoanAmount(new BigDecimal("10000"));
        application.setLoanTerm(12);
        admission = spy(new BasicRiskRuleEvaluator());
        ReflectionTestUtils.setField(admission, "minCreditScoreForLoan", 450);
        woe = spy(new ScoringCardRiskEvaluator(new WoeScoringCardEngine(current)));
        ReflectionTestUtils.setField(woe, "enabled", true);
        ReflectionTestUtils.setField(woe, "passMinPoints", 40);
        var rules = List.<RiskRuleEvaluator>of(admission, woe);
        orchestrator = spy(new TwoStageRiskOrchestrator(rules, woe, features, current, external, ahp, behaviorQuery, behaviorEngine));
        service = new RiskServiceImpl(rules, applications, users, reports, features, current,
                mapper, products, orchestrator, dedup, new RiskReportAssembler(), mock(RiskDecisionEventService.class));
        mvc = MockMvcBuilders.standaloneSetup(new RiskController(service, query))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(applications.findById(101L)).thenReturn(Optional.of(application));
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(sources.fetchAll(any())).thenReturn(List.of());
        when(cache.getLatestPayload("MACRO_LPR", "LPR_CURRENT"))
                .thenAnswer(i -> Optional.of(new HashMap<>(liveLpr)));
        when(cache.getExactPayload("MACRO_LPR", "LPR_CURRENT"))
                .thenAnswer(i -> Optional.of(new HashMap<>(liveLpr)));
        when(cache.getExactPayload("DISHONEST_PUBLIC", "NAME_历史用户"))
                .thenAnswer(i -> liveDishonest ? Optional.of(Map.of("dishonestHit", true)) : Optional.empty());
        // Repository test double keeps the actual JSON written by assess; replay must only findById.
        when(reports.save(any())).thenAnswer(i -> {
            RiskReport entity = i.getArgument(0);
            entity.setId(77L);
            stored.put(entity.getId(), entity);
            return entity;
        });
        when(reports.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
    }

    @AfterEach
    void cleanup() { current.clear(); }

    private RiskReportDTO persist(List<String> models) {
        RiskAssessmentRequest request = new RiskAssessmentRequest();
        request.setApplicationId(101L);
        request.setEnabledModels(models);
        RiskReportDTO original = service.assess(request);
        assertEquals(77L, original.getReportId());
        assertNotNull(stored.get(77L).getDetailsJson());
        clearInvocations(reports, applications, users, products, cache, crawler, sources,
                query, features, current, dedup, admission, woe, external, ahp, orchestrator, behaviorQuery, behaviorEngine);
        return original;
    }

    private void assertNoLiveAccessOrWrites() {
        verify(query, never()).query(anyLong());
        verify(query, never()).query(any(User.class));
        verify(features, never()).build(any(), any());
        verify(sources, never()).fetchAll(any());
        verify(crawler, never()).runFullCrawl();
        verify(cache, never()).save(any(), any(), any(), any(), any(), anyInt());
        verify(cache, never()).saveByLookupKey(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(query, features, sources, crawler, cache, applications, users,
                products, current, dedup, admission, woe, external, ahp, orchestrator, behaviorQuery, behaviorEngine);
        verify(reports, never()).save(any());
        verify(reports, never()).saveAndFlush(any());
        verify(reports, never()).saveAll(any());
        verify(reports, never()).delete(any());
    }

    private void assertHttpReplayEquals(RiskReportDTO original) throws Exception {
        RiskReport entity = stored.get(77L);
        String persistedJson = entity.getDetailsJson();
        // Any accidental traversal of the live application association is detected as well.
        LoanApplication association = mock(LoanApplication.class);
        entity.setLoanApplication(association);
        String entityBefore = mapper.writeValueAsString(entity);
        clearInvocations(association);
        var response = mvc.perform(post("/api/risk/replay/77"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.replay").value(true))
                .andExpect(jsonPath("$.breakdown.replaySourceReportId").value(77))
                .andExpect(jsonPath("$.breakdown.replayMode").value("PERSISTED_RESULT"))
                .andReturn().getResponse().getContentAsByteArray();
        RiskReportDTO replay = mapper.readValue(response, RiskReportDTO.class);
        var expected = mapper.readTree(mapper.writeValueAsString(original));
        ObjectNode actual = (ObjectNode) mapper.readTree(mapper.writeValueAsString(replay));
        ((ObjectNode) actual.get("breakdown")).remove(List.of("replay", "replaySourceReportId", "replayMode"));
        // Includes decision, totalScore, all model/AHP fields, full snapshot and historical timestamp.
        assertEquals(expected, actual);
        verifyNoInteractions(association);
        assertEquals(persistedJson, entity.getDetailsJson());
        assertEquals(entityBefore, mapper.writeValueAsString(entity));
        assertEquals(1, stored.size());
        assertNoLiveAccessOrWrites();
        verify(reports).findById(77L);
        verifyNoMoreInteractions(reports);
    }

    @Test
    void historicalAhpReportReplaysThroughPostWithoutMutation() throws Exception {
        RiskReportDTO original = persist(List.of("WOE", "EXTERNAL"));
        assertEquals("AHP", original.getBreakdown().get("fusionMode"));
        Map<?, ?> details = (Map<?, ?>) original.getBreakdown().get("ahp");
        for (String key : List.of("matrix", "weights", "cr", "fallbackUsed")) assertNotNull(details.get(key));
        assertHttpReplayEquals(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LPR", "DISHONEST", "FEATURES", "ALL"})
    void changedCurrentDataCannotChangeHistoricalReplay(String change) throws Exception {
        RiskReportDTO original = persist(List.of("WOE", "EXTERNAL"));
        if (change.equals("LPR") || change.equals("ALL")) {
            liveLpr.put("lpr1y", 9.0);
            liveLpr.put("lpr5y", 10.0);
        }
        if (change.equals("DISHONEST") || change.equals("ALL")) liveDishonest = true;
        if (change.equals("FEATURES") || change.equals("ALL")) {
            user.setCreditScore(100);
            user.setUserStatus(0);
            application.setLoanAmount(new BigDecimal("999999"));
        }
        assertEquals(false, original.getFeatureSnapshot().getDishonestHit());
        assertEquals(new BigDecimal("3.0"), original.getFeatureSnapshot().getLpr1y());
        assertHttpReplayEquals(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WOE", "EXTERNAL", "BEHAVIOR", "EMPTY", "ADMISSION"})
    void singleSkippedAndRejectedReportsKeepTheirHistoricalResults(String mode) throws Exception {
        liveDishonest = mode.equals("ADMISSION");
        var models = mode.equals("EMPTY") ? List.<String>of()
                : mode.equals("ADMISSION") ? List.of("WOE", "EXTERNAL") : List.of(mode);
        RiskReportDTO original = persist(models);
        if (mode.equals("ADMISSION")) {
            assertEquals("REJECTED", original.getDecision());
            assertNull(original.getTotalScore());
            liveDishonest = false;
        }
        assertHttpReplayEquals(original);
    }

    @Test
    void missingReportReturnsExplicitProjectBusinessError() throws Exception {
        mvc.perform(post("/api/risk/replay/999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RISK_REPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("风险报告不存在：999"));
        assertNoLiveAccessOrWrites();
        verify(reports).findById(999L);
        verifyNoMoreInteractions(reports);
    }

    @Test
    void invalidIdIsRejectedBeforeRepositoryAccess() {
        for (Long id : Arrays.asList(null, 0L, -1L)) {
            assertEquals("INVALID_REPORT_ID", assertThrows(BusinessException.class,
                    () -> service.replay(id)).getCode());
        }
        verifyNoInteractions(reports);
        assertNoLiveAccessOrWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "{}", "{broken", "{\"ruleResults\":[]}"})
    void unavailableOrCorruptHistoryFailsWithoutLiveFallback(String json) throws Exception {
        RiskReport entity = new RiskReport();
        entity.setId(77L);
        entity.setDetailsJson(json);
        stored.put(77L, entity);
        mvc.perform(post("/api/risk/replay/77"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RISK_REPLAY_UNAVAILABLE"));
        assertEquals(json, entity.getDetailsJson());
        assertNoLiveAccessOrWrites();
    }

    @Test
    void oldDtoRestoresOnlyPersistedExtraContextAndKeepsMissingScoresUnknown() throws Exception {
        RiskReportDTO original = persist(List.of("WOE", "EXTERNAL"));
        original.setBreakdown(null);
        original.setTotalScore(null);
        stored.get(77L).setDetailsJson(mapper.writeValueAsString(original));
        RiskReportDTO replay = service.replay(77L);
        for (String key : List.of("enabledModels", "executedModels", "fusionMode", "modelScores", "ahp")) {
            assertEquals(mapper.valueToTree(original.getFeatureSnapshot().getExtra().get(key)),
                    mapper.valueToTree(replay.getBreakdown().get(key)));
        }
        assertNull(replay.getTotalScore());
        assertNoLiveAccessOrWrites();
    }

    @Test
    void persistedFallbackAndNonzeroCrArePreservedRatherThanRecomputed() throws Exception {
        RiskReportDTO original = persist(List.of("WOE", "EXTERNAL"));
        var historicalAhp = new LinkedHashMap<String, Object>();
        historicalAhp.put("matrix", List.of(List.of(1, 9), List.of(0.5, 1)));
        historicalAhp.put("weights", Map.of("WOE", 0.5, "EXTERNAL", 0.5));
        historicalAhp.put("cr", 0.42);
        historicalAhp.put("fallbackUsed", true);
        original.getBreakdown().put("ahp", historicalAhp);
        stored.get(77L).setDetailsJson(mapper.writeValueAsString(original));
        assertHttpReplayEquals(original);
    }

    @Test
    void repeatedReplayDoesNotPersistMetadataOrMutatePreviousResponses() {
        persist(List.of("EXTERNAL"));
        String before = stored.get(77L).getDetailsJson();
        RiskReportDTO first = service.replay(77L);
        first.getBreakdown().put("enabledModels", List.of("CHANGED"));
        first.getFeatureSnapshot().setDishonestHit(true);
        RiskReportDTO second = service.replay(77L);
        assertEquals(List.of("EXTERNAL"), second.getBreakdown().get("enabledModels"));
        assertEquals(false, second.getFeatureSnapshot().getDishonestHit());
        assertEquals(before, stored.get(77L).getDetailsJson());
        assertNoLiveAccessOrWrites();
    }

    @Test
    void behaviorAndThreeModelEvidenceReplaysWithoutAnyCurrentRepaymentQuery() throws Exception {
        RiskReportDTO original = persist(List.of("WOE", "EXTERNAL", "BEHAVIOR"));
        var json = mapper.readTree(stored.get(77L).getDetailsJson());
        assertEquals(mapper.readTree("""
                {"businessUserId":"BUSINESS-2","observedAt":"2026-01-01T00:00",
                 "quality":"NO_HISTORY","historicalOverduePeriods":0,"currentOverduePeriods":0,
                 "latestRecordId":null,"latestPlanId":null,"latestPayment":"NONE",
                 "evidence":["NO_PLANS_OR_PAYMENTS"]}
                """), json.at("/featureSnapshot/extra/behaviorFacts"));
        assertEquals("behavior-v1", json.at("/featureSnapshot/extra/behaviorDetails/version").asText());
        assertEquals("NO_HISTORY", json.at("/featureSnapshot/extra/behaviorDetails/quality").asText());
        assertEquals(json.at("/breakdown/ahp"), json.at("/featureSnapshot/extra/ahp"));
        assertEquals(3, json.at("/featureSnapshot/extra/ahp/matrix").size());
        assertEquals(3, ((Map<?, ?>) ((Map<?, ?>) original.getBreakdown().get("ahp")).get("weights")).size());
        doThrow(new IllegalStateException("live repayment database unavailable")).when(behaviorQuery).query(anyString());
        ReflectionTestUtils.setField(orchestrator, "behaviorPassMinPoints", 100);
        ReflectionTestUtils.setField(woe, "enabled", false);
        liveLpr.put("lpr5y", 9.0);
        assertHttpReplayEquals(original);
    }
}
