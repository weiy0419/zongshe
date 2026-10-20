package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.ops.mq.BusinessEventPublisher;
import com.example.zongshe1.modules.product.repository.ProductRepository;
import com.example.zongshe1.modules.risk.entity.RiskReport;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureContextService;
import com.example.zongshe1.modules.risk.repository.RiskReportRepository;
import com.example.zongshe1.modules.risk.service.impl.RiskServiceImpl;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real Hibernate/JPA commits against a private H2 memory database; no Boot configuration,
 * Redis or broker connections. Supply H2 without changing the project dependencies:
 * mvn "-Dtest=RiskSnapshotJpaTest" "-Drisk.jpa.test=true"
 *     "-Dmaven.test.additionalClasspath=/absolute/path/h2-2.2.224.jar" test
 * Without the explicit opt-in this suite is skipped, not silently run against a configured database.
 */
@EnabledIfSystemProperty(named = "risk.jpa.test", matches = "true")
class RiskSnapshotJpaTest {
    private SessionFactory factory;
    private EntityManager em;
    private TransactionTemplate transactions;
    private RiskReportRepository reports;
    private RiskServiceImpl service;
    private Long applicationId;
    private Long userId;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RiskAssessmentDedupService dedup = mock(RiskAssessmentDedupService.class);
    private final TwoStageRiskOrchestrator orchestrator = mock(TwoStageRiskOrchestrator.class);
    private final BusinessEventPublisher publisher = mock(BusinessEventPublisher.class);

    @BeforeEach
    void setup() {
        factory = new Configuration()
                .addAnnotatedClass(RiskReport.class)
                .addAnnotatedClass(LoanApplication.class)
                .addAnnotatedClass(User.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:risk_" + UUID.randomUUID())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true")
                .setProperty("hibernate.session.events.log", "false")
                .buildSessionFactory();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        transactions = new TransactionTemplate(new JpaTransactionManager(factory));
        // Observe calls while delegating every operation to the real Spring Data repository.
        reports = mock(RiskReportRepository.class,
                delegatesTo(new JpaRepositoryFactory(em).getRepository(RiskReportRepository.class)));
        transactions.executeWithoutResult(status -> {
            User user = new User();
            user.setCreditScore(720);
            user.setUserStatus(1);
            em.persist(user);
            userId = user.getId();
            applicationId = persistApplication(user, "first").getId();
        });

        LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        when(applications.findById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(em.find(LoanApplication.class, i.getArgument(0))));
        when(users.findById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(em.find(User.class, i.getArgument(0))));
        when(products.existsById(7L)).thenReturn(true);
        when(orchestrator.revalidate(any(), any(), anyList(), any())).thenAnswer(i -> i.getArgument(3));
        when(publisher.publish(anyString(), any())).thenReturn(true);
        service = new RiskServiceImpl(List.of(), applications, users, reports,
                mock(FeatureContextService.class), new CurrentRiskFeatures(), mapper, products,
                orchestrator, dedup, new RiskReportAssembler(), new RiskDecisionEventService(publisher, mapper));
    }

    @AfterEach
    void close() {
        if (factory != null) factory.close();
    }

    private LoanApplication persistApplication(User user, String number) {
        LoanApplication application = new LoanApplication();
        application.setUser(user);
        application.setApplicationNo(number);
        application.setApplicantName("test");
        application.setIdCardNumber("test-id");
        application.setPhoneNumber("13000000000");
        application.setLoanType("test");
        application.setLoanAmount(new BigDecimal("10000"));
        application.setLoanTerm(12);
        em.persist(application);
        return application;
    }

    private RiskAssessmentRequest request(Long id, String model) {
        RiskAssessmentRequest request = new RiskAssessmentRequest();
        request.setApplicationId(id);
        request.setProductId(7L);
        request.setEnabledModels(List.of(model));
        return request;
    }

    private TwoStageRiskOrchestrator.Result result(String model, int score) {
        return new TwoStageRiskOrchestrator.Result("CREDIT", "PASS", "REJECTED", "test",
                List.of(), List.of(), Map.of("score", score, "maxScore", 100, "version", "test"),
                Map.of("credit_score", 720, "user_status", 1, "enabledModels", List.of(model)),
                LocalDateTime.of(2026, 10, 13, 12, 0));
    }

