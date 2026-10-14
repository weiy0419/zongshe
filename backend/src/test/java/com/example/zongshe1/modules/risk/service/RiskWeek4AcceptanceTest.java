package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.exception.GlobalExceptionHandler;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.product.repository.ProductRepository;
import com.example.zongshe1.modules.risk.controller.RiskController;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureContextService;
import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.feature.RiskFeatureQueryService;
import com.example.zongshe1.modules.risk.repository.RiskReportRepository;
import com.example.zongshe1.modules.risk.service.impl.BasicRiskRuleEvaluator;
import com.example.zongshe1.modules.risk.service.impl.RiskServiceImpl;
import com.example.zongshe1.modules.risk.service.impl.ScoringCardRiskEvaluator;
import com.example.zongshe1.modules.risk.service.scoring.impl.SimpleScoringCardEngine;
import com.example.zongshe1.modules.risk.service.scoring.impl.WoeScoringCardEngine;
import com.example.zongshe1.modules.risk.service.scoring.ScoringCardEngine;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际执行 Controller、Service、去重服务、编排、WOE、报告组装及 JSON 缓存往返。
 * 仓库/特征输入固定，Redis 传输和脚本效果用内存替身模拟；不证明真实 Redis/Lua 或 JWT/网关联调。
 */
class RiskWeek4AcceptanceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<Long, LoanApplication> applications = new HashMap<>();
    private final Map<String, String> cache = new HashMap<>();
    private final Map<String, Long> expiresAt = new HashMap<>();
    private long redisSeconds;
    private User user;
    private FeatureContextService features;
    private WoeScoringCardEngine woe;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        user = new User();
        user.setId(2L);
        user.setUserStatus(1);
        user.setCreditScore(720);
        LoanApplicationRepository repository = mock(LoanApplicationRepository.class);
        when(repository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(applications.get(i.getArgument(0))));
        UserRepository users = mock(UserRepository.class);
        when(users.findById(2L)).thenReturn(Optional.of(user));
        ProductRepository products = mock(ProductRepository.class);
        when(products.existsById(7L)).thenReturn(true);
        features = mock(FeatureContextService.class);
        when(features.build(eq(user), any())).thenAnswer(i -> {
            LoanApplication application = i.getArgument(1);
            FeatureSnapshot snapshot = new FeatureSnapshot();
            snapshot.put("credit_score", user.getCreditScore());
            snapshot.put("user_status", user.getUserStatus());
            snapshot.put("loan_amount", application.getLoanAmount());
            snapshot.put("credit_overdue_count", 0);
            snapshot.put("telecom_online_months", 24);
            return snapshot;
        });

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> cached(i.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (cached(key) != null) return false;
            Duration ttl = i.getArgument(2);
            assertEquals(Duration.ofSeconds(120), ttl);
            cache.put(key, i.getArgument(1));
            expiresAt.put(key, redisSeconds + ttl.toSeconds());
            return true;
        });
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(i -> {
            List<String> keys = i.getArgument(1);
            Object[] args = (Object[]) i.getRawArguments()[2];
            if (!args[0].equals(cached(keys.get(0)))) return 0L;
            if (keys.size() == 2) {
                long ttl = Long.parseLong(args[2].toString());
                assertEquals(120L, ttl);
                cache.put(keys.get(1), args[1].toString());
                expiresAt.put(keys.get(1), redisSeconds + ttl);
            }
            cache.remove(keys.get(0));
            expiresAt.remove(keys.get(0));
            return 1L;
        });
        RiskAssessmentDedupService dedup = new RiskAssessmentDedupService(redis, mapper);
        ReflectionTestUtils.setField(dedup, "bucketSize", new BigDecimal("1000"));
        ReflectionTestUtils.setField(dedup, "policyVersion", "w4-v2");
        CurrentRiskFeatures current = new CurrentRiskFeatures();
        BasicRiskRuleEvaluator basic = new BasicRiskRuleEvaluator();
        ReflectionTestUtils.setField(basic, "minCreditScoreForLoan", 450);
        woe = spy(new WoeScoringCardEngine(current));
        ScoringCardRiskEvaluator scoring = new ScoringCardRiskEvaluator(woe);
        ReflectionTestUtils.setField(scoring, "enabled", true);
        ReflectionTestUtils.setField(scoring, "passMinPoints", 40);
        List<RiskRuleEvaluator> rules = List.of(basic, scoring);
        TwoStageRiskOrchestrator orchestrator = new TwoStageRiskOrchestrator(rules, scoring, features, current, new com.example.zongshe1.modules.risk.service.scoring.ExternalPublicModel(), new com.example.zongshe1.modules.risk.service.ahp.AhpFusionService(new com.example.zongshe1.modules.risk.service.ahp.AhpMatrixBuilder(), new com.example.zongshe1.modules.risk.service.ahp.AhpWeightCalculator()));
        RiskReportRepository reports = mock(RiskReportRepository.class);
        Map<Long, com.example.zongshe1.modules.risk.entity.RiskReport> persistedReports =
                new HashMap<>();

        when(reports.findForAssessment(anyLong()))
                .thenAnswer(invocation -> {
                    Long applicationId = invocation.getArgument(0);
                    return Optional.ofNullable(persistedReports.get(applicationId));
                });

        when(reports.findByLoanApplication_Id(anyLong()))
                .thenAnswer(invocation ->
                        Optional.ofNullable(persistedReports.get(invocation.getArgument(0))));

        when(reports.save(any()))
                .thenAnswer(invocation -> {
                    var report = invocation.getArgument(
                            0,
                            com.example.zongshe1.modules.risk.entity.RiskReport.class);

                    report.setId(1001L);
                    persistedReports.put(report.getLoanApplication().getId(), report);

                    return report;
                });

        RiskServiceImpl service = new RiskServiceImpl(
                rules,
                repository,
                users,
                reports,
                features,
                current,
                mapper,
                products,
                orchestrator,
                dedup,
                new RiskReportAssembler(), mock(RiskDecisionEventService.class)
        );
        mvc = MockMvcBuilders.standaloneSetup(new RiskController(service, mock(RiskFeatureQueryService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        application(101L, "10000");
    }

    private String cached(String key) {
        if (expiresAt.getOrDefault(key, Long.MAX_VALUE) <= redisSeconds) {
            cache.remove(key);
            expiresAt.remove(key);
        }
        return cache.get(key);
    }

    private void application(long id, String amount) {
        LoanApplication application = new LoanApplication();
        application.setId(id);
        application.setUser(user);
        application.setLoanAmount(new BigDecimal(amount));
        application.setLoanTerm(12);
        applications.put(id, application);
    }

    private JsonNode assess(String json) throws Exception {
        String body = mvc.perform(post("/api/risk/assess").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    @Test
    void twoHttpRequestsExecuteWoeOnceAndDifferOnlyInReusedFlag() throws Exception {
        String request = "{\"applicationId\":101,\"productId\":7,\"enabledModels\":[\"WOE\"]}";
        JsonNode first = assess(request);
        JsonNode second = assess(request);
        assertFalse(first.at("/breakdown/dedup/reused").asBoolean());
        assertTrue(second.at("/breakdown/dedup/reused").asBoolean());
        assertEquals("woe-card-v1", first.path("cardVersion").asText());
        assertEquals(100, first.path("totalScore").asInt());
        assertEquals("APPROVED", first.path("decision").asText());
        assertEquals(first.path("assessedAt"), second.path("assessedAt"));
        assertEquals(1001L, first.path("reportId").asLong());
        ObjectNode comparableSecond = second.deepCopy();
        ((ObjectNode) comparableSecond.at("/breakdown/dedup")).put("reused", false);
        assertEquals(first, comparableSecond);
        verify(woe, times(1)).evaluate(any(), any());
        verify(features, times(1)).build(any(), any());
        assertEquals(1, cache.size());
        assertEquals(List.of(120L), List.copyOf(expiresAt.values()));
        System.out.println("WEEK4_FIRST=" + first);
        System.out.println("WEEK4_SECOND=" + second);
    }

    @Test
    void modelIsolationPreventsCachedApprovalWhenWoeDisabled() throws Exception {
        assess("{\"applicationId\":101,\"productId\":7}");
        JsonNode disabled = assess("{\"applicationId\":101,\"productId\":7,\"enabledModels\":[]}");
        assertEquals("MANUAL_REVIEW", disabled.path("decision").asText());
        assertEquals("DISABLED", disabled.at("/breakdown/skipped/scorecard").asText());
        assertTrue(disabled.path("totalScore").isNull());
        assertFalse(disabled.at("/breakdown/dedup/reused").asBoolean());
        verify(woe, times(1)).evaluate(any(), any());
        verify(features, times(2)).build(any(), any());
    }

    @Test
    void nearbyApplicationsReuseComputationButKeepCurrentApplicationId() throws Exception {
        application(102L, "10001");
        assess("{\"applicationId\":101,\"productId\":7}");
        JsonNode next = assess("{\"applicationId\":102,\"productId\":7}");
        assertEquals(102L, next.path("applicationId").asLong());
        assertEquals(10000, next.at("/featureSnapshot/loanAmount").asInt());
        assertTrue(next.at("/breakdown/dedup/reused").asBoolean());
        verify(woe, times(1)).evaluate(any(), any());
    }

    @Test
    void amountScoringBoundariesDoNotShareCachedScores() throws Exception {
        String[] amounts = {"50000", "50001", "200000", "200001"};
        int[] expectedScores = {100, 90, 90, 80};
        for (int index = 0; index < amounts.length; index++) {
            long id = 201L + index;
            application(id, amounts[index]);
            JsonNode report = assess("{\"applicationId\":" + id + ",\"productId\":7}");
            assertFalse(report.at("/breakdown/dedup/reused").asBoolean());
            assertEquals(expectedScores[index], report.path("totalScore").asInt());
        }
        verify(woe, times(4)).evaluate(any(), any());
    }

    @Test
    void missingProductIdDoesNotPretendToDeduplicate() throws Exception {
        assess("{\"applicationId\":101}");
        JsonNode second = assess("{\"applicationId\":101}");
        assertEquals("MISSING_PRODUCT_ID", second.at("/breakdown/dedup/skipped").asText());
        assertFalse(second.at("/breakdown/dedup/reused").asBoolean());
        assertTrue(cache.isEmpty());
        verify(woe, times(2)).evaluate(any(), any());
    }

    @Test
    void afterSimulatedTtlExpiresAssessmentRunsAgain() throws Exception {
        String request = "{\"applicationId\":101,\"productId\":7}";
        assess(request);
        redisSeconds = 121;
        assertFalse(assess(request).at("/breakdown/dedup/reused").asBoolean());
        verify(woe, times(2)).evaluate(any(), any());
    }

    @Test
    void springSelectsWoeEngineWhenConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(CurrentRiskFeatures.class, SimpleScoringCardEngine.class, WoeScoringCardEngine.class)
                .withPropertyValues("risk.scoring-card.engine=woe")
                .run(context -> assertInstanceOf(WoeScoringCardEngine.class, context.getBean(ScoringCardEngine.class)));
    }

    @Test
    void persistedAssessmentCanBeQueriedWithoutReassessment() throws Exception {
        JsonNode assessed = assess(
                "{\"applicationId\":101,\"productId\":7,\"enabledModels\":[\"WOE\"]}"
        );

        String body = mvc.perform(
                        get("/api/risk/applications/101/report")
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode response = mapper.readTree(body);
        JsonNode report = response.path("data");

        assertTrue(response.path("success").asBoolean());

        assertEquals(1001L, report.path("reportId").asLong());
        assertEquals(101L, report.path("applicationId").asLong());

        assertEquals(
                assessed.path("decision").asText(),
                report.path("decision").asText()
        );

        assertEquals(
                assessed.path("totalScore").asInt(),
                report.path("totalScore").asInt()
        );

        assertEquals(
                assessed.path("cardVersion").asText(),
                report.path("cardVersion").asText()
        );

        assertEquals(
                assessed.at("/breakdown/admission").asText(),
                report.at("/breakdown/admission").asText()
        );

        assertEquals(
                assessed.at("/breakdown/scorecard").asInt(),
                report.at("/breakdown/scorecard").asInt()
        );

        // GET 只读取已持久化报告，不能重新执行 WOE。
        verify(woe, times(1)).evaluate(any(), any());
    }

    @Test
    void week5ExternalResultsPersistThroughGetWithProductDedupEnabled() throws Exception {
        FeatureSnapshot snapshot = new FeatureSnapshot();
        snapshot.put("credit_score", 720);
        snapshot.put("user_status", 1);
        snapshot.put("loan_amount", new BigDecimal("10000"));
        snapshot.put("lpr1y", 3.0);
        snapshot.put("lpr5y", 3.45);
        snapshot.put("stale", false);
        when(features.build(eq(user), any())).thenReturn(snapshot);
        JsonNode assessed = assess("{\"applicationId\":101,\"productId\":7,\"enabledModels\":[\"WOE\",\"EXTERNAL\"]}");
        assertEquals(2, assessed.at("/breakdown/modelResults").size());
        assertEquals("AHP", assessed.at("/breakdown/fusionMode").asText());
        assertEquals(2, assessed.at("/breakdown/ahp/matrix").size());
        assertEquals(2, assessed.at("/breakdown/ahp/weights").size());
        assertFalse(assessed.at("/breakdown/ahp/fallbackUsed").asBoolean());
        assertEquals("APPROVED", assessed.path("decision").asText());
        assertEquals(1.0 / 3, assessed.at("/breakdown/ahp/weights/EXTERNAL").asDouble(), 1e-12);
        assertEquals(0, assessed.at("/breakdown/ahp/cr").asDouble());
        assertEquals(assessed.at("/breakdown/ahp"), assessed.at("/featureSnapshot/extra/ahp"));
        String body = mvc.perform(get("/api/risk/applications/101/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(assessed, mapper.readTree(body).path("data"));
        verify(woe, times(1)).evaluate(any(), any());
        verify(features, times(1)).build(any(), any());
    }

    @Test
    void week5DishonestRejectionPersistsWithoutWoe() throws Exception {
        FeatureSnapshot hit = new FeatureSnapshot();
        hit.put("dishonestHit", true);
        when(features.build(eq(user), any())).thenReturn(hit);
        JsonNode assessed = assess("{\"applicationId\":101,\"enabledModels\":[\"WOE\",\"EXTERNAL\"]}");
        assertEquals("REJECTED", assessed.path("decision").asText());
        assertEquals("DISHONEST_PUBLIC", assessed.path("reasonCodes").get(0).asText());
        assertEquals(0, assessed.at("/breakdown/executedModels").size());
        String body = mvc.perform(get("/api/risk/applications/101/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(assessed, mapper.readTree(body).path("data"));
        verify(woe, never()).evaluate(any(), any());
    }

    @Test
    void externalSingleDecisionAndScorePersistWithoutWoeOrFusion() throws Exception {
        FeatureSnapshot snapshot = new FeatureSnapshot();
        snapshot.put("lpr1y", 3);
        snapshot.put("lpr5y", 4);
        snapshot.put("stale", false);
        when(features.build(eq(user), any())).thenReturn(snapshot);
        JsonNode assessed = assess("{\"applicationId\":101,\"enabledModels\":[\"EXTERNAL\"]}");
        assertEquals("APPROVED", assessed.path("decision").asText());
        assertEquals(65, assessed.path("totalScore").asInt());
        assertEquals("SINGLE", assessed.at("/breakdown/fusionMode").asText());
        assertTrue(assessed.at("/breakdown/scorecard").isNull());
        assertEquals(65, assessed.at("/breakdown/modelResults/EXTERNAL/score").asInt());
        assertEquals("APPROVED", assessed.at("/breakdown/credit/decision").asText());
        assertFalse(assessed.path("breakdown").has("ahpWeights"));
        String body = mvc.perform(get("/api/risk/applications/101/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(assessed, mapper.readTree(body).path("data"));
        verify(woe, never()).evaluate(any(), any());
        verify(features, times(1)).build(any(), any());
    }
}
