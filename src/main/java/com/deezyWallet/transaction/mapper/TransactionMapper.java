package com.deezyWallet.transaction.mapper;

import org.springframework.stereotype.Component;

import com.deezyWallet.transaction.dto.response.AdminTransactionResponse;
import com.deezyWallet.transaction.dto.response.TransactionResponse;
import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.TransactionStatusEnum;

/**
 * Hand-written mapper — no MapStruct.
 *
 * WHY hand-written?
 *   The distinction between TransactionResponse (user-facing) and
 *   AdminTransactionResponse (admin-facing) is a security boundary.
 *   fraudScore, failureReason, idempotencyKey, senderWalletId, receiverWalletId
 *   must NEVER appear in user-facing responses.
 *
 *   A MapStruct mapper would require careful exclusion annotations that
 *   are easy to forget. A hand-written mapper makes the exclusion explicit,
 *   visible in code review, and impossible to accidentally bypass.
 *
 * statusDescription:
 *   Generated here rather than stored in DB to keep the DB schema clean
 *   and allow the description to change without a migration.
 */
@Component
public class TransactionMapper {

	/**
	 * Maps to user-facing response — no internal fields.
	 * Called from user-facing controllers only.
	 */
	public TransactionResponse toResponse(Transaction txn) {
		if (txn == null) return null;
		return TransactionResponse.builder()
				.transactionId(txn.getId())
				.type(txn.getType())
				.status(txn.getStatus())
				.statusDescription(describeStatus(txn.getStatus(), txn.getType()))
				.amount(txn.getAmount())
				.currency(txn.getCurrency())
				.description(txn.getDescription())
				.senderUserId(txn.getSenderUserId())
				.receiverUserId(txn.getReceiverUserId())
				.createdAt(txn.getCreatedAt())
				.completedAt(txn.getCompletedAt())
				.build();
	}

	/**
	 * Maps to admin response — includes all internal fields.
	 * Called from admin controllers only.
	 */
	public AdminTransactionResponse toAdminResponse(Transaction txn) {
		if (txn == null) return null;
		return AdminTransactionResponse.builder()
				.transactionId(txn.getId())
				.type(txn.getType())
				.status(txn.getStatus())
				.statusDescription(describeStatus(txn.getStatus(), txn.getType()))
				.amount(txn.getAmount())
				.currency(txn.getCurrency())
				.description(txn.getDescription())
				.senderUserId(txn.getSenderUserId())
				.senderWalletId(txn.getSenderWalletId())
				.receiverUserId(txn.getReceiverUserId())
				.receiverWalletId(txn.getReceiverWalletId())
				.fraudScore(txn.getFraudScore())
				.failureReason(txn.getFailureReason())
				.idempotencyKey(txn.getIdempotencyKey())
				.createdAt(txn.getCreatedAt())
				.completedAt(txn.getCompletedAt())
				.version(txn.getVersion())
				.build();
	}

	/**
	 * Generates a human-readable status description.
	 * Type-specific wording makes the UI copy more natural.
	 */
	private String describeStatus(TransactionStatusEnum status,
			com.deezyWallet.transaction.enums.TransactionTypeEnum type) {
		return switch (status) {
			case INITIATED       -> "Transaction initiated";
			case PENDING_DEBIT   -> "Processing payment";
			case PENDING_CREDIT  -> "Transferring funds";
			case COMPLETED       -> switch (type) {
				case P2P_TRANSFER       -> "Transfer successful";
				case MERCHANT_PAYMENT   -> "Payment successful";
				case WALLET_TOPUP       -> "Wallet topped up";
				case WALLET_WITHDRAWAL  -> "Withdrawal successful";
				case REFUND             -> "Refund processed";
			};
			case FAILED          -> "Transaction failed";
			case TIMED_OUT       -> "Transaction timed out";
			case REVERSING       -> "Reversing transaction";
			case REVERSED        -> "Transaction reversed";
			case REVERSAL_FAILED -> "Reversal failed — contact support";
		};
	}
}
