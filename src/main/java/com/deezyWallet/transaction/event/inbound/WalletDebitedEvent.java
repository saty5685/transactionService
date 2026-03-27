package com.deezyWallet.transaction.event.inbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Received from Wallet Service on wallet.events topic when a debit succeeds.
 *
 * Wallet Service publishes this after successfully debiting the sender's wallet.
 * SagaProcessor.onWalletDebited() consumes it to advance the Saga.
 *
 * balanceAfter: stored for audit but not used for business logic.
 *   The authoritative balance is always in Wallet Service's DB.
 *   We never cache it here to avoid stale-balance inconsistencies.
 *
 * Immutable: @Getter only — events are never mutated after deserialization.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletDebitedEvent {
	private String        eventId;          // UUID — for Kafka deduplication
	private String        eventType;        // "WALLET_DEBITED"
	private String        transactionId;    // correlates back to our Transaction row
	private String        walletId;
	private String        userId;
	private BigDecimal    amount;
	private BigDecimal    balanceAfter;     // informational
	private String        idempotencyKey;   // echoes the command's idempotency key
	private LocalDateTime occurredAt;
}
