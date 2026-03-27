package com.deezyWallet.transaction.event.inbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Received from Wallet Service when a funds-unblock (reversal) command succeeds.
 *
 * Published by Wallet Service after unblockFunds() completes.
 * SagaProcessor.onWalletUnblocked() marks the transaction REVERSED.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletUnblockedEvent {
	private String        eventId;
	private String        eventType;        // "WALLET_UNBLOCKED"
	private String        transactionId;
	private String        walletId;
	private BigDecimal    amountUnblocked;
	private LocalDateTime occurredAt;
}
