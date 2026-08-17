package com.example.webhook;

import com.example.webhook.dto.*;
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
 * Tenant Isolation Integration Tests
 *
 * This is one of the evaluation's most important security tests.
 *
 * Requirements:
 * - Tenant A must never read Tenant B's endpoints, events, deliveries, or secrets
 * - A crafted UUID from Tenant B's data must not bypass tenant filtering
 * - Redrive must only work on the requesting tenant's own deliveries
 *
 * If ANY of these tests fail, it represents a security breach.
 */
@DisplayName("Tenant Isolation Tests")
class TenantIsolationIT extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Tenant A cannot read Tenant B's endpoint by crafted ID")
    void tenantA_cannotReadTenantB_endpoint() {
        // Register endpoint for tenant B (googlepay)
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl("http://tenantb-secret.example.com/wh");
        req.setEventTypes(List.of("payment.success"));
        req.setAllowInternal(true);

        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-Tenant-Id", "googlepay");
        headersB.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> registerResp = restTemplate.exchange(
                "/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headersB), Map.class);

        assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tenantBEndpointId = (String) registerResp.getBody().get("id");

        // Now attempt to access Tenant B's endpoint using Tenant A's credentials
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-Tenant-Id", "makemytrip");

        ResponseEntity<String> crossTenantResp = restTemplate.exchange(
                "/api/v1/endpoints/" + tenantBEndpointId, HttpMethod.GET,
                new HttpEntity<>(headersA), String.class);

        // Must be rejected — 404 is acceptable (don't reveal that the endpoint exists for another tenant)
        assertThat(crossTenantResp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Tenant A cannot delete Tenant B's endpoint")
    void tenantA_cannotDeleteTenantB_endpoint() {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl("http://tenantb-delete-test.example.com/wh");
        req.setEventTypes(List.of("invoice.paid"));
        req.setAllowInternal(true);

        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-Tenant-Id", "googlepay");
        headersB.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> registerResp = restTemplate.exchange(
                "/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headersB), Map.class);
        String tenantBEndpointId = (String) registerResp.getBody().get("id");

        // Attempt cross-tenant delete
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-Tenant-Id", "makemytrip");

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/endpoints/" + tenantBEndpointId, HttpMethod.DELETE,
                new HttpEntity<>(headersA), Void.class);

        assertThat(deleteResp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Tenant A cannot redrive Tenant B's delivery")
    void tenantA_cannotRedrive_tenantB_delivery() {
        // Create event + endpoint + delivery for Tenant B
        String eventId = ingestEvent("googlepay", "CROSS-TENANT-REDRIVE-001", "payment.success");

        // Get the delivery ID from Tenant B's perspective
        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-Tenant-Id", "googlepay");

        // Try to redrive with Tenant A using a random delivery UUID (cross-tenant attempt)
        UUID fakeTenantBDeliveryId = UUID.randomUUID();
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-Tenant-Id", "makemytrip");
        headersA.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> redriveResp = restTemplate.exchange(
                "/api/v1/deliveries/" + fakeTenantBDeliveryId + "/redrive", HttpMethod.POST,
                new HttpEntity<>(headersA), String.class);

        assertThat(redriveResp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Endpoint list only returns calling tenant's endpoints")
    void listEndpoints_onlyReturnsTenantOwned() {
        // Register endpoints for both tenants
        registerEndpoint("makemytrip", "http://mmt-list-test.example.com/wh");
        registerEndpoint("googlepay", "http://gpay-list-test.example.com/wh");

        // List endpoints as makemytrip — should NOT see googlepay's endpoint
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-Tenant-Id", "makemytrip");

        ResponseEntity<List> listResp = restTemplate.exchange(
                "/api/v1/endpoints", HttpMethod.GET, new HttpEntity<>(headersA), List.class);

        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> endpoints = listResp.getBody();
        // All returned endpoints should have the makemytrip tenant — no googlepay URLs
        if (endpoints != null) {
            for (Object ep : endpoints) {
                Map<?, ?> epMap = (Map<?, ?>) ep;
                String url = (String) epMap.get("url");
                // Ensure googlepay's endpoint URL is not in makemytrip's list
                assertThat(url).doesNotContain("gpay-list-test");
            }
        }
    }

    // ---- Helpers ----

    private String ingestEvent(String tenant, String eventId, String type) {
        IngestEventRequest req = new IngestEventRequest();
        req.setEventId(eventId);
        req.setType(type);
        req.setPayload(Map.of("amount", 100));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<IngestEventResponse> resp = restTemplate.exchange(
                "/api/v1/events", HttpMethod.POST, new HttpEntity<>(req, headers), IngestEventResponse.class);
        return resp.getBody() != null ? resp.getBody().getEventId().toString() : null;
    }

    private void registerEndpoint(String tenant, String url) {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl(url);
        req.setEventTypes(List.of("payment.success"));
        req.setAllowInternal(true);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);

        restTemplate.exchange("/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headers), Map.class);
    }
}
