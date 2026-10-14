package com.finsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code finsight.cache.*}.
 *
 * <p>Named {@code CacheSettings} to avoid colliding with Spring Boot's
 * {@code spring.cache.*} binding class.
 *
 * @param enabled     master switch; false disables {@code @Cacheable} entirely
 * @param reportTtl   TTL for report caches — short, because a new transaction changes them
 * @param categoryTtl TTL for the category list — longer, because it changes rarely
 */
@ConfigurationProperties(prefix = "finsight.cache")
public record CacheSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT10M") Duration reportTtl,
        @DefaultValue("PT30M") Duration categoryTtl
) {
}
