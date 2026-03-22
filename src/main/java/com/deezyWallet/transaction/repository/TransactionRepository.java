package com.deezyWallet.transaction.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.TransactionStatusEnum;
import com.deezyWallet.transaction.enums.TransactionTypeEnum;

/**
 * Transaction repository.
 *
 * METHOD DESIGN DECISIONS:
 *
 * findByIdempotencyKey:
 *   Called before every transaction creation attempt.
 *   If found, return the existing transaction (idempotent response).
 *   The DB UNIQUE constraint on idempotency_key is the real guard;
 *   this query is the application-layer fast path.
 *
 * findByIdWithLock vs findById:
 *   findById — used by Saga processor reading current status.
 *              Optimistic locking (@Version) handles concurrency.
 *   findByIdWithLock — PESSIMISTIC_WRITE lock used by timeout job
 *              to prevent race with Saga processor updating the same row.
 *              Timeout job holds the lock while deciding + writing compensation.
 *
 * findBySenderUserIdOrderByCreatedAtDesc (paginated):
 *   User's transaction history. Always paginated — never SELECT *.
 *   Covered by idx_txn_sender_user + idx_txn_created_at.
 *
 * updateStatus — @Modifying targeted UPDATE:
 *   The Saga processor frequently updates status (PENDING_DEBIT → PENDING_CREDIT
 *   → COMPLETED). Using a targeted UPDATE instead of load+save:
 *   (a) avoids reloading the full entity just to change one field
 *   (b) prevents accidental overwrites of other fields modified concurrently
 *   (c) is faster — one UPDATE vs SELECT + UPDATE
 *   @Version is still incremented by Hibernate even on targeted updates
 *   when using @Modifying — but we use a conditional check in the query
 *   (AND version = :version) for optimistic concurrency.
 *
 * updateStatusAndFailureReason:
 *   Used when marking FAILED, TIMED_OUT, REVERSAL_FAILED — failure context
 *   stored alongside status change atomically.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

	// ── Idempotency lookup ────────────────────────────────────────────────────

	Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

	boolean existsByIdempotencyKey(String idempotencyKey);

	// ── Pessimistic lock for timeout job ──────────────────────────────────────

	/**
	 * Acquires a PESSIMISTIC_WRITE (SELECT FOR UPDATE) lock on the transaction row.
	 *
	 * WHY only for the timeout job and not the Saga processor?
	 *   The Saga processor uses @Version optimistic locking — it reads without
	 *   a lock and retries if the version has changed. This is fast for the
	 *   common case where there's no conflict.
	 *
	 *   The timeout job is a scheduled background task — it's okay for it to
	 *   wait briefly for a row lock. Pessimistic locking here prevents a race
	 *   where: (a) the Saga processor receives the wallet event and starts
	 *   updating status, AND simultaneously (b) the timeout job decides the
	 *   step has timed out and starts compensation. Without a lock, both could
	 *   succeed — the transaction would be marked COMPLETED and TIMED_OUT.
	 */
	@Query("SELECT t FROM Transaction t WHERE t.id = :id")
	@org.springframework.data.jpa.repository.Lock(
			jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	Optional<Transaction> findByIdWithLock(@Param("id") String id);

	// ── User-scoped queries (transaction history) ─────────────────────────────

	Page<Transaction> findBySenderUserIdOrderByCreatedAtDesc(
			String senderUserId, Pageable pageable);

	Page<Transaction> findBySenderUserIdAndStatusOrderByCreatedAtDesc(
			String senderUserId, TransactionStatusEnum status, Pageable pageable);

	Page<Transaction> findBySenderUserIdAndTypeOrderByCreatedAtDesc(
			String senderUserId, TransactionTypeEnum type, Pageable pageable);

	// ── Admin queries ─────────────────────────────────────────────────────────

	Page<Transaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Page<Transaction> findByStatusOrderByCreatedAtDesc(
			TransactionStatusEnum status, Pageable pageable);

	Page<Transaction> findBySenderUserIdOrReceiverUserIdOrderByCreatedAtDesc(
			String senderUserId, String receiverUserId, Pageable pageable);

	// ── Targeted status updates (avoid full entity reload) ────────────────────

	/**
	 * Updates transaction status with optimistic version check.
	 *
	 * Returns the number of rows updated. If 0, the version has changed
	 * (another thread updated the row first) — caller should retry.
	 *
	 * WHY include version in WHERE clause?
	 *   Standard @Version check happens at flush time in a @Transactional method.
	 *   For @Modifying JPQL, Hibernate does NOT automatically check @Version —
	 *   we must include it in the WHERE clause ourselves for optimistic concurrency.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Transaction t
           SET t.status = :newStatus, t.version = t.version + 1
           WHERE t.id = :txnId AND t.version = :expectedVersion
           """)
	int updateStatus(
			@Param("txnId")           String                txnId,
			@Param("newStatus")       TransactionStatusEnum newStatus,
			@Param("expectedVersion") long                  expectedVersion);

	/**
	 * Updates status and sets completedAt atomically — used for terminal states.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Transaction t
           SET t.status = :newStatus, t.completedAt = :completedAt,
               t.version = t.version + 1
           WHERE t.id = :txnId AND t.version = :expectedVersion
           """)
	int updateStatusAndCompletedAt(
			@Param("txnId")           String                txnId,
			@Param("newStatus")       TransactionStatusEnum newStatus,
			@Param("completedAt")     LocalDateTime         completedAt,
			@Param("expectedVersion") long                  expectedVersion);

	/**
	 * Updates status + failureReason atomically for FAILED/TIMED_OUT states.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Transaction t
           SET t.status = :newStatus, t.failureReason = :failureReason,
               t.completedAt = :completedAt, t.version = t.version + 1
           WHERE t.id = :txnId AND t.version = :expectedVersion
           """)
	int updateStatusWithFailure(
			@Param("txnId")           String                txnId,
			@Param("newStatus")       TransactionStatusEnum newStatus,
			@Param("failureReason")   String                failureReason,
			@Param("completedAt")     LocalDateTime         completedAt,
			@Param("expectedVersion") long                  expectedVersion);
}
