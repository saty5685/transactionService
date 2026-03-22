package com.deezyWallet.transaction.dto.client;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request and response DTOs for Fraud Service HTTP call.
 *
 * FraudEvaluationRequest — posted to POST /fraud/evaluate
 * FraudEvaluationResponse — received back
 */
public class FraudEvaluationDto {

	/**
	 * Request sent to Fraud Service.
	 * Includes enough context for the fraud model to compute a risk score.
	 *
	 * WHY include kycStatus in the fraud request?
	 *   KYC-verified users get a lower base risk score.
	 *   Unverified users attempting high-value transfers get a penalty.
	 *   Fraud Service adjusts the model score based on KYC level.
	 */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Request {
		private String     transactionId;
		private String     transactionType;  // "P2P_TRANSFER", "MERCHANT_PAYMENT"
		private String     senderUserId;
		private String     senderWalletId;
		private String     receiverUserId;
		private String     receiverWalletId;
		private BigDecimal amount;
		private String     currency;
		private String     senderKycStatus;
		private String     ipAddress;
		private String     deviceFingerprint; // from request header, may be null
	}

	/**
	 * Response from Fraud Service.
	 *
	 * score: 0.0 (clean) to 1.0 (certain fraud)
	 * decision: "APPROVE", "REVIEW", "DECLINE"
	 *   APPROVE  — proceed immediately
	 *   REVIEW   — proceed but flag for manual review
	 *   DECLINE  — reject the transaction
	 *
	 * Transaction Service only rejects on DECLINE or score > threshold.
	 * REVIEW transactions proceed but are flagged for the fraud team.
	 */
	@Data
	@NoArgsConstructor
	public static class Response {
		private String     transactionId;
		private double     score;      // 0.0 - 1.0
		private String     decision;   // "APPROVE", "REVIEW", "DECLINE"
		private String     reason;     // human-readable explanation (for audit)
	}
}
