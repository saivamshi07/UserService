package com.platform.userservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.entity.OutboxEvent;
import com.platform.userservice.entity.OutboxStatus;
import com.platform.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent saveEvent(String aggregateType, String aggregateId, String eventType, String topic, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .build();

            OutboxEvent saved = outboxEventRepository.save(event);
            log.info("Persisted outbox event {} for aggregate {} (type: {})", saved.getId(), aggregateId, eventType);
            return saved;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for aggregate {}: {}", aggregateId, e.getMessage(), e);
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }
}
