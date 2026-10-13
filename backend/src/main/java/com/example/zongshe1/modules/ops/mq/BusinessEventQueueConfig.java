package com.example.zongshe1.modules.ops.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 声明业务交换机和队列。催收延迟队列本周只声明，不消费。
 */
@Configuration
public class BusinessEventQueueConfig {

    public static final String EXCHANGE = "loan.exchange";
    public static final String LOAN_APPLIED = "loan.applied";
    public static final String LOAN_REPAID = "loan.repaid";
    public static final String LOAN_OVERDUE = "loan.overdue";
    public static final String RISK_DECIDED = "risk.decided";
    public static final String COLLECTION_DELAY = "collection.delay";
    public static final String COLLECTION_READY = "collection.ready";
    /** 逾期消息在此停留 90 秒后再进入 collection.ready。本周只声明，不消费。 */
    public static final int COLLECTION_DELAY_MS = 90_000;

    @Bean
    public Declarables businessEvents() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        for (String routingKey : List.of(LOAN_APPLIED, LOAN_REPAID, LOAN_OVERDUE, RISK_DECIDED, COLLECTION_READY)) {
            Queue queue = new Queue(routingKey, true);
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        }
        Queue delay = new Queue(COLLECTION_DELAY, true, false, false, Map.of(
                "x-message-ttl", COLLECTION_DELAY_MS,
                "x-dead-letter-exchange", EXCHANGE,
                "x-dead-letter-routing-key", COLLECTION_READY
        ));
        declarables.add(delay);
        declarables.add(BindingBuilder.bind(delay).to(exchange).with(COLLECTION_DELAY));
        return new Declarables(declarables);
    }

    @Bean
    public MessageConverter businessEventMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(true);
        return admin;
    }
}
