package com.example.zongshe1.modules.risk.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.common.exception.BusinessException;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.ops.mq.BusinessEventPublisher;
import com.example.zongshe1.modules.ops.mq.BusinessEventQueueConfig;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SmartTransactionObject;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction interception/synchronization with simulated storage, not a MySQL integration test. */
class RiskWeek6MqTest {
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RiskReportRepository reports = mock(RiskReportRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final TwoStageRiskOrchestrator orchestrator = mock(TwoStageRiskOrchestrator.class);
    private final RiskAssessmentDedupService dedup = mock(RiskAssessmentDedupService.class);
    private final BusinessEventPublisher publisher = mock(BusinessEventPublisher.class);
    private final ObjectMapper mapper = spy(new ObjectMapper().findAndRegisterModules());
    private final LifecycleTransactions transactions = new LifecycleTransactions();
    private final Map<Long, RiskReport> stored = new LinkedHashMap<>();
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final ListAppender<ILoggingEvent> eventLogs = new ListAppender<>();
    private final Logger eventLogger = (Logger) LoggerFactory.getLogger(RiskDecisionEventService.class);
    private final User user = new User();
    private final LoanApplication application = new LoanApplication();
    private RiskService service;
    private RiskServiceImpl target;
    private RiskDecisionEventService events;

    @BeforeEach
    void setup() {
        eventLogs.start();
        eventLogger.addAppender(eventLogs);
        user.setId(2L);
        user.setCreditScore(720);
        user.setUserStatus(1);
        application.setId(101L);
        application.setUser(user);
        application.setLoanAmount(new BigDecimal("10000"));
        application.setLoanTerm(12);
        when(applications.findById(101L)).thenReturn(Optional.of(application));
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(orchestrator.assess(eq(user), any(), anyList())).thenReturn(result("APPROVED"));
        when(reports.findForAssessment(anyLong())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(reports.findByLoanApplication_Id(anyLong())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(reports.findById(anyLong())).thenAnswer(i -> stored.values().stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(reports.save(any())).thenAnswer(i -> {
            RiskReport entity = i.getArgument(0);
            if (entity.getId() == null) entity.setId(77L + stored.size());
            stored.put(entity.getLoanApplication().getId(), entity);
            return entity;
        });
        when(publisher.publish(eq(BusinessEventQueueConfig.RISK_DECIDED), any())).thenAnswer(i -> {
            assertTrue(transactions.commits > 0, "physical commit must precede publishing");
            messages.add(i.getArgument(1));
            return true;
        });
        events = new RiskDecisionEventService(publisher, mapper);
        target = new RiskServiceImpl(List.of(), applications, users, reports,
                mock(FeatureContextService.class), new CurrentRiskFeatures(), mapper, products,
                orchestrator, dedup, new RiskReportAssembler(), events);
        ProxyFactory proxy = new ProxyFactory(target);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        service = (RiskService) proxy.getProxy();
    }

    @AfterEach
    void noLeakedTransactionContext() {
        eventLogger.detachAppender(eventLogs);
        eventLogs.stop();
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private RiskAssessmentRequest request() {
        RiskAssessmentRequest request = new RiskAssessmentRequest();
        request.setApplicationId(101L);
        return request;
    }

    private TwoStageRiskOrchestrator.Result result(String decision) {
        return new TwoStageRiskOrchestrator.Result("CREDIT", "PASS", decision, decision,
                List.of(), List.of(), Map.of("score", 80, "maxScore", 100, "version", "test-v1"),
                Map.of("credit_score", 720, "user_status", 1, "enabledModels", List.of("WOE")),
                LocalDateTime.of(2026, 10, 13, 12, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "MANUAL_REVIEW"})
    void firstDecisionMatchesPersistedReport(String decision) throws Exception {
        when(orchestrator.assess(eq(user), any(), anyList())).thenReturn(result(decision));
        Instant before = Instant.now();
        RiskReportDTO report = service.assess(request());
        assertEquals(1, messages.size());
        Map<String, Object> message = messages.get(0);
        RiskReportDTO persisted = mapper.readValue(stored.get(101L).getDetailsJson(), RiskReportDTO.class);
        assertEquals("risk.decided", message.get("event"));
        assertEquals(persisted.getApplicationId(), message.get("applicationId"));
        assertEquals(persisted.getReportId(), message.get("reportId"));
        assertEquals(persisted.getDecision(), message.get("decision"));
        assertEquals("APPROVED".equals(decision), message.get("passed"));
        assertTrue(message.containsKey("riskLevel"));
        assertNull(message.get("riskLevel"));
        assertFalse(Instant.parse((String) message.get("occurredAt")).isBefore(before));
        assertEquals(report, persisted);
        assertThrows(UnsupportedOperationException.class, () -> message.put("decision", "REJECTED"));
        verify(reports).flush();
    }

    @Test
    void unchangedDecisionUpdatesSameReportWithoutAnotherMessage() {
        RiskReportDTO first = service.assess(request());
        when(orchestrator.assess(eq(user), any(), anyList())).thenReturn(new TwoStageRiskOrchestrator.Result(
                "CREDIT", "PASS", "APPROVED", "updated details", List.of(), List.of(), Map.of(), Map.of(), LocalDateTime.now()));
        RiskReportDTO second = service.assess(request());
        assertEquals(first.getReportId(), second.getReportId());
        assertTrue(stored.get(101L).getDetailsJson().contains("updated details"));
        verify(reports, times(2)).save(any());
        verify(reports, times(2)).flush();
        assertEquals(1, messages.size());
    }

    @Test
    void changedDecisionIncludingReturnToOriginalNotifiesAgain() {
        for (String decision : List.of("APPROVED", "REJECTED", "APPROVED")) {
            when(orchestrator.assess(eq(user), any(), anyList())).thenReturn(result(decision));
            service.assess(request());
        }
        assertEquals(List.of("APPROVED", "REJECTED", "APPROVED"), messages.stream().map(m -> m.get("decision")).toList());
        assertEquals(1, stored.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "broken json", "{\"decision\":\"PASS\"}", "{\"decision\":true}"})
    void unknownPreviousDecisionDoesNotSuppressNotification(String json) {
        RiskReport old = new RiskReport();
        old.setId(77L);
        old.setDetailsJson(json);
        stored.put(101L, old);
        service.assess(request());
        assertEquals(1, messages.size());
    }

    @Test
    void outerCommitControlsPublishingAndPayloadIsSnapshot() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            RiskReportDTO report = service.assess(request());
            verifyNoInteractions(publisher);
            assertEquals(0, transactions.commits);
            report.setDecision("REJECTED");
            report.setApplicationId(999L);
            report.getBreakdown().clear();
        });
        assertEquals(1, transactions.commits);
        assertEquals("APPROVED", messages.get(0).get("decision"));
        assertEquals(101L, messages.get(0).get("applicationId"));
    }

    @Test
    void outerRollbackOnlyDoesNotPublish() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.assess(request());
            status.setRollbackOnly();
        });
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(publisher);
    }

