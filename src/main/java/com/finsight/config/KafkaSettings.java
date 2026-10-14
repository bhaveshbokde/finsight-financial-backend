package com.finsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code finsight.kafka.*}.
 *
 * <p>Named {@code KafkaSettings} rather than {@code KafkaProperties} to avoid
 * colliding with Spring Boot's own {@code org.springframework.boot.autoconfigure
 * .kafka.KafkaProperties}, which binds the separate {@code spring.kafka.*} tree.
 */
@ConfigurationProperties(prefix = "finsight.kafka")
public record KafkaSettings(Topics topics, Retry retry) {

    /**
     * @param importRequested    topic carrying {@code ImportRequestedEvent}
     * @param importRequestedDlt dead-letter topic for messages that exhausted retries
     */
    public record Topics(
            @DefaultValue("finsight.imports.requested.v1") String importRequested,
            @DefaultValue("finsight.imports.requested.v1.DLT") String importRequestedDlt
    ) {
    }

    /**
     * @param attempts          total delivery attempts, including the first one
     * @param backoffMs         initial delay before the first retry
     * @param backoffMultiplier exponential growth factor applied to {@code backoffMs}
     */
    public record Retry(
            @DefaultValue("3") int attempts,
            @DefaultValue("1000") long backoffMs,
            @DefaultValue("2.0") double backoffMultiplier
    ) {
    }
}
