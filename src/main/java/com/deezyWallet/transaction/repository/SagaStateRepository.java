package com.deezyWallet.transaction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.deezyWallet.transaction.entity.SagaState;
import com.deezyWallet.transaction.enums.SagaStepEnum;

/**
 * Saga state repository.
 *
 * THE MOST CRITICAL QUERY IN THIS FILE:
 * findTimedOutSteps() — used by SagaTimeoutJob to detect stuck Saga steps.
 *
 * This query runs every 60 seconds and must be fast.
 * Index: idx_saga_pending (step_status, started_at) makes it O(stuck steps)
 * not O(all saga steps). At steady state, stuck steps are rare —
 * the index makes this query return in microseconds even with millions of rows.
 *
 * findByTransactionIdAndStep:
 *   Used by the Saga processor when it receives a wallet event.
 *   Loads the SagaState row for the step being processed so it can
 *   mark it SUCCEEDED or FAILED.
 *
 * findLatestPendingByTransactionId:
 *   Used when determining which compensation command to issue.
 *   Returns the most recent PENDING step — that's the step that needs
 *   to be compensated.
 */
@Repository
public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

	List<SagaState> findByTransactionId(String transactionId);

	Optional<SagaState> findByTransactionIdAndStep(String transactionId, SagaStepEnum step);

	/**
	 * Finds all PENDING Saga steps that have exceeded their timeout threshold.
	 *
	 * Called by SagaTimeoutJob every 60 seconds.
	 *
	 * WHY cutoffTime parameter instead of using NOW() in the query?
	 *   The caller computes cutoffTime = now() - timeoutDuration before calling.
	 *   This makes the timeout threshold configurable per step type
	 *   (DEBIT step timeout vs CREDIT step timeout can differ).
	 *   Using NOW() in JPQL would apply the same cutoff to all step types.
	 *
	 * WHY PENDING only?
	 *   SUCCEEDED/FAILED/TIMED_OUT/SKIPPED steps are already terminal —
	 *   no compensation needed. We only care about steps still waiting.
	 */
	@Query("""
           SELECT s FROM SagaState s
           WHERE s.stepStatus = 'PENDING'
             AND s.startedAt < :cutoffTime
           ORDER BY s.startedAt ASC
           """)
	List<SagaState> findTimedOutSteps(@Param("cutoffTime") LocalDateTime cutoffTime);

	/**
	 * Finds the most recent PENDING step for a transaction.
	 * Used during compensation to know which step to reverse.
	 */
	@Query("""
           SELECT s FROM SagaState s
           WHERE s.transactionId = :txnId
             AND s.stepStatus = 'PENDING'
           ORDER BY s.startedAt DESC
           LIMIT 1
           """)
	Optional<SagaState> findLatestPendingByTransactionId(@Param("txnId") String txnId);

	/**
	 * Updates a step to SUCCEEDED.
	 * Targeted UPDATE avoids loading the entity just to change status.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE SagaState s
           SET s.stepStatus = 'SUCCEEDED', s.completedAt = :completedAt
           WHERE s.transactionId = :txnId AND s.step = :step
             AND s.stepStatus = 'PENDING'
           """)
	int markStepSucceeded(
			@Param("txnId")       String        txnId,
			@Param("step")        SagaStepEnum  step,
			@Param("completedAt") LocalDateTime  completedAt);

	/**
	 * Updates a step to FAILED with detail.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE SagaState s
           SET s.stepStatus = 'FAILED', s.failureDetail = :detail,
               s.completedAt = :completedAt
           WHERE s.transactionId = :txnId AND s.step = :step
             AND s.stepStatus = 'PENDING'
           """)
	int markStepFailed(
			@Param("txnId")       String        txnId,
			@Param("step")        SagaStepEnum  step,
			@Param("detail")      String        detail,
			@Param("completedAt") LocalDateTime  completedAt);

	/**
	 * Updates a step to TIMED_OUT.
	 * Called by SagaTimeoutJob after deciding a step has exceeded its threshold.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE SagaState s
           SET s.stepStatus = 'TIMED_OUT',
               s.failureDetail = 'No response from Wallet Service within timeout window',
               s.completedAt = :completedAt
           WHERE s.id = :sagaStateId AND s.stepStatus = 'PENDING'
           """)
	int markStepTimedOut(
			@Param("sagaStateId") Long          sagaStateId,
			@Param("completedAt") LocalDateTime completedAt);
}
