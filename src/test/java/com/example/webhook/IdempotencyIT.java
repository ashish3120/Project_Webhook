package com.example.webhook;

import com.example.webhook.dto.IngestEventRequest;
import com.example.webhook.dto.IngestEventResponse;
import com.example.webhook.dto.RegisterEndpointRequest;
import com.example.webhook.entity.Delivery;
import com.example.webhook.repository.DeliveryRepository;
import com.example.webhook.repository.EventRepository;
import com.example.webhook.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency Integration Tests
 *
 * Critical requirement: submitting the same eventId for the same tenant MUST NOT
 * create duplicate events or duplicate deliveries.
 *
 * The system uses a database-level UNIQUE constraint on (tenant_id, event_id_external)
 * as the ultimate safety net against races. The application also has a fast-path read
 * before insert to return early on known duplicates.
 */
@DisplayName("Idempotency Tests")
class IdempotencyIT extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    @DisplayName("Duplicate eventId for same tenant returns 202 but does not create duplicate deliveries")
    void duplicateEventId_sameTenant_noNewDelivery() {
        // Register an endpoint for makemytrip subscribed to payment.success
        registerEndpoint("makemytrip", "http://example.com/webhook-idempotency", List.of("payment.success"));

        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("PAY-IDEMPOTENCY-001");
        req.setType("payment.success");
        req.setPayload(Map.of("amount", 100));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // First submission
        ResponseEntity<IngestEventResponse> first = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), IngestEventResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody()).isNotNull();
        UUID eventId = first.getBody().getEventId();
        int firstDeliveries = first.getBody().getDeliveriesCreated();

        // Second submission — same tenant, same eventId
        ResponseEntity<IngestEventResponse> second = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), IngestEventResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).isNotNull();
        // Should return the same internal event ID
        assertThat(second.getBody().getEventId()).isEqualTo(eventId);
        // No new deliveries should be created on the second call
        assertThat(second.getBody().getDeliveriesCreated()).isEqualTo(0);

        // Database-level verification: only 1 event row, not 2
        long eventCount = eventRepository.findAll().stream()
                .filter(e -> "PAY-IDEMPOTENCY-001".equals(e.getEventIdExternal()))
                .count();
        assertThat(eventCount).isEqualTo(1);

        // Database-level verification: deliveries were created only once
        List<Delivery> deliveries = deliveryRepository.findByEventIdAndTenantId(
                eventId,
                eventRepository.findById(eventId).orElseThrow().getTenantId());
        assertThat(deliveries).hasSize(firstDeliveries);
    }

    @Test
    @DisplayName("Same eventId for different tenants creates independent events")
    void sameEventId_differentTenants_bothAccepted() {
        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("SHARED-EVENT-001");
        req.setType("payment.success");
        req.setPayload(Map.of("amount", 200));

        // Submit for tenant makemytrip
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-Tenant-Id", "makemytrip");
        headersA.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<IngestEventResponse> respA = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headersA), IngestEventResponse.class);
        assertThat(respA.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(respA.getBody()).isNotNull();

        // Submit for tenant googlepay — should also succeed (different tenant scope)
        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-Tenant-Id", "googlepay");
        headersB.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<IngestEventResponse> respB = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headersB), IngestEventResponse.class);
        assertThat(respB.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(respB.getBody()).isNotNull();

        // Both events should have different internal IDs
        assertThat(respA.getBody().getEventId()).isNotEqualTo(respB.getBody().getEventId());

        // Both events should exist in the database
        long totalEvents = eventRepository.findAll().stream()
                .filter(e -> "SHARED-EVENT-001".equals(e.getEventIdExternal()))
                .count();
        assertThat(totalEvents).isEqualTo(2);
    }

    private void registerEndpoint(String tenant, String url, List<String> eventTypes) {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl(url);
        req.setEventTypes(eventTypes);
        req.setAllowInternal(true);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);

        restTemplate.exchange("/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
    }
}
