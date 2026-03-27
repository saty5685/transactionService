package com.deezyWallet.transaction.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.WalletCommandTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Command published to wallet.commands — instructs Wallet Service to debit.
 *
 * Wallet Service's TransactionEventConsumer (Step 10 of wallet implementation)
 * consumes this. It performs:
 *   1. Idempotency check on idempotencyKey
 *   2. PESSIMISTIC_WRITE lock on the wallet row
 *   3. Balance + status checks
 *   4. Balance deduction + transaction record
 *   5. Publish WALLET_DEBITED or WALLET_DEBIT_FAILED
 *
 * idempotencyKey: set to "{transactionId}:DEBIT_SENDER"
 *   Wallet Service uses this to deduplicate replays.
 *   If we publish the same command twice (retry after timeout), Wallet Service
 *   detects the duplicate and returns the existing transaction response.
 *
 * commandType: WalletCommandType.WALLET_DEBIT
 *   Wallet Service uses this discriminator to route to the correct handler.
 *   We avoid separate topics per command type — one topic, one consumer group,
 *   discriminator field for routing.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletDebitCommand {
	private String            commandId;        // UUID — for tracing
	private WalletCommandTypeEnum commandType;      // WALLET_DEBIT
	private String            transactionId;
	private String            walletId;         // sender's walletId
	private BigDecimal        amount;
	private String            currency;
	private String            idempotencyKey;   // "{transactionId}:DEBIT_SENDER"
	private String            description;
	private LocalDateTime     issuedAt;
}
