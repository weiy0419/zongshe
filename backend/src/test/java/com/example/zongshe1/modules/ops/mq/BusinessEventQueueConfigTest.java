package com.example.zongshe1.modules.ops.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessEventQueueConfigTest {

    @Test
    void declaresBusinessQueuesAndCollectionDelay() {
        Declarables declarables = new BusinessEventQueueConfig().businessEvents();
        Map<String, Queue> queues = new HashMap<>();
        boolean exchangePresent = false;
        int bindings = 0;
        for (Declarable declarable : declarables.getDeclarables()) {
            if (declarable instanceof org.springframework.amqp.core.TopicExchange exchange) {
                exchangePresent = BusinessEventQueueConfig.EXCHANGE.equals(exchange.getName());
            }
            if (declarable instanceof Queue queue) {
                queues.put(queue.getName(), queue);
            }
            if (declarable instanceof org.springframework.amqp.core.Binding) {
                bindings++;
            }
        }

        assertTrue(exchangePresent);
        assertEquals(6, bindings);
        for (String name : java.util.List.of(
                BusinessEventQueueConfig.LOAN_APPLIED,
                BusinessEventQueueConfig.LOAN_REPAID,
                BusinessEventQueueConfig.LOAN_OVERDUE,
                BusinessEventQueueConfig.RISK_DECIDED,
                BusinessEventQueueConfig.COLLECTION_READY)) {
            assertTrue(queues.containsKey(name));
            assertTrue(queues.get(name).isDurable());
        }
        Queue delay = queues.get(BusinessEventQueueConfig.COLLECTION_DELAY);
        assertEquals(BusinessEventQueueConfig.COLLECTION_DELAY_MS, delay.getArguments().get("x-message-ttl"));
        assertEquals(BusinessEventQueueConfig.EXCHANGE, delay.getArguments().get("x-dead-letter-exchange"));
        assertEquals(BusinessEventQueueConfig.COLLECTION_READY, delay.getArguments().get("x-dead-letter-routing-key"));
    }
}
