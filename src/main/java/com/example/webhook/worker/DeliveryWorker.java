package com.example.webhook.worker;

import com.example.webhook.circuitbreaker.EndpointCircuitBreakerService;
import com.example.webhook.entity.Delivery;
import com.example.webhook.entity.Delivery.DeliveryStatus;
import com.example.webhook.entity.DeliveryAttempt;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.entity.Event;
import com.example.webhook.repository.DeliveryAttemptRepository;
import com.example.webhook.repository.DeliveryRepository;
import com.example.webhook.repository.EndpointRepository;
import com.example.webhook.repository.EventRepository;
import com.example.webhook.service.WebhookClient;
import com.example.webhook.service.WebhookClient.DeliveryResult;
import com.example.webhook.util.RetryBackoffCalculator;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background delivery worker pool.
 *
 * Execution flow — TWO SEPARATE TRANSACTIONS (this is mandatory):
 *
 * Transaction 1 — Claim:
 *   SELECT deliveries WHERE status=PENDING AND due FOR UPDATE SKIP LOCKED LIMIT n
 *   UPDATE delivery SET status=IN_PROGRESS, locked_by=worker-id, locked_until=now+lease
 *   COMMIT  ← lease is committed, worker crash will auto-expire after locked_until
 *
 * [No DB lock held during HTTP call — transaction 1 is fully committed]
 *
 * HTTP Call — OUTSIDE any transaction:
 *   POST signed payload to external endpoint
 *   Wait for response (bounded by connect/read timeouts)
 *
 * Transaction 2 — Record result:
 *   INSERT delivery_attempt (immutable row — never updated)
 *   UPDATE delivery (status, retry scheduling, response code/snippet)
 *   COMMIT
 *
 * Why this matters:
 * - Holding a DB transaction during HTTP calls would exhaust the connection pool
 * - A hung endpoint would block DB connections indefinitely
 * - SKIP LOCKED ensures concurrent workers never steal each other's claimed deliveries
 * - Committed leases survive worker crashes — another worker reclaims after expiry
 */
@Component
@Slf4j
public class DeliveryWorker {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final EndpointRepository endpointRepository;
    private final EventRepository eventRepository;
    private final WebhookClient webhookClient;
    private final RetryBackoffCalculator backoffCalculator;
    private final EndpointCircuitBreakerService circuitBreakerService;
    private final TransactionTemplate transactionTemplate;

    private final int batchSize;
    private final int leaseDurationSeconds;
    private final int maxAttempts;
    private final ExecutorService executorService;
    private final String workerId;

