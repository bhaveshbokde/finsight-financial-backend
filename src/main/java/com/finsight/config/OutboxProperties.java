package com.finsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code finsight.outbox.*}.
 *
 * @param enabled        when false the poller never runs (used by unit/slice tests)
 * @param pollIntervalMs delay between poller passes
 * @param batchSize      maximum rows claimed per pass
 * @param maxAttempts    publish attempts before a row is parked and reported as stuck
 */
@ConfigurationProperties(prefix = "finsight.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10") int maxAttempts
) {
}