    @Test
    void outerExceptionDoesNotPublish() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.assess(request());
            throw new IllegalStateException("outer failure");
        }));
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(publisher);
    }

    @Test
    void commitFailureDoesNotPublish() {
        transactions.failCommit = true;
        assertThrows(TransactionSystemException.class, () -> service.assess(request()));
        verifyNoInteractions(publisher);
    }

    @Test
    void saveFailureDoesNotPublish() {
        doThrow(new DataIntegrityViolationException("duplicate application")).when(reports).save(any());
        assertThrows(BusinessException.class, () -> service.assess(request()));
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(publisher);
    }

    @Test
    void flushFailureDoesNotPublish() {
        doThrow(new DataIntegrityViolationException("flush failure")).when(reports).flush();
        assertThrows(BusinessException.class, () -> service.assess(request()));
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(publisher);
    }

    @Test
    void serializationFailureDoesNotPublish() throws Exception {
        doThrow(new JsonProcessingException("serialization failure") { })
                .when(mapper).writeValueAsString(any(RiskReportDTO.class));
        assertThrows(BusinessException.class, () -> service.assess(request()));
        assertEquals(1, transactions.rollbacks);
        verify(reports, never()).flush();
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishFailureKeepsCommittedReport(boolean throwsException) throws Exception {
        if (throwsException) doThrow(new IllegalStateException("broker down")).when(publisher).publish(anyString(), any());
        else doReturn(false).when(publisher).publish(anyString(), any());
        RiskReportDTO report = assertDoesNotThrow(() -> service.assess(request()));
        assertEquals(report, mapper.readValue(stored.get(101L).getDetailsJson(), RiskReportDTO.class));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        String failureLog = eventLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("status=FAILED")).findFirst().orElseThrow();
        assertTrue(failureLog.contains(throwsException ? "reason=PUBLISH_EXCEPTION" : "reason=PUBLISH_RETURNED_FALSE"));
        assertTrue(failureLog.contains("routingKey=risk.decided applicationId=101 reportId=77 decision=APPROVED occurredAt="));
        // This minimal design deliberately has no automatic retry for an unchanged decision.
        service.assess(request());
        verify(publisher).publish(eq(BusinessEventQueueConfig.RISK_DECIDED), any());
    }

    @Test
    void replayAndGetReportNeverPublish() {
        service.assess(request());
        clearInvocations(publisher);
        service.replay(77L);
        service.getRiskReportDTOByApplicationId(101L);
        verifyNoInteractions(publisher);
    }

    @Test
    void noTransactionNeverPublishesImmediately() {
        target.assess(request());
        verifyNoInteractions(publisher);
        assertTrue(eventLogs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("status=SKIPPED_NO_TRANSACTION")));
    }

    @Test
    void caughtInnerFailureStillRollsBackOuterTransactionAndPendingEvent() {
        assertThrows(UnexpectedRollbackException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.assess(request());
            doThrow(new DataIntegrityViolationException("second assessment fails")).when(reports).flush();
            assertThrows(BusinessException.class, () -> service.assess(request()));
        }));
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(publisher);
    }

    @Test
    void synchronizationAloneIsNotATransaction() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            target.assess(request());
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            verifyNoInteractions(publisher);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void activeTransactionFlagWithoutSynchronizationNeverPublishes() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            target.assess(request());
            verifyNoInteractions(publisher);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void redisHitForDifferentApplicationsStillNotifiesEachApplication() {
        when(products.existsById(7L)).thenReturn(true);
        when(dedup.assess(eq(2L), eq(7L), any(), anyList(), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(result("APPROVED"), true));
        LoanApplication other = new LoanApplication();
        other.setId(102L);
        other.setUser(user);
        other.setLoanAmount(application.getLoanAmount());
        other.setLoanTerm(12);
        when(applications.findById(102L)).thenReturn(Optional.of(other));
        RiskAssessmentRequest request = request();
        request.setProductId(7L);
        service.assess(request);
        request.setApplicationId(102L);
        service.assess(request);
        assertEquals(List.of(101L, 102L), messages.stream().map(m -> m.get("applicationId")).toList());
        verifyNoInteractions(orchestrator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WOE", "EXTERNAL", "AHP", "MANUAL_REVIEW"})
    void onlyExistingFinalWoeLevelIsUsed(String mode) {
        RiskReportDTO report = new RiskReportAssembler().assemble(result(
                "MANUAL_REVIEW".equals(mode) ? mode : "APPROVED"), 77L, 101L, 2L);
        report.getBreakdown().put("fusionMode", "AHP".equals(mode) ? "AHP" : "SINGLE");
        report.getBreakdown().put("credit", Map.of("status", "COMPLETED", "modelName",
                "MANUAL_REVIEW".equals(mode) ? "WOE" : mode, "decision", report.getDecision()));
        report.getBreakdown().put("ruleResults", List.of(Map.of("stage", "CREDIT", "riskLevel", 2)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.afterReportPersisted(null, report));
        assertEquals("WOE".equals(mode) ? Integer.valueOf(2) : null, messages.get(0).get("riskLevel"));
    }

    /** Spring owns synchronization, participation, callbacks and cleanup; only physical storage is simulated. */
    private static class LifecycleTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Tx> current = new ThreadLocal<>();
        int commits;
        int rollbacks;
        boolean failCommit;

        @Override protected Object doGetTransaction() {
            Tx tx = current.get();
            return tx == null ? new Tx() : tx;
        }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Tx) transaction).active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            Tx tx = (Tx) transaction;
            tx.active = true;
            current.set(tx);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("simulated commit failure");
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((Tx) status.getTransaction()).rollbackOnly = true;
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((Tx) transaction).active = false;
            current.remove();
        }
        private static class Tx implements SmartTransactionObject {
            boolean active;
            boolean rollbackOnly;
            @Override public boolean isRollbackOnly() { return rollbackOnly; }
            @Override public void flush() { }
        }
    }
}
