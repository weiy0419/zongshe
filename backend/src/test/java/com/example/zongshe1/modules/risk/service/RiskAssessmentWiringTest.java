package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.common.enums.RiskReasonCode;
import com.example.zongshe1.common.exception.BusinessException;
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
import com.example.zongshe1.modules.risk.service.scoring.impl.WoeScoringCardEngine;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentWiringTest {
    @Mock private LoanApplicationRepository applications;
    @Mock private UserRepository users;
    @Mock private ProductRepository products;
    @Mock private RiskReportRepository reports;
    @Mock private FeatureContextService features;
    @Mock private RiskAssessmentDedupService dedup;

    private RiskServiceImpl service;
    private TwoStageRiskOrchestrator orchestrator;
    private CurrentRiskFeatures current;
    private User user;
    private LoanApplication application;
    private RiskAssessmentRequest request;

    @BeforeEach
    void setUp() {
        current = new CurrentRiskFeatures();
        BasicRiskRuleEvaluator basic = new BasicRiskRuleEvaluator();
        ReflectionTestUtils.setField(basic, "minCreditScoreForLoan", 450);
        ScoringCardRiskEvaluator scoring = new ScoringCardRiskEvaluator(new WoeScoringCardEngine(current));
        ReflectionTestUtils.setField(scoring, "enabled", true);
        ReflectionTestUtils.setField(scoring, "passMinPoints", 40);
        List<RiskRuleEvaluator> rules = List.of(basic, scoring);
        orchestrator = new TwoStageRiskOrchestrator(rules, scoring, features, current, new com.example.zongshe1.modules.risk.service.scoring.ExternalPublicModel(), new com.example.zongshe1.modules.risk.service.ahp.AhpFusionService(new com.example.zongshe1.modules.risk.service.ahp.AhpMatrixBuilder(), new com.example.zongshe1.modules.risk.service.ahp.AhpWeightCalculator()));
        service = new RiskServiceImpl(rules, applications, users, reports, features, current,
                new ObjectMapper().findAndRegisterModules(), products, orchestrator, dedup,
                new RiskReportAssembler());
        user = new User();
        user.setId(2L); // 偶数 ID 不再触发原 Mock 的失信拒绝。
        user.setCreditScore(720);
        user.setUserStatus(1);
        application = new LoanApplication();
        application.setId(101L);
        application.setUser(user);
        application.setLoanAmount(new BigDecimal("10000"));
        application.setLoanTerm(12);
        request = new RiskAssessmentRequest();
        request.setApplicationId(101L);
        lenient().when(reports.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void givenApplication() {
        when(applications.findById(101L)).thenReturn(Optional.of(application));
        when(users.findById(2L)).thenReturn(Optional.of(user));
    }

    private void givenFeatures() {
        when(features.build(user, application)).thenAnswer(invocation -> {
            FeatureSnapshot snapshot = new FeatureSnapshot();
            snapshot.put("credit_score", user.getCreditScore());
            snapshot.put("user_status", user.getUserStatus());
            snapshot.put("loan_amount", application.getLoanAmount());
            snapshot.put("credit_overdue_count", 0);
            snapshot.put("telecom_online_months", 24);
            return snapshot;
        });
    }

    @Test
    void existingHttpRequestRunsRealWoeAndKeepsResponseContract() throws Exception {
        givenApplication();
        givenFeatures();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new RiskController(service, mock(RiskFeatureQueryService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/risk/assess").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":101}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(101))
                .andExpect(jsonPath("$.userId").value(2))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.totalScore").value(100))
                .andExpect(jsonPath("$.cardVersion").value("woe-card-v1"))
                .andExpect(jsonPath("$.breakdown.enabledModels[0]").value("WOE"))
                .andExpect(jsonPath("$.breakdown.skipped.external").value("DISABLED"))
                .andExpect(jsonPath("$.breakdown.dedup.skipped").value("MISSING_PRODUCT_ID"));
        verifyNoInteractions(dedup, products);
        verify(reports).save(any());
        assertNull(current.get());
    }

    @Test
    void emptyModelsRunsAdmissionWithoutInventingCardScore() {
        givenApplication();
        givenFeatures();
        request.setEnabledModels(List.of());
        RiskReportDTO report = service.assess(request);
        assertEquals("MANUAL_REVIEW", report.getDecision());
        assertEquals("PASS", report.getBreakdown().get("admission"));
        assertNull(report.getTotalScore());
        assertEquals(List.of(), report.getBreakdown().get("enabledModels"));
        assertEquals("DISABLED", ((Map<?, ?>) report.getBreakdown().get("skipped")).get("scorecard"));
        verify(reports).save(any());
    }

    @Test
    void admissionRejectionReturnsActualReasonCode() {
        givenApplication();
        givenFeatures();
        user.setCreditScore(400);
        RiskReportDTO report = service.assess(request);
        assertEquals("REJECTED", report.getDecision());
        assertEquals(List.of(RiskReasonCode.CREDIT_LOW), report.getReasonCodes());
        assertNull(report.getTotalScore());
        assertEquals("ADMISSION", report.getBreakdown().get("stage"));
    }

    @Test
    void productIdAndModelsReachDedupAndCallbackUsesSamePolicy() {
        givenApplication();
        givenFeatures();
        request.setProductId(7L);
        request.setEnabledModels(List.of());
        when(products.existsById(7L)).thenReturn(true);
        when(dedup.assess(eq(2L), eq(7L), eq(application.getLoanAmount()), eq(List.of()), any()))
                .thenAnswer(invocation -> {
                    Supplier<TwoStageRiskOrchestrator.Result> calculation = invocation.getArgument(4);
                    return new RiskAssessmentDedupService.Lookup(calculation.get(), false);
                });
        RiskReportDTO report = service.assess(request);
        assertEquals("MANUAL_REVIEW", report.getDecision());
        assertEquals(List.of(), report.getBreakdown().get("enabledModels"));
        assertEquals(false, ((Map<?, ?>) report.getBreakdown().get("dedup")).get("reused"));
        verify(reports).save(any());
    }

    @Test
    void cachedComputationBindsCurrentApplicationWithoutRecalculation() {
        givenFeatures();
        var cached = orchestrator.assess(user, application);
        clearInvocations(features);
        LoanApplication next = new LoanApplication();
        next.setId(102L);
        next.setUser(user);
        next.setLoanAmount(new BigDecimal("10001"));
        next.setLoanTerm(12);
        request.setApplicationId(102L);
        request.setProductId(7L);
        when(applications.findById(102L)).thenReturn(Optional.of(next));
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(products.existsById(7L)).thenReturn(true);
        when(dedup.assess(eq(2L), eq(7L), eq(next.getLoanAmount()), eq(List.of("WOE")), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(cached, true));
        RiskReportDTO report = service.assess(request);
        assertEquals(102L, report.getApplicationId());
        assertNull(report.getReportId());
        assertEquals(cached.assessedAt(), report.getAssessedAt());
        assertEquals(new BigDecimal("10000"), report.getFeatureSnapshot().getLoanAmount());
        assertEquals(true, ((Map<?, ?>) report.getBreakdown().get("dedup")).get("reused"));
        verifyNoInteractions(features);
        verify(reports).save(any());
    }

    @Test
    void changedCreditScoreDoesNotReusePreviousApproval() {
        givenApplication();
        givenFeatures();
        var cached = orchestrator.assess(user, application);
        user.setCreditScore(400);
        request.setProductId(7L);
        when(products.existsById(7L)).thenReturn(true);
        when(dedup.assess(eq(2L), eq(7L), eq(application.getLoanAmount()), eq(List.of("WOE")), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(cached, true));
        RiskReportDTO report = service.assess(request);
        assertEquals("REJECTED", report.getDecision());
        assertEquals(List.of(RiskReasonCode.CREDIT_LOW), report.getReasonCodes());
        assertEquals(false, ((Map<?, ?>) report.getBreakdown().get("dedup")).get("reused"));
        verify(features, times(2)).build(user, application);
        verify(reports).save(any());
    }

    @Test
    void userMismatchIsRejectedBeforeEvaluation() {
        givenApplication();
        request.setUserId(99L);
        assertThrows(BusinessException.class, () -> service.assess(request));
        verifyNoInteractions(features, dedup, reports);
    }

    @Test
    void unsupportedModelIsRejectedBeforeRepositoryOrCacheAccess() {
        request.setEnabledModels(List.of("ABC"));
        assertThrows(BusinessException.class, () -> service.assess(request));
        verifyNoInteractions(applications, users, features, dedup, reports);
    }
}
