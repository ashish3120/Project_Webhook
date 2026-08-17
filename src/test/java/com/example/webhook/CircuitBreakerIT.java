package com.example.webhook;

import com.example.webhook.circuitbreaker.EndpointCircuitBreakerService;
import com.example.webhook.entity.*;
import com.example.webhook.repository.*;
import com.example.webhook.worker.DeliveryWorker;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Circuit Breaker Tests with WireMock")
class CircuitBreakerIT extends BaseIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired private DeliveryWorker deliveryWorker;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private EndpointCircuitBreakerService circuitBreakerService;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        // EndpointCircuitBreakerService caches CircuitBreakers by Endpoint ID.
        // By generating a new Endpoint for each test via createEndpoint(),
        // we guarantee a completely fresh, CLOSED circuit breaker every time.
    }

    @Test
    @DisplayName("1. Circuit opens after the configured number of failures")
    void circuitOpensAfterFailureThreshold() {
        String path = "/webhook/cb-open";
        stubFor(post(urlPathMatching(path)).willReturn(serverError()));

        Endpoint ep = createEndpoint(path);
        
        // Ensure starting state is CLOSED
        assertThat(circuitBreakerService.getState(ep.getId())).isEqualTo(CircuitBreaker.State.CLOSED);

        // Make 5 failures (sliding window size is 5 in application-test.yml)
        for (int i = 0; i < 5; i++) {
            Delivery delivery = createDeliveryForEndpoint(ep, "FAIL-" + i);
            deliveryWorker.processDelivery(delivery);
        }

        // Circuit should now be OPEN because failure threshold was reached
        assertThat(circuitBreakerService.getState(ep.getId())).isEqualTo(CircuitBreaker.State.OPEN);
        
        // Verify WireMock was actually called 5 times
        verify(5, postRequestedFor(urlPathMatching(path)));
    }

    @Test
    @DisplayName("2. Calls are prevented while the circuit is OPEN")
    void openCircuitPreventsCalls() {
        String path = "/webhook/cb-prevents";
        stubFor(post(urlPathMatching(path)).willReturn(serverError()));

        Endpoint ep = createEndpoint(path);

        // Open the circuit using 5 failed calls
        for (int i = 0; i < 5; i++) {
            Delivery delivery = createDeliveryForEndpoint(ep, "FAIL-PREV-" + i);
            deliveryWorker.processDelivery(delivery);
        }

        assertThat(circuitBreakerService.getState(ep.getId())).isEqualTo(CircuitBreaker.State.OPEN);
        
        // Reset WireMock request journal to easily count new requests
        wireMock.resetRequests();

        // Submit another delivery while circuit is OPEN
        Delivery blockedDelivery = createDeliveryForEndpoint(ep, "BLOCKED-001");
        deliveryWorker.processDelivery(blockedDelivery);

        // Verify WireMock received NO new HTTP requests (the call was blocked entirely)
        verify(0, postRequestedFor(urlPathMatching(path)));

        // Verify the delivery was safely postponed rather than incrementing attempt counts
        Delivery result = deliveryRepository.findById(blockedDelivery.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(result.getNextAttemptAt()).isAfter(OffsetDateTime.now());
        assertThat(result.getAttemptCount()).isEqualTo(0); // Attempt count wasn't incremented
    }

    @Test
    @DisplayName("3. Circuit breakers are isolated per endpoint")
    void circuitBreakersAreIsolatedPerEndpoint() {
        String pathA = "/webhook/cb-iso-a";
        String pathB = "/webhook/cb-iso-b";
        
        stubFor(post(urlPathMatching(pathA)).willReturn(serverError()));
        stubFor(post(urlPathMatching(pathB)).willReturn(ok()));

        Endpoint epA = createEndpoint(pathA);
        Endpoint epB = createEndpoint(pathB);

        // Open circuit for Endpoint A
        for (int i = 0; i < 5; i++) {
            Delivery deliveryA = createDeliveryForEndpoint(epA, "ISO-A-" + i);
            deliveryWorker.processDelivery(deliveryA);
        }
        
        assertThat(circuitBreakerService.getState(epA.getId())).isEqualTo(CircuitBreaker.State.OPEN);

        // Process deliveries for Endpoint B
        for (int i = 0; i < 3; i++) {
            Delivery deliveryB = createDeliveryForEndpoint(epB, "ISO-B-" + i);
            deliveryWorker.processDelivery(deliveryB);
        }
        
        // Endpoint B's circuit should remain CLOSED despite Endpoint A's total failure
        assertThat(circuitBreakerService.getState(epB.getId())).isEqualTo(CircuitBreaker.State.CLOSED);

        // Verify Endpoint B actually received 3 successful requests on the wire
        verify(3, postRequestedFor(urlPathMatching(pathB)));
    }

    // ---- Helpers ----

    private Endpoint createEndpoint(String path) {
        Tenant tenant = tenantRepository.findByName("makemytrip").orElseThrow();

        Endpoint ep = new Endpoint();
        ep.setTenantId(tenant.getId());
        ep.setUrl("http://localhost:" + wireMock.port() + path);
        ep.setSecret("test-hmac-secret-12345");
        ep.setSubscribedEventTypes(List.of("payment.success"));
        ep.setStatus(Endpoint.EndpointStatus.ACTIVE);
        ep.setAllowInternal(true);
        return endpointRepository.save(ep);
    }

    private Delivery createDeliveryForEndpoint(Endpoint ep, String externalEventId) {
        Event event = new Event();
        event.setTenantId(ep.getTenantId());
        event.setEventIdExternal(externalEventId);
        event.setType("payment.success");
        event.setPayload(Map.of("amount", 100));
        eventRepository.save(event);

        Delivery delivery = new Delivery();
        delivery.setEventId(event.getId());
        delivery.setEndpointId(ep.getId());
        delivery.setTenantId(ep.getTenantId());
        delivery.setStatus(Delivery.DeliveryStatus.IN_PROGRESS); // Pre-claimed state
        delivery.setLockedBy("test-worker");
        delivery.setLockedUntil(OffsetDateTime.now().plusSeconds(60));
        delivery.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
        return deliveryRepository.save(delivery);
    }
}
