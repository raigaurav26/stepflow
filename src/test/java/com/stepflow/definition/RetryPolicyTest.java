package com.stepflow.definition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 0.0);

    @Test
    void waitDoublesAfterEachFailure() {
        assertEquals(Duration.ofSeconds(1), policy.baseDelay(1));
        assertEquals(Duration.ofSeconds(2), policy.baseDelay(2));
        assertEquals(Duration.ofSeconds(4), policy.baseDelay(3));
        assertEquals(Duration.ofSeconds(16), policy.baseDelay(5));
    }

    @Test
    void waitIsCappedAtMaxDelay() {
        assertEquals(Duration.ofSeconds(30), policy.baseDelay(6));
        assertEquals(Duration.ofSeconds(30), policy.baseDelay(10_000), "huge attempt counts must not overflow");
    }

    @Test
    void jitterOnlyEverShortensTheWaitWithinItsBound() {
        RetryPolicy jittered = new RetryPolicy(Duration.ofSeconds(10), 1.0, Duration.ofSeconds(10), 0.3);
        Random random = new Random(42);
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 1_000; i++) {
            long millis = jittered.delayAfter(1, random).toMillis();
            min = Math.min(min, millis);
            max = Math.max(max, millis);
        }
        assertTrue(min >= 7_000, "never below 70% of the base wait, was " + min);
        assertTrue(max <= 10_000, "never above the base wait, was " + max);
        assertTrue(max - min > 2_000, "waits should actually be spread out");
    }

    @Test
    void zeroJitterIsExact() {
        assertEquals(Duration.ofSeconds(4), policy.delayAfter(3, new Random()));
    }

    @Test
    void fixedPolicyNeverGrows() {
        RetryPolicy fixed = RetryPolicy.fixed(Duration.ofMillis(250));
        assertEquals(Duration.ofMillis(250), fixed.delayAfter(1, new Random()));
        assertEquals(Duration.ofMillis(250), fixed.delayAfter(9, new Random()));
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofSeconds(5), 2.0, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(5), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 1.5));
        assertThrows(IllegalArgumentException.class, () -> policy.baseDelay(0));
    }
}
