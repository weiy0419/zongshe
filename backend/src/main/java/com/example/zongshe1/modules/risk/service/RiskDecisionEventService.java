package com.example.zongshe1.modules.risk.service;

import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.modules.ops.mq.BusinessEventPublisher;
import com.example.zongshe1.modules.ops.mq.BusinessEventQueueConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Best-effort decision transition notifications, not a durable delivery ledger. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskDecisionEventService {
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED", "MANUAL_REVIEW");
    private final BusinessEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public void afterReportPersisted(String previousDetailsJson, RiskReportDTO report) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("event={} status=SKIPPED_NO_TRANSACTION applicationId={} reportId={} decision={}",
                    BusinessEventQueueConfig.RISK_DECIDED, report.getApplicationId(),
                    report.getReportId(), report.getDecision());
            return;
        }
        String previousDecision = previousDecision(previousDetailsJson);
        if (previousDecision != null && previousDecision.equals(report.getDecision())) return;

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("event", BusinessEventQueueConfig.RISK_DECIDED);
        values.put("applicationId", report.getApplicationId());
        values.put("passed", "APPROVED".equals(report.getDecision()));
        values.put("riskLevel", finalRiskLevel(report));
        values.put("occurredAt", Instant.now().toString());
        values.put("reportId", report.getReportId());
        values.put("decision", report.getDecision());
        // All values are immutable scalars; null is intentional for an unknown final level.
        Map<String, Object> payload = Collections.unmodifiableMap(values);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (!publisher.publish(BusinessEventQueueConfig.RISK_DECIDED, payload)) {
                        logFailure(payload, "PUBLISH_RETURNED_FALSE", null);
                    }
                } catch (RuntimeException exception) {
                    logFailure(payload, "PUBLISH_EXCEPTION", exception);
                }
            }
        });
    }

    private String previousDecision(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode decision = root == null ? null : root.get("decision");
            if (decision != null && decision.isTextual() && DECISIONS.contains(decision.textValue())) {
                return decision.textValue();
            }
        } catch (JsonProcessingException ignored) {
            // Legacy/malformed reports cannot prove that this decision was already recorded.
        }
        return null;
    }

    private static Integer finalRiskLevel(RiskReportDTO report) {
        // Only the completed single WOE result already carries a model risk level.
        // Never invent EXTERNAL/AHP thresholds or equate manual review with rejection.
        Map<String, Object> breakdown = report.getBreakdown();
        if (!("APPROVED".equals(report.getDecision()) || "REJECTED".equals(report.getDecision())) || breakdown == null
                || !"SINGLE".equals(breakdown.get("fusionMode"))
                || !(breakdown.get("credit") instanceof Map<?, ?> credit)
                || !"COMPLETED".equals(credit.get("status"))
                || !"WOE".equals(credit.get("modelName"))
                || !report.getDecision().equals(credit.get("decision"))
                || !(breakdown.get("ruleResults") instanceof List<?> rules)) return null;
        Integer level = null;
        for (Object value : rules) {
            if (value instanceof Map<?, ?> rule && "CREDIT".equals(rule.get("stage"))) {
                if (level != null || !(rule.get("riskLevel") instanceof Integer candidate)
                        || candidate < 1 || candidate > 4) return null;
                level = candidate;
            }
        }
        return level;
    }

    private static void logFailure(Map<String, Object> payload, String reason, RuntimeException exception) {
        log.warn("event={} status=FAILED reason={} routingKey={} applicationId={} reportId={} decision={} occurredAt={}",
                payload.get("event"), reason, BusinessEventQueueConfig.RISK_DECIDED,
                payload.get("applicationId"), payload.get("reportId"), payload.get("decision"),
                payload.get("occurredAt"), exception);
    }
}
