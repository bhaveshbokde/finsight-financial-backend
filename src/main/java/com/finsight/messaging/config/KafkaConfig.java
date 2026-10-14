package com.finsight.messaging.config;

import com.finsight.config.KafkaSettings;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka topics, retry policy and dead-letter handling.
 *
 * <h2>Retry strategy</h2>
 * The listener container retries a failed record in place with exponential
 * backoff. After the configured number of attempts the record is handed to a
 * {@link DeadLetterPublishingRecoverer}, which copies it — headers, key and value
 * intact, plus diagnostic headers naming the original topic, partition, offset and
 * exception — onto the {@code .DLT} topic, and the offset is then committed so the
 * consumer moves on.
 *
 * <p>Without a dead-letter step, a message the consumer can never handle (a
 * "poison pill") blocks its partition forever: the container retries, fails,
 * declines to commit, re-polls the same offset, and no later message is ever
 * processed.
 *
 * <h2>Non-retryable failures</h2>
 * Deserialisation and validation errors are classified as non-retryable and go
 * straight to the DLT. Retrying them wastes the whole backoff budget on an outcome
 * that cannot change — the bytes will not become valid on the third attempt.
 *
 * <h2>What retry does NOT protect against</h2>
 * Retries are for <em>transient</em> faults: a brief database blip, a lock timeout.
 * They do not make processing safe to repeat — that comes from the idempotency
 * ledger ({@code processed_events}) and the unique index on
 * {@code (user_id, external_ref)}. Retry without idempotency is how duplicate
 * financial records get created.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Single partition and replication factor 1, matching the single-broker
     * development setup in docker-compose.yml. A real deployment would use
     * {@code replicas >= 3} with {@code min.insync.replicas = 2}; these values are
     * intentionally the smallest that work locally, and are called out in docs/08
     * rather than presented as production settings.
     */
    private static final int PARTITIONS = 1;
    private static final short REPLICAS = 1;

    private final KafkaSettings settings;

    public KafkaConfig(KafkaSettings settings) {
        this.settings = settings;
    }

    /**
     * Declares the main topic.
     *
     * <p>{@code NewTopic} beans are created by Spring's {@code KafkaAdmin} at
     * startup if the broker allows it. Explicit creation beats relying on
     * {@code auto.create.topics.enable}, which produces a topic with the broker's
     * defaults rather than the ones this application wants — and is switched off in
     * most real clusters.
     *
     * <p>When no broker is reachable, topic creation simply fails and is logged;
     * the application still starts, because an unavailable Kafka must not prevent
     * the synchronous REST API from serving traffic.
     */
    @Bean
    public NewTopic importRequestedTopic() {
        return TopicBuilder.name(settings.topics().importRequested())
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic importRequestedDltTopic() {
        return TopicBuilder.name(settings.topics().importRequestedDlt())
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /**
     * Listener container factory carrying the retry + DLT policy.
     *
     * <p>Named explicitly and referenced from {@code @KafkaListener(containerFactory
     * = ...)} so there is no doubt about which policy a listener is running under.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> finsightListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler(kafkaTemplate));
        return factory;
    }

    private DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var retry = settings.retry();

        ExponentialBackOff backOff = new ExponentialBackOff(retry.backoffMs(), retry.backoffMultiplier());
        // attempts includes the initial try, so the number of *retries* is one fewer.
        backOff.setMaxAttempts(Math.max(retry.attempts() - 1, 0));
        backOff.setMaxInterval(30_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    String dlt = settings.topics().importRequestedDlt();
                    log.error("Routing record to dead-letter topic {}: topic={} partition={} offset={} cause={}",
                            dlt, record.topic(), record.partition(), record.offset(), exception.toString());
                    // Same partition number on the DLT keeps ordering within a key.
                    return new org.apache.kafka.common.TopicPartition(dlt, record.partition());
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Retrying these can never succeed: the payload itself is wrong.
        handler.addNotRetryableExceptions(
                ConversionException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);

        AtomicInteger observed = new AtomicInteger();
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retry {} for record from topic={} offset={} (total observed failures {}): {}",
                        deliveryAttempt, record.topic(), record.offset(),
                        observed.incrementAndGet(), ex.toString()));

        return handler;
    }
}
