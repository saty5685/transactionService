package com.deezyWallet.transaction.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.WalletCommandTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Command published to wallet.commands — instructs Wallet Service to credit.
 *
 * Published by SagaProcessor.onWalletDebited() after debit succeeds.
 * idempotencyKey: "{transactionId}:CREDIT_RECEIVER"
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletCreditCommand {
	private String            commandId;
	private WalletCommandTypeEnum commandType;      // WALLET_CREDIT
	private String            transactionId;
	private String            walletId;         // receiver's walletId
	private BigDecimal        amount;
	private String            currency;
	private String            idempotencyKey;   // "{transactionId}:CREDIT_RECEIVER"
	private String            description;
	private LocalDateTime     issuedAt;
}
