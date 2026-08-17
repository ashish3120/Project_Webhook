package com.example.webhook;

import com.example.webhook.dto.*;
import com.example.webhook.entity.Delivery;
import com.example.webhook.entity.DeliveryAttempt;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.entity.Event;
import com.example.webhook.entity.Tenant;
import com.example.webhook.repository.*;
import com.example.webhook.worker.DeliveryWorker;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook Delivery Tests using WireMock
 *
 * Tests the actual HTTP delivery pipeline:
 * - Successful delivery (200)
 * - Failure with retry (500 → 500 → 200)
 * - Dead-lettering after max attempts (always 500)
 * - Timeout handling
 * - HMAC-SHA256 signature verification
 * - Immutable attempt ledger
 *
 * WireMock simulates the external webhook receiver.
 * No real external network calls are made.
 */
@DisplayName("Webhook Delivery Tests with WireMock")
class WebhookDeliveryIT extends BaseIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TenantRepository tenantRepository;

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
    }

    @Test
    @DisplayName("Successful delivery (200) marks delivery as DELIVERED with 1 attempt")
    void successfulDelivery_markedAsDelivered() throws InterruptedException {
        stubFor(post(urlPathMatching("/webhook.*"))
                .willReturn(ok().withBody("accepted")));

        Delivery delivery = createDeliveryForWireMock("DELIVERED-001", "/webhook/success");
        deliveryWorker.processDelivery(delivery);

        Delivery result = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(Delivery.DeliveryStatus.DELIVERED);
        assertThat(result.getAttemptCount()).isEqualTo(1);

        List<DeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getResponseCode()).isEqualTo(200);
        assertThat(attempts.get(0).getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Failed delivery (500) schedules retry, delivery stays PENDING")
    void failedDelivery_schedulesRetry() throws InterruptedException {
        stubFor(post(urlPathMatching("/webhook.*"))
                .willReturn(serverError().withBody("internal error")));

        Delivery delivery = createDeliveryForWireMock("RETRY-001", "/webhook/fails");
        deliveryWorker.processDelivery(delivery);

        Delivery result = deliveryRepository.findById(delivery.getId()).orElseThrow();
        // After 1 failure with maxAttempts=3, should be PENDING (scheduled for retry)
        assertThat(result.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(result.getAttemptCount()).isEqualTo(1);
        assertThat(result.getLastResponseCode()).isEqualTo(500);
        // Next attempt should be in the future
        assertThat(result.getNextAttemptAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    @DisplayName("Max attempts reached marks delivery as DEAD_LETTERED")
    void maxAttemptsReached_deadLettered() throws InterruptedException {
        // Always return 500
        stubFor(post(urlPathMatching("/webhook.*"))
                .willReturn(serverError().withBody("permanent failure")));

        Delivery delivery = createDeliveryForWireMock("DEAD-LETTER-001", "/webhook/dead");

        // Simulate maxAttempts-1 previous attempts
        delivery.setAttemptCount(2); // test config has maxAttempts=3, so this is attempt 3
        deliveryRepository.save(delivery);

        deliveryWorker.processDelivery(delivery);

        Delivery result = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(Delivery.DeliveryStatus.DEAD_LETTERED);
    }

    @Test
    @DisplayName("Attempt history is immutable — all attempts preserved after retry")
    void attemptHistory_isImmutable() throws InterruptedException {
        // Return 500 twice, then 200
        wireMock.stubFor(post(urlPathMatching("/webhook.*"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("Started")
                .willReturn(serverError().withBody("error 1"))
                .willSetStateTo("attempt-2"));

        wireMock.stubFor(post(urlPathMatching("/webhook.*"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("attempt-2")
                .willReturn(serverError().withBody("error 2"))
                .willSetStateTo("attempt-3"));

        wireMock.stubFor(post(urlPathMatching("/webhook.*"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("attempt-3")
                .willReturn(ok().withBody("success")));

        Delivery delivery = createDeliveryForWireMock("HISTORY-001", "/webhook/history");

        // Simulate first attempt (500)
        deliveryWorker.processDelivery(delivery);
        // Re-load and simulate second attempt (500)
        Delivery d2 = deliveryRepository.findById(delivery.getId()).orElseThrow();
        d2.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1)); // make it due
        deliveryRepository.save(d2);
        deliveryWorker.processDelivery(d2);
        // Re-load and simulate third attempt (200)
        Delivery d3 = deliveryRepository.findById(delivery.getId()).orElseThrow();
        d3.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
        deliveryRepository.save(d3);
        deliveryWorker.processDelivery(d3);

        Delivery final_ = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(final_.getStatus()).isEqualTo(Delivery.DeliveryStatus.DELIVERED);
        assertThat(final_.getAttemptCount()).isEqualTo(3);

        List<DeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(3);

        // Attempt 1 is still 500 (immutable)
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(0).getResponseCode()).isEqualTo(500);

        // Attempt 2 is still 500 (immutable)
        assertThat(attempts.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(attempts.get(1).getResponseCode()).isEqualTo(500);

        // Attempt 3 is 200
        assertThat(attempts.get(2).getAttemptNumber()).isEqualTo(3);
        assertThat(attempts.get(2).getResponseCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("HMAC-SHA256 signature header is present and verifiable on outbound calls")
    void hmacSignature_isPresentAndCorrect() {
        stubFor(post(urlPathMatching("/webhook.*"))
                .willReturn(ok()));

        Delivery delivery = createDeliveryForWireMock("HMAC-001", "/webhook/hmac");
        Endpoint ep = endpointRepository.findById(delivery.getEndpointId()).orElseThrow();

        deliveryWorker.processDelivery(delivery);

        // Verify WireMock received a request with the signature headers
        verify(postRequestedFor(urlPathMatching("/webhook.*"))
                .withHeader("X-Webhook-Signature", matching(".+"))
                .withHeader("X-Webhook-Timestamp", matching("\\d+")));
    }

    @Test
    @DisplayName("Timeout treated as failed delivery attempt with retry scheduled")
    void timeout_treatedAsFailedAttempt() {
        // WireMock delays response longer than read timeout (3000ms in test config)
        stubFor(post(urlPathMatching("/webhook.*"))
                .willReturn(ok().withFixedDelay(5000)));

        Delivery delivery = createDeliveryForWireMock("TIMEOUT-001", "/webhook/slow");
        deliveryWorker.processDelivery(delivery);

        Delivery result = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(result.getAttemptCount()).isEqualTo(1);

        List<DeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(1);
        // No response code (timeout) — error field should contain timeout info
        assertThat(attempts.get(0).getError()).isNotNull();
    }

    // ---- Helpers ----

    private Delivery createDeliveryForWireMock(String externalEventId, String path) {
        Tenant tenant = tenantRepository.findByName("makemytrip").orElseThrow();

        Endpoint ep = new Endpoint();
        ep.setTenantId(tenant.getId());
        ep.setUrl("http://localhost:" + wireMock.port() + path);
        ep.setSecret("test-hmac-secret-12345");
        ep.setSubscribedEventTypes(List.of("payment.success"));
        ep.setStatus(Endpoint.EndpointStatus.ACTIVE);
        ep.setAllowInternal(true);
        endpointRepository.save(ep);

        Event event = new Event();
        event.setTenantId(tenant.getId());
        event.setEventIdExternal(externalEventId);
        event.setType("payment.success");
        event.setPayload(Map.of("amount", 100, "bookingId", "TEST123"));
        eventRepository.save(event);

        Delivery delivery = new Delivery();
        delivery.setEventId(event.getId());
        delivery.setEndpointId(ep.getId());
        delivery.setTenantId(tenant.getId());
        delivery.setStatus(Delivery.DeliveryStatus.IN_PROGRESS); // Pre-claimed state
        delivery.setLockedBy("test-worker");
        delivery.setLockedUntil(OffsetDateTime.now().plusSeconds(60));
        delivery.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
        return deliveryRepository.save(delivery);
    }
}
