package com.example.webhook.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint circuit breaker manager using Resilience4j.
 *
 * Each endpoint gets its own independent circuit breaker instance.
 * Endpoint A's failures do NOT affect Endpoint B's circuit state.
 *
 * Circuit breaker lifecycle:
 *   CLOSED   → Normal operation. All calls go through.
 *   OPEN     → Endpoint is failing. Calls are blocked for cooldown window.
 *   HALF_OPEN → Testing recovery. A limited number of test calls are allowed.
 *   CLOSED   → Recovery confirmed. Normal operation resumes.
 *
 * Configuration:
 *   - Sliding window: last 10 calls
 *   - Failure threshold: 50% of calls fail → OPEN
 *   - Wait duration in OPEN: 60 seconds
 *   - Permitted calls in HALF_OPEN: 3
 */
@Component
@Slf4j
public class EndpointCircuitBreakerService {

    private final CircuitBreakerRegistry registry;
    private final CircuitBreakerConfig circuitBreakerConfig;

    // Cache of circuit breaker per endpoint ID
    private final ConcurrentHashMap<UUID, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public EndpointCircuitBreakerService(
            @Value("${resilience4j.circuitbreaker.configs.default.sliding-window-size:10}") int slidingWindowSize,
            @Value("${resilience4j.circuitbreaker.configs.default.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state:60s}") String waitDuration,
            @Value("${resilience4j.circuitbreaker.configs.default.permitted-number-of-calls-in-half-open-state:3}") int permittedInHalfOpen) {

        this.circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(parseDuration(waitDuration))
                .permittedNumberOfCallsInHalfOpenState(permittedInHalfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        this.registry = CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    /**
     * Get or create a circuit breaker for the given endpoint.
     */
    public CircuitBreaker getCircuitBreaker(UUID endpointId) {
        return circuitBreakers.computeIfAbsent(endpointId, id -> {
            String name = "endpoint-" + id;
            CircuitBreaker cb = registry.circuitBreaker(name, circuitBreakerConfig);
            log.debug("Created circuit breaker for endpoint {}", id);
            return cb;
        });
    }

    /**
     * Check if a circuit breaker allows a call to proceed.
     */
    public boolean isCallPermitted(UUID endpointId) {
        CircuitBreaker cb = getCircuitBreaker(endpointId);
        CircuitBreaker.State state = cb.getState();

        if (state == CircuitBreaker.State.OPEN) {
            log.info("Circuit breaker OPEN for endpoint {} — blocking delivery attempt", endpointId);
            return false;
        }
        return true;
    }

    /**
     * Record a successful delivery attempt (drives circuit breaker towards CLOSED).
     */
    public void recordSuccess(UUID endpointId) {
        CircuitBreaker cb = getCircuitBreaker(endpointId);
        cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Record a failed delivery attempt (drives circuit breaker towards OPEN).
     */
    public void recordFailure(UUID endpointId, Throwable throwable) {
        CircuitBreaker cb = getCircuitBreaker(endpointId);
        cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                throwable != null ? throwable : new RuntimeException("Delivery failure"));
    }

    /**
     * Get the current state of an endpoint's circuit breaker.
     */
    public CircuitBreaker.State getState(UUID endpointId) {
        return getCircuitBreaker(endpointId).getState();
    }

    private Duration parseDuration(String durationStr) {
        // Handle Spring-style durations like "60s", "1m", "1h"
        if (durationStr.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(durationStr.replace("s", "")));
        } else if (durationStr.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(durationStr.replace("m", "")));
        } else if (durationStr.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(durationStr.replace("h", "")));
        }
        return Duration.ofSeconds(60); // fallback
    }
}
