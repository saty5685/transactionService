package com.deezyWallet.transaction.event;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.TxnEventTypeEnum;
import com.deezyWallet.transaction.event.outbound.TxnCompletedEvent;
import com.deezyWallet.transaction.event.outbound.TxnFailedEvent;
import com.deezyWallet.transaction.event.outbound.TxnReversedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes domain events to the txn.events Kafka topic.
 *
 * Events are keyed by transactionId — same ordering guarantee as commands.
 * All publishes are fire-and-forget with failure logging.
 *
 * Consumers of txn.events:
 *   Notification Service — TXN_INITIATED, TXN_COMPLETED, TXN_FAILED, TXN_REVERSED
 *   Ledger Service       — TXN_COMPLETED, TXN_REVERSED
 *
 * WHY publish TXN_INITIATED?
 *   Notification Service can send an immediate "your transfer is being processed"
 *   push notification. This reduces user anxiety while the Saga runs.
 *   Without it, the user sees nothing for up to 5 seconds (Kafka round-trip × 2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TxnEventPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void publishTxnInitiated(Transaction txn) {
		// Lightweight event — just transactionId and amount
		// Notification Service shows "Processing your ₹X transfer" push
		var event = new java.util.HashMap<String, Object>();
		event.put("eventId",       UUID.randomUUID().toString());
		event.put("eventType",     TxnEventTypeEnum.TXN_INITIATED.name());
		event.put("transactionId", txn.getId());
		event.put("senderUserId",  txn.getSenderUserId());
		event.put("amount",        txn.getAmount());
		event.put("currency",      txn.getCurrency());
		event.put("occurredAt",    LocalDateTime.now().toString());
		publish(txn.getId(), event, TxnEventTypeEnum.TXN_INITIATED);
	}

	public void publishTxnCompleted(Transaction txn) {
		TxnCompletedEvent event = TxnCompletedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(TxnEventTypeEnum.TXN_COMPLETED)
				.transactionId(txn.getId())
				.transactionType(txn.getType().name())
				.senderUserId(txn.getSenderUserId())
				.senderWalletId(txn.getSenderWalletId())
				.receiverUserId(txn.getReceiverUserId())
				.receiverWalletId(txn.getReceiverWalletId())
				.amount(txn.getAmount())
				.currency(txn.getCurrency())
				.completedAt(txn.getCompletedAt())
				.occurredAt(LocalDateTime.now())
				.build();
		publish(txn.getId(), event, TxnEventTypeEnum.TXN_COMPLETED);
	}

	public void publishTxnFailed(Transaction txn, String reason) {
		TxnFailedEvent event = TxnFailedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(TxnEventTypeEnum.TXN_FAILED)
				.transactionId(txn.getId())
				.senderUserId(txn.getSenderUserId())
				.amount(txn.getAmount())
				.failureReason(reason)
				.occurredAt(LocalDateTime.now())
				.build();
		publish(txn.getId(), event, TxnEventTypeEnum.TXN_FAILED);
	}

	public void publishTxnReversed(Transaction txn) {
		TxnReversedEvent event = TxnReversedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(TxnEventTypeEnum.TXN_REVERSED)
				.transactionId(txn.getId())
				.senderUserId(txn.getSenderUserId())
				.senderWalletId(txn.getSenderWalletId())
				.amount(txn.getAmount())
				.reversedAt(txn.getCompletedAt())
				.occurredAt(LocalDateTime.now())
				.build();
		publish(txn.getId(), event, TxnEventTypeEnum.TXN_REVERSED);
	}

	private void publish(String key, Object event, TxnEventTypeEnum type) {
		try {
			kafkaTemplate.send(TxnConstants.TOPIC_TXN_EVENTS, key, event);
			log.debug("Published {}: transactionId={}", type, key);
		} catch (Exception e) {
			log.error("KAFKA PUBLISH FAILED: type={} transactionId={} error={}",
					type, key, e.getMessage(), e);
		}
	}
}
