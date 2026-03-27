package com.deezyWallet.transaction.event.inbound;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Received from Wallet Service when a debit command fails.
 *
 * Common failure reasons published by Wallet Service:
 *   INSUFFICIENT_BALANCE   — sender didn't have enough available balance
 *                            (can happen if another concurrent debit won the race)
 *   WALLET_FROZEN          — wallet was frozen between preflight and debit
 *   WALLET_LIMIT_EXCEEDED  — daily/monthly spending limit hit
 *   WALLET_NOT_FOUND       — walletId in the command was invalid
 *
 * SagaProcessor.onWalletDebitFailed() marks the transaction FAILED.
 * No compensation needed — no money moved.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletDebitFailedEvent {
	private String        eventId;
	private String        eventType;        // "WALLET_DEBIT_FAILED"
	private String        transactionId;
	private String        walletId;
	private String        failureReason;    // error code from Wallet Service
	private String        failureMessage;   // human-readable — for audit log
	private LocalDateTime occurredAt;
}
