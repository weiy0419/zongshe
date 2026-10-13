package com.example.zongshe1.modules.ops.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 业务方只调用这里发消息，不自己声明交换机。代理不可用时返回 false，不打断申贷或评估。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public boolean publish(String routingKey, Object payload) {
        try {
            rabbitTemplate.convertAndSend(BusinessEventQueueConfig.EXCHANGE, routingKey, payload);
            log.info("published exchange={} routingKey={}", BusinessEventQueueConfig.EXCHANGE, routingKey);
            return true;
        } catch (RuntimeException ex) {
            log.warn("publish failed routingKey={}: {}", routingKey, ex.getMessage());
            return false;
        }
    }
}
