package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.repay.entity.RepayPlan;
import com.example.zongshe1.modules.repay.entity.RepaymentRecord;
import com.example.zongshe1.modules.repay.repository.RepayPlanRepository;
import com.example.zongshe1.modules.repay.repository.RepaymentRecordRepository;
import com.example.zongshe1.modules.risk.feature.BehaviorFeatureQueryService;
import com.example.zongshe1.modules.risk.service.scoring.BehaviorScoreEngine;
import com.example.zongshe1.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BehaviorFeatureQueryServiceTest {
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final RepayPlanRepository plans = mock(RepayPlanRepository.class);
    private final RepaymentRecordRepository records = mock(RepaymentRecordRepository.class);
    private final BehaviorFeatureQueryService query = new BehaviorFeatureQueryService(applications, plans, records);
    private static final String USER_ID = "BUSINESS-007";

    private RepayPlan plan(String status) {
        User user = new User(); user.setId(99L); user.setUserId(USER_ID);
        LoanApplication app = new LoanApplication(); app.setId(12L); app.setUser(user);
        RepayPlan plan = new RepayPlan(); plan.setId(21L); plan.setLoanApplication(app);
        plan.setStatus(status); plan.setTotalAmount(new BigDecimal("100.00"));
        plan.setPaidTotal(BigDecimal.ZERO); plan.setDueDate(LocalDate.now().plusDays(2));
        when(applications.findByUserIdOrderByApplyTimeDesc(USER_ID)).thenReturn(List.of(app));
        when(plans.findByLoanApplication_IdOrderByPeriodNoAsc(12L)).thenReturn(List.of(plan));
        return plan;
    }
    private RepaymentRecord payment(RepayPlan plan, long id, String amount) {
        RepaymentRecord record = new RepaymentRecord(); record.setId(id); record.setRepayPlan(plan);
        record.setLoanApplication(plan.getLoanApplication()); record.setAmount(new BigDecimal(amount));
        record.setRepayMethod("ONLINE"); record.setRepayTime(LocalDateTime.now().minusHours(1));
        return record;
    }
    private void payments(RepaymentRecord... values) {
        when(records.findByLoanApplication_User_UserIdOrderByRepayTimeDesc(USER_ID)).thenReturn(List.of(values));
    }
    @Test void noPlansOrPaymentsMeansNoHistoryAndUsesBusinessStringId() {
        var facts = query.query(USER_ID);
        assertEquals(DataQuality.NO_HISTORY, facts.quality());
        assertEquals(0, facts.historicalOverduePeriods());
        verify(applications).findByUserIdOrderByApplyTimeDesc(USER_ID);
        verify(records).findByLoanApplication_User_UserIdOrderByRepayTimeDesc(USER_ID);
        verifyNoMoreInteractions(applications, records);
        verifyNoInteractions(plans);
    }
    @Test void currentOverdueDoesNotClaimHistoricalTotal() {
        var plan = plan("OVERDUE"); plan.setDueDate(LocalDate.now().minusDays(3));
        var facts = query.query(USER_ID);
        assertEquals(1, facts.currentOverduePeriods());
        assertNull(facts.historicalOverduePeriods());
        assertEquals(DataQuality.PARTIAL, facts.quality());
        assertEquals(35, new BehaviorScoreEngine().evaluate(facts).score());
    }
    @Test void soleOnTimeFullPaymentHasDirectAmountEvidence() {
        var plan = plan("PAID"); plan.setPaidTotal(new BigDecimal("100"));
        payments(payment(plan, 31L, "100"));
        var facts = query.query(USER_ID);
        assertEquals(LatestPayment.FULL, facts.latestPayment());
        assertEquals(31L, facts.latestRecordId());
        assertEquals(21L, facts.latestPlanId());
        assertEquals(DataQuality.PARTIAL, facts.quality()); // historical total still unknown
        verify(plans, never()).save(any());
        verify(records, never()).save(any());
    }
    @Test void solePartialPaymentIsNotFull() {
        var plan = plan("PENDING"); plan.setPaidTotal(new BigDecimal("40"));
        payments(payment(plan, 31L, "40"));
        assertEquals(LatestPayment.NOT_FULL, query.query(USER_ID).latestPayment());
    }
    @Test void cumulativeSettlementCannotProveLatestSinglePaymentFull() {
        var plan = plan("PAID"); plan.setPaidTotal(new BigDecimal("100"));
        var earlier = payment(plan, 30L, "40"); earlier.setRepayTime(LocalDateTime.now().minusDays(1));
        payments(payment(plan, 31L, "60"), earlier);
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
    }
    @Test void overdueThenSettledDoesNotBecomeZeroHistoricalOverdue() {
        var plan = plan("PAID"); plan.setDueDate(LocalDate.now().minusDays(3));
        plan.setPaidTotal(new BigDecimal("100")); payments(payment(plan, 31L, "100"));
        var facts = query.query(USER_ID);
        assertEquals(0, facts.currentOverduePeriods());
        assertNull(facts.historicalOverduePeriods());
        assertEquals(LatestPayment.UNKNOWN, facts.latestPayment());
    }
    @Test void adminPaymentCannotBeUsedAsExactIncrementalAmount() {
        var plan = plan("PAID"); plan.setPaidTotal(new BigDecimal("100"));
        var record = payment(plan, 31L, "100"); record.setRepayMethod("ADMIN"); payments(record);
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
    }
    @Test void missingAmountAndDueDateRemainUnknown() {
        var plan = plan("PAID"); plan.setDueDate(null);
        var record = payment(plan, 31L, "100"); record.setAmount(null); payments(record);
        var facts = query.query(USER_ID);
        assertEquals(LatestPayment.UNKNOWN, facts.latestPayment());
        assertEquals(DataQuality.PARTIAL, facts.quality());
    }
    @Test void unknownPaymentMethodIsNotAssumedOrdinary() {
        var plan = plan("PAID"); plan.setPaidTotal(new BigDecimal("100"));
        var record = payment(plan, 31L, "100"); record.setRepayMethod(null); payments(record);
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
    }
    @Test void olderOrphanRecordPreventsClaimingSolePaymentEvidence() {
        var plan = plan("PAID"); plan.setPaidTotal(new BigDecimal("100"));
        var orphan = new RepaymentRecord(); orphan.setRepayTime(LocalDateTime.now().minusDays(1));
        payments(payment(plan, 31L, "100"), orphan);
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
    }
    @Test void contradictoryAmountCannotEarnFullPaymentBonus() {
        var plan = plan("PAID"); plan.setPaidTotal(new BigDecimal("120"));
        payments(payment(plan, 31L, "120"));
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
    }
    @Test void missingPaymentTimestampCannotPickAnotherAsLatest() {
        var plan = plan("PAID"); var record = payment(plan, 31L, "100");
        record.setRepayTime(null); payments(record);
        assertNull(query.query(USER_ID).latestRecordId());
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
    }
    @Test void equalTimestampsAreAmbiguousRatherThanOrderedById() {
        var plan = plan("PAID"); var a = payment(plan, 31L, "50"); var b = payment(plan, 32L, "50");
        b.setRepayTime(a.getRepayTime()); payments(a, b);
        assertEquals(LatestPayment.UNKNOWN, query.query(USER_ID).latestPayment());
        assertNull(query.query(USER_ID).latestRecordId());
    }
    @Test void orphanPaymentIsPartialNotNewUser() {
        var record = new RepaymentRecord(); record.setRepayTime(LocalDateTime.now().minusHours(1)); payments(record);
        assertEquals(DataQuality.PARTIAL, query.query(USER_ID).quality());
    }
    @Test void invalidAssociationDoesNotClaimEmptyHistory() {
        var plan = plan("PENDING"); plan.getLoanApplication().getUser().setUserId("OTHER");
        assertEquals(DataQuality.PARTIAL, query.query(USER_ID).quality());
        assertNull(query.query(USER_ID).currentOverduePeriods());
    }
    @Test void queryExceptionIsErrorWithoutScore() {
        when(applications.findByUserIdOrderByApplyTimeDesc(USER_ID)).thenThrow(new IllegalStateException("database unavailable"));
        var facts = query.query(USER_ID);
        assertEquals(DataQuality.ERROR, facts.quality());
        assertNull(new BehaviorScoreEngine().evaluate(facts).score());
        verifyNoInteractions(plans, records);
    }
    @Test void failureAfterReadingPlansDiscardsPartialSuccess() {
        plan("OVERDUE");
        when(records.findByLoanApplication_User_UserIdOrderByRepayTimeDesc(USER_ID)).thenThrow(new IllegalStateException());
        var facts = query.query(USER_ID);
        assertEquals(DataQuality.ERROR, facts.quality());
        assertNull(facts.currentOverduePeriods());
    }
    @Test void invalidUserIdFailsBeforeQueryAndAdapterIsReadOnly() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> query.query(" "));
        verifyNoInteractions(applications, plans, records);
        assertTrue(BehaviorFeatureQueryService.class.getMethod("query", String.class)
                .getAnnotation(Transactional.class).readOnly());
    }
}
