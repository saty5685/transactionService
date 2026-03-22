package com.deezyWallet.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Wallet Service — Spring Boot entry point.
 *
 * Annotations explained:
 *
 * @SpringBootApplication
 *   — Component scan, auto-configuration, @Configuration rollup.
 *
 * @EnableJpaAuditing
 *   — Activates @CreatedDate / @LastModifiedDate on entities.
 *   — Requires @EntityListeners(AuditingEntityListener.class) on each entity.
 *
 * @EnableKafka
 *   — Activates @KafkaListener scanning.
 *
 * @EnableAsync
 *   — Enables @Async for fire-and-forget operations (e.g. cache warming).
 *
 * @EnableConfigurationProperties
 *   — Activates @ConfigurationProperties beans (JwtProperties, etc.).
 */
@EnableJpaAuditing
@EnableJpaRepositories
@EnableKafka
@EnableAsync
@EnableConfigurationProperties
@SpringBootApplication
public class TransactionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransactionServiceApplication.class, args);
	}

}
