package com.swiftcart.inventory_service.service;

import com.swiftcart.inventory_service.entity.OutboxEvent;
import com.swiftcart.inventory_service.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class OutboxEventPublisherService {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topicName;

    public OutboxEventPublisherService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${inventory.topic.name:inventory-events}") String topicName
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    @Scheduled(fixedRate = 5000)
    @Transactional("transactionManager")
    public void publishOutboxEvents() {
        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalse();
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.executeInTransaction(kafkaOps -> {
                    kafkaOps.send(topicName, String.valueOf(event.getAggregateId()), event.getPayload());
                    outboxEventRepository.delete(event);
                    log.info("Published and deleted outbox event: id={}, eventType={}, aggregateId={}",
                            event.getId(), event.getEventType(), event.getAggregateId());
                    return true;
                });
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}, eventType={}, aggregateId={}, error={}",
                        event.getId(), event.getEventType(), event.getAggregateId(), e.getMessage());
            }
        }
    }
}