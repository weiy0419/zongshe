package com.example.zongshe1.modules.ops.mq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BusinessEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private BusinessEventPublisher publisher;

    @Test
    void publishSendsToLoanExchange() {
        Map<String, Object> payload = Map.of("applicationNo", "LA-1");

        boolean sent = publisher.publish(BusinessEventQueueConfig.LOAN_APPLIED, payload);

        assertTrue(sent);
        verify(rabbitTemplate).convertAndSend(BusinessEventQueueConfig.EXCHANGE, BusinessEventQueueConfig.LOAN_APPLIED, payload);
    }

    @Test
    void brokerFailureReturnsFalse() {
        doThrow(new RuntimeException("connection refused"))
                .when(rabbitTemplate)
                .convertAndSend(BusinessEventQueueConfig.EXCHANGE, BusinessEventQueueConfig.RISK_DECIDED, "report-1");

        assertFalse(publisher.publish(BusinessEventQueueConfig.RISK_DECIDED, "report-1"));
    }
}
