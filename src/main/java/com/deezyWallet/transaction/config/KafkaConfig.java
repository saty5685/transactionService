package com.deezyWallet.transaction.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka producer and consumer configuration for Transaction Service.
 *
 * PRODUCER (wallet.commands, txn.events):
 *   Same settings as User and Wallet services — acks=all, idempotent, no type headers.
 *   Keyed by transactionId → all events for the same txn go to the same partition.
 *   This guarantees ordering: WALLET_DEBIT_CMD always processed before WALLET_CREDIT_CMD
 *   for the same transaction (assuming same key → same partition).
 *
 * CONSUMER (wallet.events):
 *   Manual ACK — we only ACK after processing is confirmed.
 *   Exponential backoff with DLQ — matches Wallet Service pattern exactly.
 *   Business exceptions (saga step already terminal, duplicate event) → ACK immediately.
 *   System exceptions (DB down, Redis error) → NO ACK → retry with backoff → DLQ.
 *
 * WHY the same group-id for wallet.events as Wallet Service has for wallet.commands?
 *   They're DIFFERENT topics. Wallet Service consumes wallet.commands.
 *   Transaction Service consumes wallet.events.
 *   Group IDs are scoped per topic — no conflict.
 *
 * DLQ TOPIC: wallet.events.DLT
 *   After maxRetries (3), failed events are forwarded here for manual inspection.
 *   Alert on DLT message count in production monitoring.
 */
@Configuration
public class KafkaConfig {

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	// ── Producer ──────────────────────────────────────────────────────────────

	@Bean
	public ProducerFactory<String, Object> producerFactory() {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		props.put(ProducerConfig.ACKS_CONFIG, "all");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		props.put(ProducerConfig.RETRIES_CONFIG, 3);
		props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
		return new DefaultKafkaProducerFactory<>(props);
	}

	@Bean
	public KafkaTemplate<String, Object> kafkaTemplate() {
		return new KafkaTemplate<>(producerFactory());
	}

	// ── Consumer ──────────────────────────────────────────────────────────────

	@Bean
	public ConsumerFactory<String, Object> consumerFactory() {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.deezyWallet.*");
		props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
		props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
				"java.util.LinkedHashMap");
		return new DefaultKafkaConsumerFactory<>(props);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
			ConsumerFactory<String, Object> consumerFactory,
			KafkaTemplate<String, Object>   kafkaTemplate) {

		ConcurrentKafkaListenerContainerFactory<String, Object> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);

		// Manual ACK — we control when to acknowledge
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

		// Exponential backoff: 1s → 2s → 4s, max 3 retries before DLQ
		ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
		backOff.setMaxAttempts(3);

		DefaultErrorHandler errorHandler = new DefaultErrorHandler(
				(record, exception) -> {
					// Send to DLQ — KafkaTemplate publishes to topic.DLT
					kafkaTemplate.send(
							record.topic() + ".DLT",
							record.key() != null ? record.key().toString() : null,
							record.value()
					);
				},
				backOff
		);

		// Business exceptions: ACK immediately, don't retry
		errorHandler.addNotRetryableExceptions(
				IllegalStateException.class  // terminal saga state, duplicate event
		);

		factory.setCommonErrorHandler(errorHandler);
		return factory;
	}


	// ── HTTP client config for sync service calls ─────────────────────────────
	// Using simple RestTemplate for now — WebClient (reactive) is overkill
	// for the low-frequency sync calls (fraud check, user status, balance preflight)

	@Bean
	public org.springframework.web.client.RestTemplate restTemplate(
			com.deezyWallet.transaction.security.InternalAuthInterceptor authInterceptor) {
		// In production: configure connection pool, timeouts, circuit breaker
		var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(2000);  // 2s connect timeout
		factory.setReadTimeout(3000);     // 3s read timeout (covers fraud service SLA)

		var restTemplate = new org.springframework.web.client.RestTemplate(factory);
		restTemplate.getInterceptors().add(authInterceptor);
		return restTemplate;
	}
}
