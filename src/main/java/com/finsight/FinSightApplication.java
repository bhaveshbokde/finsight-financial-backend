package com.finsight;

import com.finsight.config.CacheSettings;
import com.finsight.config.CsvImportProperties;
import com.finsight.config.JwtProperties;
import com.finsight.config.KafkaSettings;
import com.finsight.config.OutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * FinSight — financial transaction management and cash-flow analytics backend.
 *
 * <p>{@code @SpringBootApplication} is a meta-annotation combining
 * {@code @Configuration}, {@code @EnableAutoConfiguration} and
 * {@code @ComponentScan}. Component scanning starts at this package
 * ({@code com.finsight}) and walks every sub-package, which is why the feature
 * packages below it need no explicit registration.
 *
 * <p>Caching is <em>not</em> enabled here: {@code @EnableCaching} lives on
 * {@link com.finsight.config.RedisCacheConfig} so that it can be switched off
 * wholesale via {@code finsight.cache.enabled=false} without touching this class.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        JwtProperties.class,
        CsvImportProperties.class,
        OutboxProperties.class,
        KafkaSettings.class,
        CacheSettings.class
})
@EnableJpaAuditing          // populates @CreatedDate / @LastModifiedDate
@EnableTransactionManagement
@EnableScheduling           // drives the transactional-outbox poller
public class FinSightApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinSightApplication.class, args);
    }
}
