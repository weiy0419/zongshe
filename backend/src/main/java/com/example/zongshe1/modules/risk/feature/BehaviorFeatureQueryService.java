package com.example.zongshe1.modules.risk.feature;

import com.example.zongshe1.modules.loanapplication.repository.LoanApplicationRepository;
import com.example.zongshe1.modules.repay.entity.RepayPlan;
import com.example.zongshe1.modules.repay.entity.RepaymentRecord;
import com.example.zongshe1.modules.repay.repository.RepayPlanRepository;
import com.example.zongshe1.modules.repay.repository.RepaymentRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.example.zongshe1.modules.risk.feature.BehaviorFeatureSnapshot.*;

/**
 * Live observation only, not an as-of/replay query. Existing repositories use String User.userId.
 * Caller supplies an existing business user. No plans/payments means no repayment history,
 * not proof that the user exists. Historical totals remain unknown for every nonempty history:
 * mutable plans lack original obligations and immutable overdue/settlement events.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BehaviorFeatureQueryService {
    private final LoanApplicationRepository applications;
    private final RepayPlanRepository plans;
    private final RepaymentRecordRepository records;

    @Transactional(readOnly = true)
    public BehaviorFeatureSnapshot query(String businessUserId) {
        if (businessUserId == null || businessUserId.isBlank()) throw new IllegalArgumentException("Business user ID required");
        LocalDateTime observedAt = LocalDateTime.now();
        try {
            Map<Long, RepayPlan> byId = new LinkedHashMap<>();
            List<String> evidence = new ArrayList<>();
            boolean valid = true;
            for (var application : applications.findByUserIdOrderByApplyTimeDesc(businessUserId)) {
                if (application.getId() == null || application.getUser() == null
                        || !businessUserId.equals(application.getUser().getUserId())) {
                    valid = false;
                    continue;
                }
                for (RepayPlan plan : plans.findByLoanApplication_IdOrderByPeriodNoAsc(application.getId())) {
                    if (plan.getId() == null || plan.getLoanApplication() == null
                            || !application.getId().equals(plan.getLoanApplication().getId())
                            || byId.putIfAbsent(plan.getId(), plan) != null) valid = false;
                }
            }
            List<RepaymentRecord> payments = records.findByLoanApplication_User_UserIdOrderByRepayTimeDesc(businessUserId);
            if (valid && byId.isEmpty() && payments.isEmpty())
                return new BehaviorFeatureSnapshot(businessUserId, observedAt, DataQuality.NO_HISTORY,
                        0, 0, null, null, LatestPayment.NONE, List.of("NO_PLANS_OR_PAYMENTS"));

            int overdue = 0;
            for (RepayPlan plan : byId.values()) {
                if (!Set.of("PENDING", "PAID", "OVERDUE").contains(Objects.toString(plan.getStatus(), ""))) valid = false;
                if ("OVERDUE".equals(plan.getStatus())) overdue++;
            }
            evidence.add("CURRENT_OVERDUE_IS_STATUS_COUNT_NOT_HISTORICAL_TOTAL");
            evidence.add("HISTORICAL_OVERDUE_UNKNOWN_NO_IMMUTABLE_HISTORY");
            LatestPayment latestPayment = payments.isEmpty() ? LatestPayment.NONE : LatestPayment.UNKNOWN;
            Long recordId = null, planId = null;
            boolean ordered = payments.stream().allMatch(p -> p.getRepayTime() != null
                    && !p.getRepayTime().isAfter(observedAt));
            boolean paymentLinksValid = payments.stream().allMatch(p -> p.getId() != null
                    && p.getRepayPlan() != null && byId.containsKey(p.getRepayPlan().getId())
                    && p.getLoanApplication() != null
                    && byId.get(p.getRepayPlan().getId()).getLoanApplication().getId()
                        .equals(p.getLoanApplication().getId()));
            if (!paymentLinksValid) evidence.add("PAYMENT_ASSOCIATIONS_INCOMPLETE");
            if (!payments.isEmpty() && ordered) {
                RepaymentRecord latest = payments.stream().max(Comparator.comparing(RepaymentRecord::getRepayTime)).orElseThrow();
                long ties = payments.stream().filter(p -> p.getRepayTime().equals(latest.getRepayTime())).count();
                if (ties == 1) {
                    recordId = latest.getId();
                    planId = latest.getRepayPlan() == null ? null : latest.getRepayPlan().getId();
                    RepayPlan plan = byId.get(planId);
                    if (valid && paymentLinksValid && plan != null && latest.getLoanApplication() != null
                            && plan.getLoanApplication().getId().equals(latest.getLoanApplication().getId())) {
                        latestPayment = classify(latest, plan, payments, evidence);
                    } else evidence.add("LATEST_PAYMENT_ASSOCIATION_UNKNOWN");
                } else evidence.add("LATEST_PAYMENT_TIME_TIE_UNKNOWN");
            } else if (!payments.isEmpty()) evidence.add("PAYMENT_TIME_MISSING_OR_FUTURE");
            if (!valid) evidence.add("INCOMPLETE_OR_AMBIGUOUS_PLAN_DATA");
            return new BehaviorFeatureSnapshot(businessUserId, observedAt, DataQuality.PARTIAL,
                    null, valid ? overdue : null, recordId, planId, latestPayment, evidence);
        } catch (RuntimeException exception) {
            log.warn("Behavior evidence query failed: {}", exception.getClass().getSimpleName());
            return new BehaviorFeatureSnapshot(businessUserId, observedAt, DataQuality.ERROR,
                    null, null, null, null, LatestPayment.UNKNOWN, List.of("BEHAVIOR_QUERY_FAILED"));
        }
    }

    private LatestPayment classify(RepaymentRecord record, RepayPlan plan,
                                   List<RepaymentRecord> payments, List<String> evidence) {
        // Only a sole ordinary payment, reconciled to the current plan and made before due,
        // is usable. Multiple payments / ADMIN / overdue amounts lack original obligation evidence.
        long count = payments.stream().filter(p -> p.getRepayPlan() != null
                && plan.getId().equals(p.getRepayPlan().getId())).count();
        if (count != 1 || !Set.of("ONLINE", "BANK_TRANSFER").contains(Objects.toString(record.getRepayMethod(), ""))
                || record.getAmount() == null || record.getAmount().signum() <= 0
                || plan.getTotalAmount() == null || plan.getTotalAmount().signum() <= 0
                || plan.getPaidTotal() == null || plan.getPaidTotal().compareTo(record.getAmount()) != 0
                || plan.getDueDate() == null || record.getRepayTime().toLocalDate().isAfter(plan.getDueDate())
                || "OVERDUE".equals(plan.getStatus())) {
            evidence.add("SINGLE_PAYMENT_EVIDENCE_INSUFFICIENT");
            return LatestPayment.UNKNOWN;
        }
        int comparison = record.getAmount().compareTo(plan.getTotalAmount());
        if (comparison == 0 && "PAID".equals(plan.getStatus())) {
            evidence.add("SOLE_ON_TIME_PAYMENT_EQUALS_PERIOD_TOTAL");
            return LatestPayment.FULL;
        }
        if (comparison < 0 && "PENDING".equals(plan.getStatus())) {
            evidence.add("SOLE_PAYMENT_BELOW_PERIOD_TOTAL");
            return LatestPayment.NOT_FULL;
        }
        evidence.add("PAYMENT_AMOUNT_STATUS_CONFLICT");
        return LatestPayment.UNKNOWN;
    }
}
