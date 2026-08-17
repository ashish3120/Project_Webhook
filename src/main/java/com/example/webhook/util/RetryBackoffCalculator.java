package com.example.webhook.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with bounded jitter.
 *
 * Formula:
 *   delay = min(maxDelayMs, baseDelayMs × 2^attempt) + jitter
 *   jitter = uniform random in [0, jitterFactor × delay]
 *
 * This means:
 *   Attempt 0 → ~5s + jitter
 *   Attempt 1 → ~10s + jitter
 *   Attempt 2 → ~20s + jitter
 *   Attempt 3 → ~40s + jitter
 *   Attempt 4 → ~80s + jitter
 *   Attempt 5 → ~160s + jitter
 *   Attempt 6 → ~320s + jitter (capped at maxDelay = 1h)
 *   Attempt 7 → capped at 1h + jitter
 *   (total spread: ~24h over 8 attempts)
 *
 * Jitter is ESSENTIAL to prevent thundering-herd when many deliveries fail simultaneously.
 */
@Component
public class RetryBackoffCalculator {

    @Value("${webhook.retry.base-delay-ms:5000}")
    private long baseDelayMs;

    @Value("${webhook.retry.max-delay-ms:3600000}")
    private long maxDelayMs;

    @Value("${webhook.retry.jitter-factor:0.25}")
    private double jitterFactor;

    /**
     * Calculate the delay in milliseconds before the next retry attempt.
     *
     * @param attemptNumber 0-based attempt number (0 = first attempt has just failed)
     * @return delay in milliseconds before next attempt
     */
    public long calculateDelayMs(int attemptNumber) {
        // Exponential growth: base × 2^attempt
        long exponential = baseDelayMs * (1L << Math.min(attemptNumber, 30)); // cap bit shift
        long cappedDelay = Math.min(exponential, maxDelayMs);

        // Add random jitter in [0, jitterFactor × cappedDelay]
        long maxJitter = (long) (cappedDelay * jitterFactor);
        long jitter = maxJitter > 0 ? ThreadLocalRandom.current().nextLong(0, maxJitter + 1) : 0;

        return cappedDelay + jitter;
    }

    // Testable version with explicit config
    public long calculateDelayMs(int attemptNumber, long baseMs, long maxMs, double jitter) {
        long exponential = baseMs * (1L << Math.min(attemptNumber, 30));
        long capped = Math.min(exponential, maxMs);
        long maxJitterMs = (long) (capped * jitter);
        long jitterMs = maxJitterMs > 0 ? ThreadLocalRandom.current().nextLong(0, maxJitterMs + 1) : 0;
        return capped + jitterMs;
    }
}
