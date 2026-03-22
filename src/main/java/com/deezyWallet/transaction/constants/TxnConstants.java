package com.deezyWallet.transaction.constants;

import java.math.BigDecimal;

/**
 * Compile-time constants for Transaction Service.
 *
 * Structural constants — part of the code contract, not deployment config.
 * Deployment config (timeouts, feature flags) belongs in application.yml.
 */
public final class TxnConstants {

	private TxnConstants() {}

	// ── Kafka topics ──────────────────────────────────────────────────────────

	/** Commands published TO Wallet Service */
	public static final String TOPIC_WALLET_COMMANDS = "wallet.commands";

	/** Events consumed FROM Wallet Service */
	public static final String TOPIC_WALLET_EVENTS   = "wallet.events";

	/** Events published by Transaction Service (consumed by Ledger, Notification) */
	public static final String TOPIC_TXN_EVENTS      = "txn.events";

	// ── Redis key prefixes ────────────────────────────────────────────────────

	/** Idempotency lock key: txn:lock:{idempotencyKey} */
	public static final String REDIS_TXN_LOCK_PREFIX = "txn:lock:";

	/** In-flight txn cache: txn:inflight:{txnId} → status (for fast status reads) */
	public static final String REDIS_TXN_STATUS_PREFIX = "txn:status:";

	// ── Business limits ───────────────────────────────────────────────────────

	/** Minimum transaction amount — prevents dust transactions */
	public static final BigDecimal MIN_TRANSACTION_AMOUNT = new BigDecimal("1.00");

	/** Maximum single P2P transaction amount — enforced at API layer */
	public static final BigDecimal MAX_P2P_AMOUNT = new BigDecimal("100000.00");

	/** Maximum merchant payment amount per transaction */
	public static final BigDecimal MAX_MERCHANT_AMOUNT = new BigDecimal("500000.00");

	// ── Saga timeouts ─────────────────────────────────────────────────────────

	/** Seconds before a PENDING_DEBIT step is considered timed out */
	public static final int SAGA_DEBIT_TIMEOUT_SECONDS  = 300;  // 5 minutes

	/** Seconds before a PENDING_CREDIT step is considered timed out */
	public static final int SAGA_CREDIT_TIMEOUT_SECONDS = 300;  // 5 minutes

	/** Seconds before a REVERSING step is considered timed out */
	public static final int SAGA_REVERSAL_TIMEOUT_SECONDS = 600; // 10 minutes

	/** How often the timeout job runs (seconds) */
	public static final int SAGA_TIMEOUT_JOB_INTERVAL_SECONDS = 60;

	// ── Idempotency ───────────────────────────────────────────────────────────

	/** TTL for the Redis idempotency lock (must exceed max saga duration) */
	public static final int IDEMPOTENCY_LOCK_TTL_SECONDS = 3600; // 1 hour

	// ── Fraud ─────────────────────────────────────────────────────────────────

	/** Maximum acceptable fraud score (0.0 = clean, 1.0 = certain fraud) */
	public static final double FRAUD_SCORE_THRESHOLD    = 0.75;

	/** Timeout for Fraud Service HTTP call — fail-safe (reject if timeout) */
	public static final int FRAUD_SERVICE_TIMEOUT_MS    = 3000;

	// ── API paths ─────────────────────────────────────────────────────────────

	public static final String API_TXN_BASE      = "/api/v1/transactions";
	public static final String API_ADMIN_BASE    = "/api/v1/admin/transactions";
	public static final String API_INTERNAL_BASE = "/internal/v1/transactions";
	public static final String ACTUATOR_HEALTH   = "/actuator/health";

	// ── Roles ─────────────────────────────────────────────────────────────────

	public static final String ROLE_USER             = "ROLE_USER";
	public static final String ROLE_MERCHANT         = "ROLE_MERCHANT";
	public static final String ROLE_ADMIN            = "ROLE_ADMIN";
	public static final String ROLE_INTERNAL_SERVICE = "ROLE_INTERNAL_SERVICE";

	// ── JWT claim keys (must match User Service) ──────────────────────────────

	public static final String JWT_CLAIM_ROLES      = "roles";
	public static final String JWT_CLAIM_EMAIL      = "email";
	public static final String JWT_CLAIM_KYC_STATUS = "kycStatus";
}