    public DeliveryWorker(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository attemptRepository,
            EndpointRepository endpointRepository,
            EventRepository eventRepository,
            WebhookClient webhookClient,
            RetryBackoffCalculator backoffCalculator,
            EndpointCircuitBreakerService circuitBreakerService,
            PlatformTransactionManager transactionManager,
            @Value("${webhook.worker.batch-size:10}") int batchSize,
            @Value("${webhook.worker.lease-duration-seconds:60}") int leaseDurationSeconds,
            @Value("${webhook.retry.max-attempts:8}") int maxAttempts,
            @Value("${webhook.worker.thread-pool-size:5}") int threadPoolSize) {

        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.webhookClient = webhookClient;
        this.backoffCalculator = backoffCalculator;
        this.circuitBreakerService = circuitBreakerService;
        this.batchSize = batchSize;
        this.leaseDurationSeconds = leaseDurationSeconds;
        this.maxAttempts = maxAttempts;
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

        // Bounded thread pool — limits parallelism, prevents a single slow endpoint
        // from consuming unlimited worker resources
        this.executorService = Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "delivery-worker");
            t.setDaemon(true);
            return t;
        });

        // Requires new transaction per call — never inherits caller's transaction
        TransactionTemplate tmpl = new TransactionTemplate(transactionManager);
        tmpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = tmpl;

        log.info("DeliveryWorker started: workerId={} batchSize={} leaseSec={} maxAttempts={}",
                workerId, batchSize, leaseDurationSeconds, maxAttempts);
    }

    /**
     * Main polling loop — runs every poll-interval milliseconds.
     * Claims a batch of due deliveries and dispatches each to the bounded thread pool.
     */
    @Scheduled(fixedDelayString = "${webhook.worker.poll-interval-ms:5000}")
    public void pollAndDispatch() {
        List<Delivery> claimed = claimBatch();
        if (!claimed.isEmpty()) {
            log.debug("Worker {} claimed {} deliveries", workerId, claimed.size());
            for (Delivery delivery : claimed) {
                executorService.submit(() -> processDelivery(delivery));
            }
        }
    }

    /**
     * Transaction 1: Claim a batch of due deliveries atomically.
     *
     * SELECT ... FOR UPDATE SKIP LOCKED guarantees:
     * - Only PENDING deliveries past their next_attempt_at are selected
     * - Rows locked by other workers are silently skipped (no wait, no deadlock)
     * - This worker gets an exclusive set — no other worker can claim the same rows
     * - The lease (locked_until) is committed BEFORE any HTTP call happens
     */
    public List<Delivery> claimBatch() {
        return transactionTemplate.execute(status -> {
            List<Delivery> due = deliveryRepository.findDueDeliveriesForUpdate(batchSize);

            if (due.isEmpty()) {
                return due;
            }

            OffsetDateTime leaseExpiry = OffsetDateTime.now().plusSeconds(leaseDurationSeconds);
            for (Delivery d : due) {
                d.setStatus(DeliveryStatus.IN_PROGRESS);
                d.setLockedBy(workerId);
                d.setLockedUntil(leaseExpiry);
                d.setUpdatedAt(OffsetDateTime.now());
            }
            deliveryRepository.saveAll(due);
            return due;
        });
    }

    /**
     * Process one delivery entirely outside any database transaction.
     *
     * Flow:
     * 1. Load endpoint and event (separate short reads — no long-running txn)
     * 2. Check circuit breaker
     * 3. Make HTTP call (no DB lock held)
     * 4. Record result in Transaction 2
     */
    public void processDelivery(Delivery delivery) {
        MDC.put("deliveryId", delivery.getId().toString());
        MDC.put("tenantId", delivery.getTenantId().toString());

        try {
            // Short read to get current endpoint state
            Optional<Endpoint> endpointOpt = endpointRepository.findById(delivery.getEndpointId());
            if (endpointOpt.isEmpty()) {
                log.warn("Endpoint {} deleted, dead-lettering delivery {}", delivery.getEndpointId(), delivery.getId());
                recordResult(delivery, null, "Endpoint deleted", true);
                return;
            }

            Endpoint endpoint = endpointOpt.get();

            if (endpoint.getStatus() == Endpoint.EndpointStatus.DISABLED) {
                log.warn("Endpoint {} DISABLED, dead-lettering delivery {}", endpoint.getId(), delivery.getId());
                recordResult(delivery, null, "Endpoint disabled", true);
                return;
            }

            // Check circuit breaker state before attempting HTTP call
            if (!circuitBreakerService.isCallPermitted(endpoint.getId())) {
                postponeDelivery(delivery);
                return;
            }

            // Load event payload for the webhook body
            Object webhookPayload = buildWebhookPayload(delivery);

            // ===== HTTP CALL — no DB transaction held =====
            DeliveryResult result = webhookClient.deliver(endpoint.getUrl(), endpoint.getSecret(), webhookPayload);
            // ===== END HTTP CALL =====

            // Record success/failure with circuit breaker
            if (result.isSuccess()) {
                circuitBreakerService.recordSuccess(endpoint.getId());
            } else {
                String errMsg = result.getError() != null ? result.getError()
                        : "HTTP " + result.getResponseCode();
                circuitBreakerService.recordFailure(endpoint.getId(), new RuntimeException(errMsg));
            }

            recordResult(delivery, result, null, false);

        } catch (Exception e) {
            log.error("Unexpected error processing delivery {}: {}", delivery.getId(), e.getClass().getSimpleName());
            DeliveryResult errorResult = DeliveryResult.error(0L,
                    e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 200));
            recordResult(delivery, errorResult, null, false);
        } finally {
            MDC.remove("deliveryId");
            MDC.remove("tenantId");
        }
    }

    /**
     * Transaction 2: Record the delivery attempt and update delivery state.
     *
     * The delivery_attempt row is IMMUTABLE — never updated after insert.
     * The delivery row is updated to reflect the new state (DELIVERED, PENDING+retry, or DEAD_LETTERED).
     */
    private void recordResult(Delivery delivery, DeliveryResult result, String forceDeadLetterReason, boolean forceDeadLetter) {
        transactionTemplate.execute(status -> {
            // Re-read delivery to get current state (another worker might not have — but this is our exclusive claim)
            Delivery fresh = deliveryRepository.findById(delivery.getId()).orElse(delivery);
            int attemptNumber = fresh.getAttemptCount() + 1;

            // INSERT immutable attempt record
            DeliveryAttempt attempt = new DeliveryAttempt();
            attempt.setDeliveryId(fresh.getId());
            attempt.setAttemptNumber(attemptNumber);
            if (result != null) {
                attempt.setResponseCode(result.getResponseCode());
                attempt.setLatencyMs(result.getLatencyMs());
                attempt.setError(result.getError());
                attempt.setResponseSnippet(result.getResponseSnippet());
            }
            if (forceDeadLetterReason != null) {
                attempt.setError(forceDeadLetterReason);
            }
            attemptRepository.save(attempt);

            // UPDATE delivery state
            fresh.setAttemptCount(attemptNumber);
            fresh.setLockedBy(null);
            fresh.setLockedUntil(null);
            fresh.setUpdatedAt(OffsetDateTime.now());

            if (forceDeadLetter) {
                fresh.setStatus(DeliveryStatus.DEAD_LETTERED);
                fresh.setLastResponseSnippet(forceDeadLetterReason);
                log.warn("Delivery {} DEAD_LETTERED ({})", fresh.getId(), forceDeadLetterReason);

            } else if (result != null && result.isSuccess()) {
                fresh.setStatus(DeliveryStatus.DELIVERED);
                fresh.setLastResponseCode(result.getResponseCode());
                fresh.setLastResponseSnippet(result.getResponseSnippet());
                log.info("Delivery {} DELIVERED after {} attempt(s) latencyMs={}",
                        fresh.getId(), attemptNumber, result.getLatencyMs());

            } else {
                // Failure: schedule retry or dead-letter
                if (result != null) {
                    fresh.setLastResponseCode(result.getResponseCode());
                    fresh.setLastResponseSnippet(result.getResponseSnippet());
                }

                if (attemptNumber >= maxAttempts) {
                    fresh.setStatus(DeliveryStatus.DEAD_LETTERED);
                    log.warn("Delivery {} DEAD_LETTERED after max {} attempts", fresh.getId(), maxAttempts);
                } else {
                    long delayMs = backoffCalculator.calculateDelayMs(attemptNumber - 1);
                    fresh.setStatus(DeliveryStatus.PENDING);
                    fresh.setNextAttemptAt(OffsetDateTime.now().plusNanos(delayMs * 1_000_000L));
                    log.info("Delivery {} will retry in {}ms (attempt {}/{})",
                            fresh.getId(), delayMs, attemptNumber, maxAttempts);
                }
            }

            deliveryRepository.save(fresh);
            return null;
        });
    }

    /**
     * Postpone a delivery when the circuit breaker is OPEN.
     * Does NOT increment attempt count — circuit breaker blocking is not a real attempt.
     */
    private void postponeDelivery(Delivery delivery) {
        transactionTemplate.execute(status -> {
            Delivery fresh = deliveryRepository.findById(delivery.getId()).orElse(delivery);
            fresh.setStatus(DeliveryStatus.PENDING);
            fresh.setLockedBy(null);
            fresh.setLockedUntil(null);
            fresh.setNextAttemptAt(OffsetDateTime.now().plusSeconds(leaseDurationSeconds));
            fresh.setUpdatedAt(OffsetDateTime.now());
            deliveryRepository.save(fresh);
            log.info("Delivery {} postponed — circuit OPEN for endpoint {}", fresh.getId(), fresh.getEndpointId());
            return null;
        });
    }

    /**
     * Build the webhook payload that will be POSTed to the subscriber.
     * Wraps the original event payload in a standard envelope.
     */
    private Object buildWebhookPayload(Delivery delivery) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("deliveryId", delivery.getId().toString());
        envelope.put("eventId", delivery.getEventId().toString());
        envelope.put("tenantId", delivery.getTenantId().toString());
        envelope.put("attemptNumber", delivery.getAttemptCount() + 1);
        envelope.put("deliveredAt", System.currentTimeMillis());

        // Load actual event data
        eventRepository.findById(delivery.getEventId()).ifPresent(event -> {
            envelope.put("type", event.getType());
            envelope.put("externalEventId", event.getEventIdExternal());
            envelope.put("payload", event.getPayload());
            envelope.put("eventCreatedAt", event.getCreatedAt().toString());
        });

        return envelope;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public String getWorkerId() { return workerId; }
}
