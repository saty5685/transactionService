package com.deezyWallet.transaction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.deezyWallet.transaction.enums.TransactionStatusEnum;
import com.deezyWallet.transaction.enums.TransactionTypeEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Core transaction record — one row per business transaction.
 *
 * DESIGN DECISIONS:
 *
 * UUID primary key (VARCHAR(36)):
 *   Same rationale as User and Wallet services.
 *   Transaction IDs are returned to clients — sequential IDs would leak
 *   transaction volume (competitor intelligence, user enumeration).
 *
 * senderWalletId / receiverWalletId (not userId):
 *   Transaction Service deals in wallets, not users.
 *   A user can theoretically have multiple wallets in the future.
 *   Storing walletId future-proofs the schema.
 *   We also store senderUserId and receiverUserId for fast user-scoped queries
 *   without joining through Wallet Service's DB.
 *
 * DECIMAL(19,4) for amounts:
 *   Same as Wallet Service — prevents IEEE 754 floating-point rounding errors.
 *   4 decimal places sufficient for INR (paise = 0.01).
 *
 * idempotencyKey UNIQUE constraint:
 *   Client-supplied key enforced at DB level — real guard against
 *   duplicate inserts in concurrent retry storms. Application-level
 *   Redis SETNX is the fast path; this is the backstop.
 *
 * fraudScore:
 *   Stored on the transaction for audit and ML feedback loops.
 *   Fraud Service trains models on historical scores vs outcomes.
 *   Never used for runtime decisions after the initial check.
 *
 * failureReason:
 *   Human-readable failure code stored when status → FAILED/TIMED_OUT/REVERSAL_FAILED.
 *   Used by support teams and surfaced in admin UI. Not returned to end users.
 *
 * @Version for optimistic locking:
 *   The Saga processor and the timeout job can both try to update the same
 *   transaction status concurrently. @Version ensures only one wins —
 *   the loser gets OptimisticLockException and retries.
 */
@Entity
@Table(
		name = "transactions",
		indexes = {
				@Index(name = "idx_txn_sender_user",     columnList = "sender_user_id"),
				@Index(name = "idx_txn_receiver_user",   columnList = "receiver_user_id"),
				@Index(name = "idx_txn_sender_wallet",   columnList = "sender_wallet_id"),
				@Index(name = "idx_txn_status",          columnList = "status"),
				@Index(name = "idx_txn_created_at",      columnList = "created_at"),
				@Index(name = "idx_txn_idempotency_key", columnList = "idempotency_key")
		}
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

	@Id
	@Column(name = "id", length = 36, updatable = false, nullable = false)
	private String id;

	// ── Parties ───────────────────────────────────────────────────────────────

	@Column(name = "sender_user_id", nullable = false, length = 36)
	private String senderUserId;

	@Column(name = "sender_wallet_id", nullable = false, length = 36)
	private String senderWalletId;

	/** Null for WALLET_TOPUP (external source) */
	@Column(name = "receiver_user_id", length = 36)
	private String receiverUserId;

	/** Null for WALLET_WITHDRAWAL (external destination) */
	@Column(name = "receiver_wallet_id", length = 36)
	private String receiverWalletId;

	// ── Transaction details ───────────────────────────────────────────────────

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private TransactionTypeEnum type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	@Builder.Default
	private TransactionStatusEnum status = TransactionStatusEnum.INITIATED;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	@Builder.Default
	private String currency = "INR";

	@Column(length = 500)
	private String description;

	// ── Idempotency ───────────────────────────────────────────────────────────

	/**
	 * Client-supplied idempotency key — UUID format enforced at DTO validation.
	 * UNIQUE constraint at DB level — real guard against duplicate inserts.
	 */
	@Column(name = "idempotency_key", nullable = false, unique = true, length = 36)
	private String idempotencyKey;

	// ── Fraud ─────────────────────────────────────────────────────────────────

	/**
	 * Fraud risk score from Fraud Service (0.0 = clean, 1.0 = certain fraud).
	 * Stored for audit and ML feedback. Null if fraud check was skipped.
	 */
	@Column(name = "fraud_score", precision = 5, scale = 4)
	private BigDecimal fraudScore;

	// ── Failure context ───────────────────────────────────────────────────────

	/** Error code when status is FAILED / TIMED_OUT / REVERSAL_FAILED */
	@Column(name = "failure_reason", length = 100)
	private String failureReason;

	// ── Timestamps ────────────────────────────────────────────────────────────

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/** Timestamp when the transaction reached a terminal state */
	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	// ── Optimistic locking ────────────────────────────────────────────────────

	/**
	 * @Version for optimistic locking.
	 *
	 * The Saga processor (handling wallet.events) and the timeout scheduler
	 * can both attempt to update the same transaction concurrently.
	 * @Version ensures only one succeeds — the loser retries.
	 * This prevents split-brain state where both think they won the race.
	 */
	@Version
	@Column(nullable = false)
	@Builder.Default
	private Long version = 0L;

	// ── Business logic helpers ────────────────────────────────────────────────

	@Transient
	public boolean canTransition(TransactionStatusEnum newStatus) {
		if (this.status.isTerminal()) return false;
		// Add specific valid-transition rules here if needed
		return true;
	}

	@Transient
	public void markCompleted() {
		this.status      = TransactionStatusEnum.COMPLETED;
		this.completedAt = LocalDateTime.now();
	}

	@Transient
	public void markFailed(String reason) {
		this.status        = TransactionStatusEnum.FAILED;
		this.failureReason = reason;
		this.completedAt   = LocalDateTime.now();
	}
}
