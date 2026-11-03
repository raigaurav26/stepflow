package com.stepflow.definition;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * How long to wait before retrying a failed step: exponential backoff with a cap and jitter.
 *
 * <p>The wait before retry {@code n} is {@code initialDelay * multiplier^(n-1)}, capped at
 * {@code maxDelay}, then reduced by a random amount of up to {@code jitter} of itself.
 * Jitter spreads retries out so that many steps failing at the same moment (a partner API
 * going down) don't all hit it again at the same moment when it comes back.
 *
 * @param initialDelay wait before the first retry
 * @param multiplier   how much the wait grows after each failure, at least 1
 * @param maxDelay     the wait never grows past this
 * @param jitter       0 for exact waits, up to 1 to randomise the whole wait
 */
public record RetryPolicy(Duration initialDelay, double multiplier, Duration maxDelay, double jitter) {

    public static final RetryPolicy DEFAULT =
            new RetryPolicy(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 0.2);

    public RetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Need 0 <= initialDelay <= maxDelay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
    }

    public static RetryPolicy fixed(Duration delay) {
        return new RetryPolicy(delay, 1.0, delay, 0.0);
    }

    public static RetryPolicy exponential(Duration initialDelay, Duration maxDelay) {
        return new RetryPolicy(initialDelay, 2.0, maxDelay, DEFAULT.jitter());
    }

    /** The wait without jitter before the retry that follows failed attempt number {@code attempt}. */
    public Duration baseDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
        double millis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1);
        // pow can overflow to infinity for large attempts, so compare as doubles before converting
        if (millis >= maxDelay.toMillis()) {
            return maxDelay;
        }
        return Duration.ofMillis((long) millis);
    }

    public Duration delayAfter(int attempt, RandomGenerator random) {
        Duration base = baseDelay(attempt);
        if (jitter == 0.0) {
            return base;
        }
        double factor = 1.0 - jitter * random.nextDouble();
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }
}