    private void cacheSequence(String model, int score) {
        when(dedup.assess(eq(userId), eq(7L), any(), eq(List.of(model)), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(result(model, score), false),
                        new RiskAssessmentDedupService.Lookup(result(model, score), true));
    }

    private RiskReportDTO assess(RiskAssessmentRequest request) {
        return transactions.execute(status -> service.assess(request));
    }

    private String storedJson(Long id) {
        // Each invocation has a fresh persistence context after the previous physical commit.
        return transactions.execute(status -> em.find(RiskReport.class, id).getDetailsJson());
    }

    @Test
    void repeatedHitCommitsNoEntityUpdateAndReplayKeepsOriginalSnapshot() throws Exception {
        cacheSequence("WOE", 58);
        RiskAssessmentRequest request = request(applicationId, "WOE");
        RiskReportDTO first = assess(request);
        String original = storedJson(first.getReportId());
        assertFalse(mapper.readTree(original).at("/breakdown/dedup/reused").asBoolean());
        verify(publisher).publish(eq("risk.decided"), argThat(payload ->
                payload instanceof Map<?, ?> values
                        && applicationId.equals(values.get("applicationId"))
                        && "REJECTED".equals(values.get("decision"))));
        factory.getStatistics().clear();
        clearInvocations(reports, publisher);

        RiskReportDTO repeated = assess(request);
        assertEquals(true, ((Map<?, ?>) repeated.getBreakdown().get("dedup")).get("reused"));
        assertEquals(first.getReportId(), repeated.getReportId());
        assertEquals(first.getTotalScore(), repeated.getTotalScore());
        assertEquals(first.getDecision(), repeated.getDecision());
        assertEquals(first.getAssessedAt(), repeated.getAssessedAt());
        assertEquals(original, storedJson(first.getReportId()));
        Long reportCount = transactions.execute(status -> em.createQuery(
                "select count(r) from RiskReport r where r.loanApplication.id = :id", Long.class)
                .setParameter("id", applicationId).getSingleResult());
        assertEquals(1L, reportCount);
        assertEquals(0, factory.getStatistics().getEntityUpdateCount(), "commit must not dirty any entity");
        assertEquals(0, factory.getStatistics().getEntityInsertCount());
        verify(reports, never()).save(any());
        verify(reports, never()).flush();
        verifyNoInteractions(publisher);

        RiskReportDTO replay = transactions.execute(status -> service.replay(first.getReportId()));
        var replayTree = mapper.readTree(mapper.writeValueAsString(replay));
        ((com.fasterxml.jackson.databind.node.ObjectNode) replayTree.path("breakdown"))
                .remove(List.of("replay", "replaySourceReportId", "replayMode"));
        assertEquals(mapper.readTree(original), replayTree);
        repeated.getFeatureSnapshot().getExtra().clear();
        repeated.getBreakdown().clear();
        assertEquals(original, storedJson(first.getReportId()));
        assertEquals(first, transactions.execute(status -> service.getRiskReportDTOByApplicationId(applicationId)));
        verifyNoInteractions(publisher);
    }

    @Test
    void differentApplicationCacheHitCreatesItsOwnCommittedReport() {
        cacheSequence("WOE", 58);
        RiskReportDTO first = assess(request(applicationId, "WOE"));
        String original = storedJson(first.getReportId());
        Long otherId = transactions.execute(status ->
                persistApplication(em.find(User.class, userId), "second").getId());
        RiskReportDTO other = assess(request(otherId, "WOE"));
        assertNotEquals(first.getReportId(), other.getReportId());
        assertEquals(otherId, other.getApplicationId());
        assertEquals(true, ((Map<?, ?>) other.getBreakdown().get("dedup")).get("reused"));
        assertEquals(original, storedJson(first.getReportId()));
        assertNotNull(storedJson(other.getReportId()));
        Long reportCount = transactions.execute(status -> reports.count());
        assertEquals(2L, reportCount);
        verify(publisher, times(2)).publish(eq("risk.decided"), any());
    }

    @Test
    void changedModelsEvenWithCacheHitUpdateCurrentReport() {
        cacheSequence("WOE", 58);
        RiskReportDTO first = assess(request(applicationId, "WOE"));
        String original = storedJson(first.getReportId());
        when(dedup.assess(eq(userId), eq(7L), any(), eq(List.of("EXTERNAL")), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(result("EXTERNAL", 58), true));
        RiskReportDTO changed = assess(request(applicationId, "EXTERNAL"));
        assertEquals(first.getReportId(), changed.getReportId());
        assertEquals(List.of("EXTERNAL"), changed.getBreakdown().get("enabledModels"));
        assertNotEquals(original, storedJson(first.getReportId()));
        verify(reports, times(2)).save(any());
        verify(reports, times(2)).flush();
    }

    @Test
    void liveRevalidationVetoRetainsReassessmentEvenWithIdenticalOutput() {
        cacheSequence("WOE", 58);
        assess(request(applicationId, "WOE"));
        // A distinct result means live revalidation rejected reuse, even if all values match.
        when(orchestrator.revalidate(any(), any(), anyList(), any())).thenReturn(result("WOE", 58));
        RiskReportDTO changed = assess(request(applicationId, "WOE"));
        assertEquals(false, ((Map<?, ?>) changed.getBreakdown().get("dedup")).get("reused"));
        verify(reports, times(2)).save(any());
        verify(reports, times(2)).flush();
    }

    @Test
    void sameModelsButDifferentCachedAssessmentDoesNotReturnOldSnapshot() {
        cacheSequence("WOE", 58);
        RiskReportDTO first = assess(request(applicationId, "WOE"));
        when(dedup.assess(eq(userId), eq(7L), any(), eq(List.of("WOE")), any()))
                .thenReturn(new RiskAssessmentDedupService.Lookup(result("WOE", 59), true));
        RiskReportDTO changed = assess(request(applicationId, "WOE"));
        assertEquals(first.getReportId(), changed.getReportId());
        assertEquals(59, changed.getTotalScore());
        verify(reports, times(2)).save(any());
        verify(reports, times(2)).flush();
    }
}
