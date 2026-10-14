package com.finsight.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics configuration.
 *
 * <p>Micrometer is a facade: the application records meters through its API and a
 * registry implementation decides where they go. Here the Prometheus registry is on
 * the classpath, so {@code /actuator/prometheus} exposes them in text format — but
 * no application code mentions Prometheus, so swapping the backend is a dependency
 * change rather than a code change.
 *
 * <p>Custom meters defined elsewhere in the application:
 * <ul>
 *   <li>{@code finsight.outbox.published} / {@code .publish.failures} — counters</li>
 *   <li>{@code finsight.outbox.pending} / {@code .parked} — gauges; a rising
 *       {@code parked} is the signal that Kafka publication is broken</li>
 *   <li>{@code finsight.import.rows{outcome=imported|duplicate|failed}}</li>
 *   <li>{@code finsight.kafka.events{outcome=processed|skipped_duplicate}}</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Tags every meter with the application name.
     *
     * <p>Applied centrally rather than at each call site, so a metrics backend
     * scraping several services can distinguish them without each meter having to
     * remember the tag.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
                .commonTags("application", "finsight")
                // Publish percentiles for HTTP latency. A mean alone hides the tail,
                // and the tail is what users actually experience.
                .meterFilter(MeterFilter.maxExpected("http.server.requests",
                        java.time.Duration.ofSeconds(10)));
    }

    /** Enables {@code @Timed} on individual methods where a dedicated timer is worth the overhead. */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
