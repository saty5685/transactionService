package com.deezyWallet.transaction.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.WalletCommandTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Command published to wallet.commands — instructs Wallet Service to unblock funds.
 *
 * Published when compensation is needed (credit failure or admin reversal).
 * Wallet Service's unblockFunds() returns the blocked amount to available balance.
 * idempotencyKey: "{transactionId}:REVERSE_DEBIT"
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletUnblockCommand {
	private String            commandId;
	private WalletCommandTypeEnum commandType;      // WALLET_UNBLOCK
	private String            transactionId;
	private String            walletId;         // sender's walletId (funds return here)
	private BigDecimal        amount;
	private String            idempotencyKey;   // "{transactionId}:REVERSE_DEBIT"
	private LocalDateTime     issuedAt;
}
