package com.deezyWallet.transaction.event.inbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Received from Wallet Service when a credit command succeeds.
 *
 * SagaProcessor.onWalletCredited() marks the transaction COMPLETED
 * and publishes TXN_COMPLETED to the txn.events topic.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletCreditedEvent {
	private String        eventId;
	private String        eventType;        // "WALLET_CREDITED"
	private String        transactionId;
	private String        walletId;
	private String        userId;
	private BigDecimal    amount;
	private BigDecimal    balanceAfter;
	private String        idempotencyKey;
	private LocalDateTime occurredAt;
}
