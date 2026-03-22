package com.deezyWallet.transaction.entity;

import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.SagaStepEnum;
import com.deezyWallet.transaction.enums.SagaStepStatusEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Saga step log — one row per step per transaction.
 *
 * WHY a separate table instead of columns on Transaction?
 *   A transaction has up to 4 steps (PREFLIGHT, DEBIT_SENDER, CREDIT_RECEIVER,
 *   REVERSE_DEBIT). If we put all steps on the Transaction row, we'd need 4×3
 *   nullable columns (step_status, step_started_at, step_completed_at) per step.
 *   That's 12 nullable columns, always mostly null.
 *   A separate table with one row per step is cleaner, queryable, and extensible
 *   (adding a new Saga step is a row, not a schema migration).
 *
 * TIMEOUT DETECTION QUERY:
 *   SELECT * FROM saga_state
 *   WHERE step_status = 'PENDING'
 *     AND step IN ('DEBIT_SENDER', 'CREDIT_RECEIVER')
 *     AND started_at < NOW() - INTERVAL 5 MINUTE
 *
 * This is the exact query the SagaTimeoutJob runs every minute.
 *
 * commandIdempotencyKey:
 *   The idempotency key used when publishing the Kafka command for this step.
 *   If a step times out and we need to re-publish the command (replay),
 *   we use the SAME key — ensuring Wallet Service deduplicates the replay
 *   and doesn't double-debit.
 *
 * Immutable after creation — setters only for status and completedAt.
 * The step itself (DEBIT_SENDER etc.) never changes on a row.
 */
@Entity
@Table(
		name = "saga_state",
		indexes = {
				@Index(name = "idx_saga_txn_id",    columnList = "transaction_id"),
				@Index(name = "idx_saga_pending",    columnList = "step_status, started_at"),
				@Index(name = "idx_saga_step_status",columnList = "step_status")
		}
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SagaState {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "transaction_id", nullable = false, length = 36)
	private String transactionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SagaStepEnum step;

	@Enumerated(EnumType.STRING)
	@Column(name = "step_status", nullable = false, length = 20)
	@Setter
	private SagaStepStatusEnum stepStatus;

	/**
	 * The idempotency key used for the Kafka command published for this step.
	 * Format: {transactionId}:{step}
	 * Used for command replay on timeout — same key ensures Wallet Service deduplicates.
	 */
	@Column(name = "command_idempotency_key", nullable = false, length = 80)
	private String commandIdempotencyKey;

	/** Error message from Kafka event or timeout — for audit */
	@Column(name = "failure_detail", length = 500)
	@Setter
	private String failureDetail;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	@Setter
	private LocalDateTime completedAt;

	// ── Factory methods ───────────────────────────────────────────────────────

	public static SagaState pending(String transactionId, SagaStepEnum step) {
		return SagaState.builder()
				.transactionId(transactionId)
				.step(step)
				.stepStatus(SagaStepStatusEnum.PENDING)
				.commandIdempotencyKey(transactionId + ":" + step.name())
				.startedAt(LocalDateTime.now())
				.build();
	}

	public void succeed() {
		this.stepStatus  = SagaStepStatusEnum.SUCCEEDED;
		this.completedAt = LocalDateTime.now();
	}

	public void fail(String detail) {
		this.stepStatus    = SagaStepStatusEnum.FAILED;
		this.failureDetail = detail;
		this.completedAt   = LocalDateTime.now();
	}

	public void timeout() {
		this.stepStatus    = SagaStepStatusEnum.TIMED_OUT;
		this.failureDetail = "Saga step timed out — no response from Wallet Service";
		this.completedAt   = LocalDateTime.now();
	}
}
