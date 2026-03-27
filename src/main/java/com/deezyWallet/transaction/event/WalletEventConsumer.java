package com.deezyWallet.transaction.event;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.deezyWallet.transaction.event.inbound.WalletCreditedEvent;
import com.deezyWallet.transaction.event.inbound.WalletDebitFailedEvent;
import com.deezyWallet.transaction.event.inbound.WalletDebitedEvent;
import com.deezyWallet.transaction.event.inbound.WalletUnblockedEvent;
import com.deezyWallet.transaction.service.SagaProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes events from wallet.events — the Saga's response channel.
 *
 * WHY a single @KafkaListener for the whole topic vs one per event type?
 *   Wallet Service publishes multiple event types to wallet.events.
 *   A single listener with a routing switch is simpler to manage than
 *   multiple listeners that would each consume ALL events and skip most.
 *   With Spring Kafka's filter support we could filter — but a routing
 *   switch is more explicit and easier to trace in logs.
 *
 * ACK MATRIX (mirrors Wallet Service's approach for wallet.commands):
 * ─────────────────────────────────────────────────────────────────────
 *  WALLET_DEBITED          → process + ACK
 *  WALLET_DEBIT_FAILED     → process + ACK  (business outcome, not a system error)
 *  WALLET_CREDITED         → process + ACK
 *  WALLET_UNBLOCKED        → process + ACK
 *  Unknown eventType       → ACK  (forward compatible — ignore unknown types)
 *  Malformed JSON          → ACK  (skip + log — don't block partition)
 *  Business exception      → ACK  (e.g. duplicate event, already terminal)
 *  System exception (DB/Redis down) → NO ACK → retry with backoff → DLQ
 * ─────────────────────────────────────────────────────────────────────
 *
 * DESERIALISATION STRATEGY:
 *   Messages are received as Map<String, Object> (JSON deserialized without
 *   type headers). We inspect the "eventType" field and manually deserialize
 *   to the specific event class using ObjectMapper.convertValue().
 *
 *   WHY not use @KafkaHandler with class-based routing?
 *     Class-based routing requires type headers — which we disabled on the
 *     producer side (spring.json.add.type.headers=false) for cross-language
 *     compatibility. Manual routing gives us the same result without headers.
 *
 * CONCURRENCY:
 *   @KafkaListener(concurrency = "3") — 3 consumer threads per partition.
 *   Adjusted in application.yml per environment.
 *   Messages for the same transactionId go to the same partition (keyed publish)
 *   so ordering is preserved within a transaction even with 3 threads.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletEventConsumer {

	private final SagaProcessor sagaProcessor;
	private final ObjectMapper  objectMapper;

	@KafkaListener(
			topics         = "${kafka.topics.wallet-events:wallet.events}",
			groupId        = "${spring.kafka.consumer.group-id}",
			containerFactory = "kafkaListenerContainerFactory",
			concurrency    = "${kafka.consumer.concurrency:3}"
	)
	public void consume(ConsumerRecord<String, Object> record, Acknowledgment ack) {
		String rawKey = record.key();

		try {
			// Deserialize the raw value to a Map to inspect eventType
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = objectMapper.convertValue(record.value(), Map.class);

			String eventType = (String) payload.get("eventType");
			if (eventType == null) {
				log.warn("wallet.events: missing eventType field, key={} — skipping", rawKey);
				ack.acknowledge();
				return;
			}

			routeEvent(eventType, payload, rawKey);
			ack.acknowledge();

		} catch (IllegalStateException e) {
			// Business exception (duplicate event, already terminal state)
			// ACK — re-processing won't help
			log.warn("wallet.events: business exception processing key={} error={} — ACKing",
					rawKey, e.getMessage());
			ack.acknowledge();

		} catch (Exception e) {
			// System exception (DB down, Redis error, etc.)
			// DO NOT ACK → Kafka will redeliver → exponential backoff → DLQ
			log.error("wallet.events: system error processing key={} error={} — NOT ACKing for retry",
					rawKey, e.getMessage(), e);
			// No ack.acknowledge() call — Kafka redelivers after backoff
		}
	}

	private void routeEvent(String eventType, Map<String, Object> payload, String key) {
		switch (eventType) {
			case "WALLET_DEBITED" -> {
				WalletDebitedEvent event = objectMapper.convertValue(payload, WalletDebitedEvent.class);
				log.debug("Processing WALLET_DEBITED: txnId={}", event.getTransactionId());
				sagaProcessor.onWalletDebited(event);
			}
			case "WALLET_DEBIT_FAILED" -> {
				WalletDebitFailedEvent event = objectMapper.convertValue(payload, WalletDebitFailedEvent.class);
				log.debug("Processing WALLET_DEBIT_FAILED: txnId={} reason={}",
						event.getTransactionId(), event.getFailureReason());
				sagaProcessor.onWalletDebitFailed(event);
			}
			case "WALLET_CREDITED" -> {
				WalletCreditedEvent event = objectMapper.convertValue(payload, WalletCreditedEvent.class);
				log.debug("Processing WALLET_CREDITED: txnId={}", event.getTransactionId());
				sagaProcessor.onWalletCredited(event);
			}
			case "WALLET_UNBLOCKED" -> {
				WalletUnblockedEvent event = objectMapper.convertValue(payload, WalletUnblockedEvent.class);
				log.debug("Processing WALLET_UNBLOCKED: txnId={}", event.getTransactionId());
				sagaProcessor.onWalletUnblocked(event.getTransactionId());
			}
			default -> {
				// Unknown event type — ACK and move on (forward compatible)
				log.debug("wallet.events: unknown eventType='{}' key={} — ignoring", eventType, key);
			}
		}
	}
}
