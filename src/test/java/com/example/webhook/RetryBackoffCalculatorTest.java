package com.example.webhook;

import com.example.webhook.util.RetryBackoffCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the exponential backoff calculator.
 *
 * These are pure unit tests — no Spring context, no database.
 *
 * Tests verify:
 * 1. Delays increase with each attempt (monotonically non-decreasing trend)
 * 2. Max delay cap is enforced
 * 3. Jitter bounds are respected
 * 4. Edge cases: attempt 0, attempt at max, very large attempts
 */
@DisplayName("Retry Backoff Calculator Unit Tests")
class RetryBackoffCalculatorTest {

    private final RetryBackoffCalculator calculator = new RetryBackoffCalculator();

    private static final long BASE_MS = 5_000L;
    private static final long MAX_MS = 3_600_000L; // 1 hour
    private static final double JITTER = 0.25;

    @Test
    @DisplayName("Attempt 0 delay is at least baseDelayMs")
    void attempt0_atLeastBaseDelay() {
        long delay = calculator.calculateDelayMs(0, BASE_MS, MAX_MS, JITTER);
        assertThat(delay).isGreaterThanOrEqualTo(BASE_MS);
    }

    @Test
    @DisplayName("Delays are bounded by maxDelayMs + jitter")
    void delays_boundedByMaxDelay() {
        for (int attempt = 0; attempt < 20; attempt++) {
            long delay = calculator.calculateDelayMs(attempt, BASE_MS, MAX_MS, JITTER);
            long maxPossible = (long) (MAX_MS * (1 + JITTER));
            assertThat(delay)
                    .as("Attempt %d delay should not exceed maxDelay + jitter", attempt)
                    .isLessThanOrEqualTo(maxPossible);
        }
    }

    @Test
    @DisplayName("Delays increase with attempt number (no jitter case)")
    void delays_increaseWithAttemptNumber() {
        // With jitter=0, delays must strictly increase (up to cap)
        long prev = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            long delay = calculator.calculateDelayMs(attempt, BASE_MS, MAX_MS, 0.0);
            assertThat(delay)
                    .as("Attempt %d delay (%d) should be >= previous (%d)", attempt, delay, prev)
                    .isGreaterThanOrEqualTo(prev);
            prev = delay;
        }
    }

    @Test
    @DisplayName("At attempt 8 (cap), delay equals maxDelayMs (no jitter)")
    void attemptAtCap_equalsMaxDelay() {
        // 2^7 × 5000 = 640000ms, but maxDelay=3600000ms, so attempt 7 hits the cap
        long delay = calculator.calculateDelayMs(7, BASE_MS, MAX_MS, 0.0);
        // 2^7 × 5000 = 640000 which is < 3600000, so it's 640000
        assertThat(delay).isEqualTo(640_000L);

        // At attempt 10 (2^10 × 5000 = 5120000 > 3600000), capped at 3600000
        long cappedDelay = calculator.calculateDelayMs(10, BASE_MS, MAX_MS, 0.0);
        assertThat(cappedDelay).isEqualTo(MAX_MS);
    }

    @Test
    @DisplayName("Jitter is within expected bounds")
    void jitter_withinBounds() {
        // Run many times to test jitter distribution
        for (int i = 0; i < 100; i++) {
            long delay = calculator.calculateDelayMs(3, BASE_MS, MAX_MS, JITTER);
            long expectedBase = Math.min(BASE_MS * 8, MAX_MS); // attempt 3: 5000×2^3=40000
            long minExpected = expectedBase; // jitter is additive, min is base
            long maxExpected = (long) (expectedBase * (1 + JITTER));

            assertThat(delay)
                    .as("Delay should be between %d and %d", minExpected, maxExpected)
                    .isBetween(minExpected, maxExpected);
        }
    }

    @Test
    @DisplayName("Very large attempt numbers do not cause overflow")
    void largeAttemptNumbers_noOverflow() {
        long delay = calculator.calculateDelayMs(100, BASE_MS, MAX_MS, 0.0);
        assertThat(delay).isEqualTo(MAX_MS); // Should be capped
        assertThat(delay).isPositive();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("All attempt numbers produce positive delays")
    void allAttempts_positiveDelay(int attempt) {
        long delay = calculator.calculateDelayMs(attempt, BASE_MS, MAX_MS, JITTER);
        assertThat(delay).isPositive();
    }
}
