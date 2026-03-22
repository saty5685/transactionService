package com.deezyWallet.transaction.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.TransactionStatusEnum;
import com.deezyWallet.transaction.enums.TransactionTypeEnum;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Extended transaction response for admin endpoints.
 *
 * Adds internal fields not exposed to end users:
 *   fraudScore     — for fraud investigation
 *   failureReason  — for support team debugging
 *   senderWalletId / receiverWalletId — for wallet-level audit
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminTransactionResponse {

	private String            transactionId;
	private TransactionTypeEnum   type;
	private TransactionStatusEnum status;
	private String            statusDescription;

	private BigDecimal        amount;
	private String            currency;
	private String            description;

	private String            senderUserId;
	private String            senderWalletId;
	private String            receiverUserId;
	private String            receiverWalletId;

	private BigDecimal        fraudScore;      // internal — not in user response
	private String            failureReason;   // internal — not in user response
	private String            idempotencyKey;  // internal — not in user response

	private LocalDateTime     createdAt;
	private LocalDateTime     completedAt;
	private Long              version;         // for debugging optimistic lock conflicts
}
