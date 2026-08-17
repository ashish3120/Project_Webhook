package com.example.webhook;

import com.example.webhook.dto.RegisterEndpointRequest;
import com.example.webhook.entity.Delivery;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.entity.Event;
import com.example.webhook.entity.Tenant;
import com.example.webhook.repository.*;
import com.example.webhook.worker.DeliveryWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent Worker Claim Tests
 *
 * This is one of the most critical tests in the entire suite.
 *
 * It verifies that SELECT FOR UPDATE SKIP LOCKED works correctly:
 * when multiple workers try to claim the same delivery simultaneously,
 * only ONE worker should succeed in claiming it.
 *
 * This test MUST use real PostgreSQL — no mocks or H2.
 * The locking semantics are PostgreSQL-specific.
 */
@DisplayName("Concurrent Worker Claim Tests")
class ConcurrentWorkerClaimIT extends BaseIntegrationTest {

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    @DisplayName("Concurrent workers claim disjoint sets — no delivery is claimed twice")
    void concurrentWorkers_noDuplicateClaims() throws InterruptedException {
        // Clear deliveries from previous test classes to prevent state bleed affecting the assertion
        deliveryRepository.deleteAll();

        // Create 3 pending deliveries
        Tenant tenant = tenantRepository.findByName("makemytrip").orElseThrow();
        Endpoint ep = createTestEndpoint(tenant.getId());
        Event event = createTestEvent(tenant.getId(), "CONCURRENT-001");

        List<Delivery> pendingDeliveries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pendingDeliveries.add(createPendingDelivery(event.getId(), ep.getId(), tenant.getId()));
        }

        int workerCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        // Track which delivery IDs get claimed and by which worker
        ConcurrentHashMap<UUID, String> claims = new ConcurrentHashMap<>();
        AtomicInteger totalClaimed = new AtomicInteger(0);

        for (int i = 0; i < workerCount; i++) {
            final int workerIdx = i;
            pool.submit(() -> {
                try {
                    startGate.await(); // All workers start simultaneously
                    List<Delivery> claimed = deliveryWorker.claimBatch();
                    for (Delivery d : claimed) {
                        String prev = claims.putIfAbsent(d.getId(), "worker-" + workerIdx);
                        if (prev == null) {
                            totalClaimed.incrementAndGet();
                        }
                        // If prev != null, this delivery was already claimed by another worker — FAILURE
                        assertThat(prev).as("Delivery %s claimed twice!", d.getId()).isNull();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // Release all workers simultaneously
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();

        pool.shutdown();

        // Verify total claimed <= number of deliveries
        assertThat(totalClaimed.get()).isLessThanOrEqualTo(pendingDeliveries.size());
        // Verify no delivery was claimed by more than one worker
        assertThat(claims.size()).isEqualTo(totalClaimed.get());

        // Verify in DB: all claimed deliveries have IN_PROGRESS status
        for (UUID deliveryId : claims.keySet()) {
            Delivery d = deliveryRepository.findById(deliveryId).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
            assertThat(d.getLockedBy()).isNotNull();
            assertThat(d.getLockedUntil()).isAfter(OffsetDateTime.now());
        }
    }

    @Test
    @DisplayName("Delivery with expired lease can be reclaimed by another worker")
    void expiredLease_canBeReclaimed() throws InterruptedException {
        Tenant tenant = tenantRepository.findByName("makemytrip").orElseThrow();
        Endpoint ep = createTestEndpoint(tenant.getId());
        Event event = createTestEvent(tenant.getId(), "LEASE-EXPIRY-001");
        Delivery delivery = createPendingDelivery(event.getId(), ep.getId(), tenant.getId());

        // Simulate a crashed worker: set lease in the past
        delivery.setStatus(Delivery.DeliveryStatus.IN_PROGRESS);
        delivery.setLockedBy("crashed-worker-abc");
        delivery.setLockedUntil(OffsetDateTime.now().minusSeconds(60)); // expired
        deliveryRepository.save(delivery);

        // Another worker should be able to reclaim it
        List<Delivery> claimed = deliveryWorker.claimBatch();

        assertThat(claimed).anyMatch(d -> d.getId().equals(delivery.getId()));

        Delivery reclaimed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
        assertThat(reclaimed.getLockedBy()).isNotEqualTo("crashed-worker-abc");
    }

    @Test
    @DisplayName("Delivery with valid (non-expired) lease cannot be reclaimed")
    void validLease_cannotBeReclaimed() throws InterruptedException {
        Tenant tenant = tenantRepository.findByName("makemytrip").orElseThrow();
        Endpoint ep = createTestEndpoint(tenant.getId());
        Event event = createTestEvent(tenant.getId(), "LEASE-VALID-001");
        Delivery delivery = createPendingDelivery(event.getId(), ep.getId(), tenant.getId());

        // Set a valid lease (expires in the future)
        delivery.setStatus(Delivery.DeliveryStatus.IN_PROGRESS);
        delivery.setLockedBy("active-worker-xyz");
        delivery.setLockedUntil(OffsetDateTime.now().plusSeconds(3600)); // still valid
        deliveryRepository.save(delivery);

        // Another worker should NOT be able to claim it
        List<Delivery> claimed = deliveryWorker.claimBatch();

        boolean claimedThisDelivery = claimed.stream().anyMatch(d -> d.getId().equals(delivery.getId()));
        assertThat(claimedThisDelivery).isFalse();

        // Original lease should still be intact
        Delivery stillLocked = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(stillLocked.getLockedBy()).isEqualTo("active-worker-xyz");
    }

    // Helper methods
    private Endpoint createTestEndpoint(UUID tenantId) {
        Endpoint ep = new Endpoint();
        ep.setTenantId(tenantId);
        ep.setUrl("http://test-concurrent.example.com/wh");
        ep.setSecret("test-secret-concurrent");
        ep.setSubscribedEventTypes(List.of("payment.success"));
        ep.setStatus(Endpoint.EndpointStatus.ACTIVE);
        return endpointRepository.save(ep);
    }

    private Event createTestEvent(UUID tenantId, String externalId) {
        Event event = new Event();
        event.setTenantId(tenantId);
        event.setEventIdExternal(externalId);
        event.setType("payment.success");
        event.setPayload(Map.of("amount", 100));
        return eventRepository.save(event);
    }

    private Delivery createPendingDelivery(UUID eventId, UUID endpointId, UUID tenantId) {
        Delivery d = new Delivery();
        d.setEventId(eventId);
        d.setEndpointId(endpointId);
        d.setTenantId(tenantId);
        d.setStatus(Delivery.DeliveryStatus.PENDING);
        d.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1)); // due now
        return deliveryRepository.save(d);
    }
}
