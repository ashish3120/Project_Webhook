package com.example.webhook;

import com.example.webhook.dto.IngestEventRequest;
import com.example.webhook.dto.IngestEventResponse;
import com.example.webhook.dto.RegisterEndpointRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fan-Out Integration Tests
 *
 * When an event is ingested:
 * - One PENDING delivery must be created for each ACTIVE endpoint subscribed to that event type
 * - Endpoints subscribed to OTHER event types must NOT receive a delivery
 * - Disabled endpoints must NOT receive a delivery
 */
@DisplayName("Fan-Out Tests")
class FanOutIT extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    @DisplayName("payment.success creates deliveries for subscribed endpoints only")
    void fanOut_createsDeliveriesOnlyForSubscribedEndpoints() {
        // Register Endpoint A — subscribed to payment.success
        registerEndpoint("makemytrip", "http://endpoint-a.example.com/wh", List.of("payment.success"));
        // Register Endpoint B — subscribed to payment.success
        registerEndpoint("makemytrip", "http://endpoint-b.example.com/wh", List.of("payment.success"));
        // Register Endpoint C — subscribed to invoice.paid (different event type — should NOT get delivery)
        registerEndpoint("makemytrip", "http://endpoint-c.example.com/wh", List.of("invoice.paid"));

        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("FAN-OUT-001");
        req.setType("payment.success");
        req.setPayload(Map.of("amount", 3000));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<IngestEventResponse> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), IngestEventResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();

        // Exactly 2 deliveries (Endpoint A and B) — NOT 3
        assertThat(resp.getBody().getDeliveriesCreated()).isEqualTo(2);

        // Verify at DB level: all deliveries are PENDING before worker processes them
        UUID eventId = resp.getBody().getEventId();
        var tenantId = eventRepository.findById(eventId).orElseThrow().getTenantId();
        List<Delivery> deliveries = deliveryRepository.findByEventIdAndTenantId(eventId, tenantId);

        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allMatch(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING);
        assertThat(deliveries).allMatch(d -> d.getAttemptCount() == 0);
    }

    @Test
    @DisplayName("Disabled endpoint does not receive delivery on new event")
    void disabledEndpoint_doesNotReceiveDelivery() {
        // Register and then disable an endpoint
        String endpointId = registerEndpoint("makemytrip",
                "http://disabled.example.com/wh", List.of("payment.success"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");

        // Disable the endpoint
        restTemplate.exchange("/api/v1/endpoints/" + endpointId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);

        IngestEventRequest req = new IngestEventRequest();
        req.setEventId("FAN-OUT-DISABLED-001");
        req.setType("payment.success");
        req.setPayload(Map.of("amount", 100));

        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<IngestEventResponse> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(req, headers), IngestEventResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // No delivery should be created for the disabled endpoint
        assertThat(resp.getBody().getDeliveriesCreated()).isEqualTo(0);
    }

    private String registerEndpoint(String tenant, String url, List<String> eventTypes) {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl(url);
        req.setEventTypes(eventTypes);
        req.setAllowInternal(true);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange("/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headers), Map.class);
        return String.valueOf(resp.getBody().get("id"));
    }
}
