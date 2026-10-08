package com.platform.userservice.service;

import com.platform.userservice.entity.OutboxEvent;
import com.platform.userservice.entity.OutboxStatus;
import com.platform.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherService {

    private static final int MAX_RETRIES = 5;
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:2000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING, PageRequest.of(0, BATCH_SIZE));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to publish", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            publishSingleEvent(event);
        }
    }

    @Transactional
    public void publishSingleEvent(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            event.setStatus(OutboxStatus.PUBLISHED);
                            event.setProcessedAt(Instant.now());
                            event.setErrorMessage(null);
                            outboxEventRepository.save(event);
                            log.info("Published outbox event {} to topic {}", event.getId(), event.getTopic());
                        } else {
                            handleFailure(event, ex);
                        }
                    });
        } catch (Exception ex) {
            handleFailure(event, ex);
        }
    }

    private void handleFailure(OutboxEvent event, Throwable ex) {
        int retries = event.getRetryCount() + 1;
        event.setRetryCount(retries);
        event.setErrorMessage(ex.getMessage());

        if (retries >= MAX_RETRIES) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("Outbox event {} exceeded max retries ({}). Marked as FAILED. Error: {}",
                    event.getId(), MAX_RETRIES, ex.getMessage());
        } else {
            log.warn("Failed to publish outbox event {} (attempt {}/{}). Retrying next tick. Error: {}",
                    event.getId(), retries, MAX_RETRIES, ex.getMessage());
        }
        outboxEventRepository.save(event);
    }
}
