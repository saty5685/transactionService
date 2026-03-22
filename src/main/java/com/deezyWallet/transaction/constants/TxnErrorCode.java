package com.deezyWallet.transaction.constants;

/**
 * Stable string error codes for Transaction Service exceptions.
 *
 * Returned in ErrorResponse.errorCode — machine-readable for API consumers.
 * Once deployed, codes MUST NOT be renamed (breaking change for consumers).
 */
public final class TxnErrorCode {

	private TxnErrorCode() {}

	// ── Transaction validation ────────────────────────────────────────────────
	public static final String INVALID_AMOUNT            = "INVALID_AMOUNT";
	public static final String SELF_TRANSFER_NOT_ALLOWED = "SELF_TRANSFER_NOT_ALLOWED";
	public static final String RECEIVER_NOT_FOUND        = "RECEIVER_NOT_FOUND";
	public static final String RECEIVER_NOT_ACTIVE       = "RECEIVER_NOT_ACTIVE";
	public static final String SENDER_NOT_ACTIVE         = "SENDER_NOT_ACTIVE";
	public static final String SENDER_KYC_REQUIRED       = "SENDER_KYC_REQUIRED";
	public static final String INSUFFICIENT_BALANCE      = "INSUFFICIENT_BALANCE";

	// ── Fraud ─────────────────────────────────────────────────────────────────
	public static final String FRAUD_SCORE_EXCEEDED      = "FRAUD_SCORE_EXCEEDED";
	public static final String FRAUD_SERVICE_UNAVAILABLE = "FRAUD_SERVICE_UNAVAILABLE";

	// ── Idempotency ───────────────────────────────────────────────────────────
	public static final String DUPLICATE_TRANSACTION     = "DUPLICATE_TRANSACTION";

	// ── Transaction state ─────────────────────────────────────────────────────
	public static final String TRANSACTION_NOT_FOUND     = "TRANSACTION_NOT_FOUND";
	public static final String TRANSACTION_NOT_REVERSIBLE = "TRANSACTION_NOT_REVERSIBLE";
	public static final String TRANSACTION_ALREADY_TERMINAL = "TRANSACTION_ALREADY_TERMINAL";

	// ── Saga ──────────────────────────────────────────────────────────────────
	public static final String SAGA_STEP_FAILED          = "SAGA_STEP_FAILED";
	public static final String SAGA_TIMEOUT              = "SAGA_TIMEOUT";

	// ── Generic ───────────────────────────────────────────────────────────────
	public static final String VALIDATION_FAILED         = "VALIDATION_FAILED";
	public static final String INTERNAL_ERROR            = "INTERNAL_ERROR";
	public static final String ACCESS_DENIED             = "ACCESS_DENIED";
}
