package com.deezyWallet.transaction.event;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.WalletCommandTypeEnum;
import com.deezyWallet.transaction.event.outbound.WalletCreditCommand;
import com.deezyWallet.transaction.event.outbound.WalletDebitCommand;
import com.deezyWallet.transaction.event.outbound.WalletUnblockCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes commands to the wallet.commands Kafka topic.
 *
 * Commands are keyed by transactionId → all commands for the same transaction
 * land on the same partition → Wallet Service processes them in order.
 *
 * ORDERING GUARANTEE:
 *   WALLET_DEBIT_CMD and WALLET_CREDIT_CMD for the same transaction will always
 *   be on the same partition in order. This prevents CREDIT arriving before DEBIT
 *   (which would credit a balance that hasn't been debited yet).
 *
 * FIRE-AND-FORGET:
 *   All publishes are non-blocking. The KafkaTemplate.send() returns a Future
 *   that we don't await. Failures are logged for manual replay.
 *
 *   WHY not await the Future?
 *     We call these methods from inside @Transactional service methods (or
 *     just after commit). Awaiting the Kafka ACK would hold the DB connection
 *     or delay the HTTP response for broker round-trip time (~5–50ms).
 *     Fire-and-forget keeps latency low. Recovery is via SagaTimeoutJob if
 *     the message was truly lost.
 *
 * All publish methods never throw — failures are logged with enough context
 * for a manual replay script.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletCommandPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	/**
	 * Publishes WALLET_DEBIT_CMD — first Saga step.
	 *
	 * @param txn              the transaction to debit from
	 * @param idempotencyKey   "{transactionId}:DEBIT_SENDER" from SagaState row
	 */
	public void publishDebitCommand(Transaction txn, String idempotencyKey) {
		WalletDebitCommand command = WalletDebitCommand.builder()
				.commandId(UUID.randomUUID().toString())
				.commandType(WalletCommandTypeEnum.WALLET_DEBIT)
				.transactionId(txn.getId())
				.walletId(txn.getSenderWalletId())
				.amount(txn.getAmount())
				.currency(txn.getCurrency())
				.idempotencyKey(idempotencyKey)
				.description(txn.getDescription())
				.issuedAt(LocalDateTime.now())
				.build();

		publish(txn.getId(), command, "WALLET_DEBIT_CMD");
	}

	/**
	 * Publishes WALLET_CREDIT_CMD — second Saga step.
	 * Called after WALLET_DEBITED received from Wallet Service.
	 *
	 * @param txn              the transaction (contains receiver wallet info)
	 * @param idempotencyKey   "{transactionId}:CREDIT_RECEIVER" from SagaState row
	 */
	public void publishCreditCommand(Transaction txn, String idempotencyKey) {
		WalletCreditCommand command = WalletCreditCommand.builder()
				.commandId(UUID.randomUUID().toString())
				.commandType(WalletCommandTypeEnum.WALLET_CREDIT)
				.transactionId(txn.getId())
				.walletId(txn.getReceiverWalletId())
				.amount(txn.getAmount())
				.currency(txn.getCurrency())
				.idempotencyKey(idempotencyKey)
				.description(txn.getDescription())
				.issuedAt(LocalDateTime.now())
				.build();

		publish(txn.getId(), command, "WALLET_CREDIT_CMD");
	}

	/**
	 * Publishes WALLET_UNBLOCK_CMD — compensation step.
	 * Called when credit fails or admin initiates reversal.
	 *
	 * @param txn              the transaction (sender wallet receives the unblock)
	 * @param idempotencyKey   "{transactionId}:REVERSE_DEBIT" from SagaState row
	 */
	public void publishUnblockCommand(Transaction txn, String idempotencyKey) {
		WalletUnblockCommand command = WalletUnblockCommand.builder()
				.commandId(UUID.randomUUID().toString())
				.commandType(WalletCommandTypeEnum.WALLET_UNBLOCK)
				.transactionId(txn.getId())
				.walletId(txn.getSenderWalletId())
				.amount(txn.getAmount())
				.idempotencyKey(idempotencyKey)
				.issuedAt(LocalDateTime.now())
				.build();

		publish(txn.getId(), command, "WALLET_UNBLOCK_CMD");
	}

	// ── Internal publish ──────────────────────────────────────────────────────

	private void publish(String key, Object command, String commandType) {
		try {
			kafkaTemplate.send(TxnConstants.TOPIC_WALLET_COMMANDS, key, command);
			log.debug("Published {}: transactionId={}", commandType, key);
		} catch (Exception e) {
			// Never propagate — SagaTimeoutJob handles the recovery
			log.error("KAFKA PUBLISH FAILED: type={} transactionId={} error={}",
					commandType, key, e.getMessage(), e);
		}
	}
}
