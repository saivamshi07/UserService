package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.entity.OutboxEvent;
import com.platform.userservice.entity.OutboxStatus;
import com.platform.userservice.entity.User;
import com.platform.userservice.event.UserDeactivatedEvent;
import com.platform.userservice.event.UserRegisteredEvent;
import com.platform.userservice.repository.OutboxEventRepository;
import com.platform.userservice.repository.UserRepository;
import com.platform.userservice.service.OutboxPublisherService;
import com.platform.userservice.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class OutboxIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void testRegisterUser_CreatesPendingOutboxEvent() throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis()).substring(8);
        RegisterRequest request = RegisterRequest.builder()
                .username("outboxuser" + suffix)
                .email("outboxuser" + suffix + "@example.com")
                .password("StrongPass123!")
                .build();

        var response = userService.registerUser(request, "127.0.0.1", "JUnit");

        // Verify outbox event persisted atomically
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("USER");
        assertThat(event.getAggregateId()).isEqualTo(response.getId().toString());
        assertThat(event.getEventType()).isEqualTo("USER_REGISTERED");
        assertThat(event.getTopic()).isEqualTo("user.registered.v1");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        UserRegisteredEvent payload = objectMapper.readValue(event.getPayload(), UserRegisteredEvent.class);
        assertThat(payload.getUserId()).isEqualTo(response.getId());
        assertThat(payload.getUsername()).isEqualTo(request.getUsername());
        assertThat(payload.getEmail()).isEqualTo(request.getEmail());
    }

    @Test
    void testDeactivateUser_CreatesPendingOutboxEvent() throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis()).substring(8);
        User user = userRepository.save(User.builder()
                .username("deactuser" + suffix)
                .email("deact" + suffix + "@example.com")
                .passwordHash("passhash")
                .isActive(true)
                .build());

        userService.deactivateUser(user.getId(), "127.0.0.1", "JUnit");

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("USER");
        assertThat(event.getAggregateId()).isEqualTo(user.getId().toString());
        assertThat(event.getEventType()).isEqualTo("USER_DEACTIVATED");
        assertThat(event.getTopic()).isEqualTo("user.deactivated.v1");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        UserDeactivatedEvent payload = objectMapper.readValue(event.getPayload(), UserDeactivatedEvent.class);
        assertThat(payload.getUserId()).isEqualTo(user.getId());
        assertThat(payload.getUsername()).isEqualTo(user.getUsername());
        assertThat(payload.getDisabledAt()).isNotNull();
    }

    @Test
    void testOutboxPublisher_PublishesToKafkaAndUpdatesStatus() {
        // Arrange pending event
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("USER")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("USER_REGISTERED")
                .topic("user.registered.v1")
                .payload("{\"test\":\"data\"}")
                .status(OutboxStatus.PENDING)
                .build();
        event = outboxEventRepository.save(event);

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        outboxPublisherService.publishPendingEvents();

        // Assert
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("user.registered.v1");
        assertThat(keyCaptor.getValue()).isEqualTo(event.getAggregateId());
        assertThat(payloadCaptor.getValue()).isEqualTo("{\"test\":\"data\"}");

        OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getProcessedAt()).isNotNull();
    }
}
