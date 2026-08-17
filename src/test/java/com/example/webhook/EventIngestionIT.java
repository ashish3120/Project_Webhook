package com.example.webhook;

import com.example.webhook.dto.IngestEventRequest;
import com.example.webhook.dto.IngestEventResponse;
import com.example.webhook.dto.RegisterEndpointRequest;
import com.example.webhook.dto.EndpointResponse;
import com.example.webhook.entity.Delivery;
import com.example.webhook.repository.DeliveryRepository;
import com.example.webhook.repository.EventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for event ingestion.
 *
 * Uses real PostgreSQL via Testcontainers.
 * Tests the full HTTP → Service → DB pipeline.
 */
@DisplayName("Event Ingestion Tests")
class EventIngestionIT extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    @DisplayName("Valid event ingestion returns 202 Accepted")
    void validEvent_returns202() {
        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("EVT-001");
        req.setType("payment.success");
        req.setPayload(Map.of("amount", 5000, "bookingId", "MMT123"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<IngestEventResponse> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), IngestEventResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getEventId()).isNotNull();
        assertThat(resp.getBody().getExternalEventId()).isEqualTo("EVT-001");
    }

    @Test
    @DisplayName("Missing eventId returns 400 Bad Request")
    void missingEventId_returns400() {
        IngestEventRequest req = new IngestEventRequest();
        // eventId intentionally null
        req.setType("payment.success");
        req.setPayload(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Missing type returns 400 Bad Request")
    void missingType_returns400() {
        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("EVT-002");
        // type intentionally null
        req.setPayload(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Unknown tenant returns 401")
    void unknownTenant_returns401() {
        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("EVT-003");
        req.setType("payment.success");
        req.setPayload(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "unknown-tenant-xyz");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
