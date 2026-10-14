package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.common.exception.BusinessException;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.product.repository.ProductRepository;
import com.example.zongshe1.modules.risk.entity.RiskReport;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureContextService;
import com.example.zongshe1.modules.risk.repository.RiskReportRepository;
import com.example.zongshe1.modules.risk.service.impl.RiskServiceImpl;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 Spring 事务拦截器验证提交/回滚选择，仓库和事务管理器为替身。
 * 并发用例模拟唯一键失败，不证明真实 MySQL 唯一约束、隔离或物理回滚。
 */
class RiskWeek6IdempotencyTest {
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final RiskReportRepository reports = mock(RiskReportRepository.class);
    private final FeatureContextService features = mock(FeatureContextService.class);
    private final TwoStageRiskOrchestrator orchestrator = mock(TwoStageRiskOrchestrator.class);
    private final RiskAssessmentDedupService dedup = mock(RiskAssessmentDedupService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ObjectMapper mapper = spy(new ObjectMapper().findAndRegisterModules());
    private final AtomicReference<RiskReport> managed = new AtomicReference<>();
    private final AtomicReference<String> flushedJson = new AtomicReference<>();
    private final User user = new User();
    private final LoanApplication application = new LoanApplication();
    private RiskService service;

    @BeforeEach
    void setup() {
        user.setId(2L);
        user.setCreditScore(720);
        user.setUserStatus(1);
        application.setId(101L);
        application.setUser(user);
        application.setLoanAmount(new BigDecimal("10000"));
        application.setLoanTerm(12);
        when(applications.findById(101L)).thenReturn(Optional.of(application));
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(orchestrator.assess(eq(user), eq(application), anyList()))
                .thenReturn(result("APPROVED", List.of("WOE")));
        when(reports.findForAssessment(application.getId())).thenAnswer(i -> Optional.ofNullable(managed.get()));
        when(reports.save(any())).thenAnswer(i -> {
            RiskReport entity = i.getArgument(0);
            if (entity.getId() == null) entity.setId(77L);
            managed.set(entity);
            return entity;
        });
        doAnswer(i -> {
            flushedJson.set(managed.get().getDetailsJson());
            return null;
        }).when(reports).flush();
        when(reports.findByLoanApplication_Id(101L)).thenAnswer(i -> {
            if (flushedJson.get() == null) return Optional.empty();
            RiskReport loaded = new RiskReport();
            loaded.setId(77L);
            loaded.setDetailsJson(flushedJson.get());
            return Optional.of(loaded);
        });
        when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        RiskServiceImpl target = new RiskServiceImpl(List.of(), applications, users, reports,
                features, new CurrentRiskFeatures(), mapper, products, orchestrator, dedup,
                new RiskReportAssembler(), mock(RiskDecisionEventService.class));
        ProxyFactory proxy = new ProxyFactory(target);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        service = (RiskService) proxy.getProxy();
    }

    private RiskAssessmentRequest request() {
        RiskAssessmentRequest request = new RiskAssessmentRequest();
        request.setApplicationId(101L);
        return request;
    }

    private TwoStageRiskOrchestrator.Result result(String decision, List<String> models) {
        return new TwoStageRiskOrchestrator.Result("CREDIT", "PASS", decision, decision,
                List.of(), List.of(), Map.of("score", 80, "maxScore", 100, "version", "test-v1"),
                Map.of("credit_score", 720, "user_status", 1, "enabledModels", models),
                LocalDateTime.of(2026, 10, 13, 12, 0));
    }

    @Test
    void firstAssessmentFlushesCompleteJsonWithGeneratedId() throws Exception {
        RiskReportDTO report = service.assess(request());
        assertEquals(77L, report.getReportId());
        assertSame(application, managed.get().getLoanApplication());
        assertEquals(mapper.readTree(mapper.writeValueAsString(report)), mapper.readTree(flushedJson.get()));
        assertEquals(77L, mapper.readTree(flushedJson.get()).path("reportId").asLong());
        verify(reports).save(any());
        verify(reports).flush();
        verify(transactions).commit(any());
        verify(transactions, never()).rollback(any());
    }

    @Test
    void sequentialReassessmentKeepsIdAndReadsUpdatedModelSelection() throws Exception {
        RiskReportDTO first = service.assess(request());
        RiskReport entity = managed.get();
        when(orchestrator.assess(user, application, List.of("EXTERNAL")))
                .thenReturn(result("REJECTED", List.of("EXTERNAL")));
        RiskAssessmentRequest next = request();
        next.setEnabledModels(List.of("EXTERNAL"));
        RiskReportDTO second = service.assess(next);
        assertSame(entity, managed.get());
        assertEquals(first.getReportId(), second.getReportId());
        assertEquals("REJECTED", second.getDecision());
        assertFalse(entity.getPassed());
        assertEquals(mapper.readTree(mapper.writeValueAsString(second)), mapper.readTree(flushedJson.get()));
        assertEquals(mapper.valueToTree(second),
                mapper.valueToTree(service.getRiskReportDTOByApplicationId(101L)));
        verify(orchestrator).assess(user, application, List.of("WOE"));
        verify(orchestrator).assess(user, application, List.of("EXTERNAL"));
        verify(reports, times(2)).save(any());
    }

    @Test
    void serializationFailureAfterIdAllocationRollsBackInsteadOfCommitting() throws Exception {
        doThrow(new JsonProcessingException("test serialization failure") { })
                .when(mapper).writeValueAsString(any(RiskReportDTO.class));
        BusinessException error = assertThrows(BusinessException.class, () -> service.assess(request()));
        assertEquals("RISK_REPORT_SERIALIZATION_FAILED", error.getCode());
        verify(reports).save(any()); // 未提交的 ID 分配，不等同于成功保存报告。
        verify(reports, never()).flush();
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        assertNull(flushedJson.get());
    }

    @Test
    void serializationFailureOnUpdateDoesNotReplacePreviousJson() throws Exception {
        service.assess(request());
        String previous = flushedJson.get();
        clearInvocations(transactions, reports);
        doThrow(new JsonProcessingException("test serialization failure") { })
                .when(mapper).writeValueAsString(any(RiskReportDTO.class));
        assertThrows(BusinessException.class, () -> service.assess(request()));
        assertEquals(previous, managed.get().getDetailsJson());
        assertEquals(previous, flushedJson.get());
        verify(reports, never()).save(any());
        verify(reports, never()).flush();
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void redisComputationHitStillPersistsCurrentApplicationReport() throws Exception {
        RiskAssessmentRequest request = request();
        request.setProductId(7L);
        when(products.existsById(7L)).thenReturn(true);
        when(dedup.assess(eq(2L), eq(7L), eq(application.getLoanAmount()), eq(List.of("WOE")), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(result("APPROVED", List.of("WOE")), true));
        RiskReportDTO report = service.assess(request);
        assertEquals(true, ((Map<?, ?>) report.getBreakdown().get("dedup")).get("reused"));
        assertEquals(101L, report.getApplicationId());
        assertEquals(77L, report.getReportId());
        assertEquals(mapper.readTree(mapper.writeValueAsString(report)), mapper.readTree(flushedJson.get()));
        verifyNoInteractions(orchestrator, features);
        verify(reports).save(any());
        verify(reports).flush();
        verify(transactions).commit(any());
    }

    @Test
    void flushConstraintFailureRollsBackWithoutReadingAReplacementReport() {
        doThrow(new DataIntegrityViolationException("constraint violation at flush")).when(reports).flush();
        BusinessException error = assertThrows(BusinessException.class, () -> service.assess(request()));
        assertEquals("RISK_REPORT_SAVE_CONFLICT", error.getCode());
        verify(reports).findForAssessment(application.getId());
        verify(reports, never()).findByLoanApplication_Id(anyLong());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void concurrentFirstInsertConflictIsNotReportedAsSuccess() throws Exception {
        CyclicBarrier bothReadMissing = new CyclicBarrier(2);
        AtomicReference<RiskReport> inserted = new AtomicReference<>();
        when(reports.findForAssessment(application.getId())).thenAnswer(i -> {
            bothReadMissing.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });
        doAnswer(i -> {
            RiskReport entity = i.getArgument(0);
            if (!inserted.compareAndSet(null, entity)) {
                throw new DataIntegrityViolationException("duplicate application_id (simulated)");
            }
            entity.setId(77L);
            managed.set(entity);
            return entity;
        }).when(reports).save(any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> assess = () -> {
                try {
                    return service.assess(request());
                } catch (BusinessException exception) {
                    return exception;
                }
            };
            Future<Object> first = executor.submit(assess);
            Future<Object> second = executor.submit(assess);
            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(RiskReportDTO.class::isInstance).count());
            BusinessException failure = outcomes.stream().filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast).findFirst().orElseThrow();
            assertEquals("RISK_REPORT_SAVE_CONFLICT", failure.getCode());
            assertEquals(77L, mapper.readTree(flushedJson.get()).path("reportId").asLong());
            verify(reports, times(2)).findForAssessment(application.getId());
            verify(reports, times(2)).save(any());
            verify(reports).flush();
            verify(reports, never()).findByLoanApplication_Id(anyLong());
            verify(transactions).commit(any());
            verify(transactions).rollback(any());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
