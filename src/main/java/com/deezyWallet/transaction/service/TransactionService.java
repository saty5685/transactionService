package com.deezyWallet.transaction.service;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.constants.TxnErrorCode;
import com.deezyWallet.transaction.dto.request.MerchantPayRequest;
import com.deezyWallet.transaction.dto.request.TransferRequest;
import com.deezyWallet.transaction.dto.response.AdminTransactionResponse;
import com.deezyWallet.transaction.dto.response.PagedResponse;
import com.deezyWallet.transaction.dto.response.TransactionResponse;
import com.deezyWallet.transaction.entity.SagaState;
import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.*;
import com.deezyWallet.transaction.event.TxnEventPublisher;
import com.deezyWallet.transaction.event.WalletCommandPublisher;
import com.deezyWallet.transaction.exception.*;
import com.deezyWallet.transaction.mapper.TransactionMapper;
import com.deezyWallet.transaction.repository.SagaStateRepository;
import com.deezyWallet.transaction.repository.TransactionRepository;
import com.deezyWallet.transaction.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TransactionService — initiates transactions and drives the Saga.
 *
 * RESPONSIBILITIES:
 *   - Idempotency check (Redis SETNX fast path + DB UNIQUE constraint backstop)
 *   - Delegates preflight to PreflightService (sync external calls)
 *   - Persists Transaction + initial SagaState row
 *   - Publishes WALLET_DEBIT_CMD to start the Saga
 *   - Read operations: history, status, admin queries
 *
 * WHAT THIS SERVICE DOES NOT DO:
 *   - Does not process wallet events (SagaProcessor handles those)
 *   - Does not check for timed-out Sagas (SagaTimeoutJob handles that)
 *   - Does not touch balances (Wallet Service owns that)
 *
 * TRANSACTION STRATEGY:
 *   initiateTransfer() is NOT a single @Transactional method.
 *   WHY? Because it calls PreflightService (3 HTTP calls) THEN persists.
 *   A transaction wrapping HTTP calls holds a DB connection for seconds — pool exhaustion.
 *   Instead:
 *     Phase 1 (no DB tx): Redis idempotency lock + PreflightService calls
 *     Phase 2 (@Transactional): persist Transaction + SagaState row
 *     Phase 3 (post-commit): publish Kafka WALLET_DEBIT_CMD
 *
 *   If Phase 2 fails (DB error), Phase 3 never runs — no orphaned Kafka message.
 *   If Phase 3 fails (Kafka error), the transaction is INITIATED in DB but no
 *   Saga has started. The SagaTimeoutJob will detect it and trigger compensation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

	private final TransactionRepository  transactionRepository;
	private final SagaStateRepository    sagaStateRepository;
	private final PreflightService       preflightService;
	private final WalletCommandPublisher walletCommandPublisher;
	private final TxnEventPublisher      txnEventPublisher;
	private final TransactionMapper      mapper;
	private final RedisTemplate<String, String> redisTemplate;

	// ── Initiate P2P Transfer ─────────────────────────────────────────────────

	/**
	 * Initiates a P2P transfer — the entry point for the Saga.
	 *
	 * IDEMPOTENCY FLOW:
	 *   1. SETNX idempotency lock in Redis (fast path)
	 *   2. If lock fails → check DB for existing txn → return it (200, not error)
	 *   3. If lock succeeds → run preflight → persist → publish debit cmd
	 *
	 * WHY return existing txn on duplicate instead of throwing 409?
	 *   RFC 7231 idempotency semantics: repeated identical requests should
	 *   return the same response. 409 Conflict is wrong — the request wasn't
	 *   invalid, it was already processed. Returning the existing txn lets
	 *   the client confirm the original intent succeeded.
	 */
	public TransactionResponse initiateTransfer(TransferRequest    req,
			UserPrincipal      sender) {
		// Phase 1: Idempotency fast-path check
		boolean lockAcquired = acquireIdempotencyLock(req.getIdempotencyKey());
		if (!lockAcquired) {
			return handleDuplicateRequest(req.getIdempotencyKey());
		}

		try {
			// Phase 1b: DB idempotency check (covers Redis-down scenario)
			if (transactionRepository.existsByIdempotencyKey(req.getIdempotencyKey())) {
				return transactionRepository.findByIdempotencyKey(req.getIdempotencyKey())
						.map(mapper::toResponse)
						.orElseThrow(() -> new TransactionNotFoundException(
								"Transaction not found for idempotency key"));
			}

			// Phase 2: Preflight checks (no DB tx — HTTP calls)
			PreflightService.PreflightResult preflight =
					preflightService.runForTransfer(sender, req.getReceiverIdentifier(), req.getAmount());

			// Phase 3: Persist transaction + Saga step
			Transaction txn = persistTransactionAndSaga(
					sender.getUserId(),
					preflight.getSenderWalletId(),
					preflight.getReceiverUserId(),
					preflight.getReceiverWalletId(),
					req.getAmount(),
					req.getDescription(),
					TransactionTypeEnum.P2P_TRANSFER,
					req.getIdempotencyKey(),
					preflight.getFraudScore()
			);

			// Phase 4: Publish debit command (post-commit — see persistAndPublish)
			publishDebitCommand(txn);

			log.info("Transfer initiated: txnId={} sender={} amount={}",
					txn.getId(), sender.getUserId(), req.getAmount());

			return mapper.toResponse(txn);

		} catch (Exception e) {
			// Release lock on any exception so retries can proceed
			// For business exceptions this is correct — the lock is done
			// For system exceptions: TTL (1h) is the backstop
			releaseIdempotencyLock(req.getIdempotencyKey());
			throw e;
		}
	}

	/**
	 * Initiates a merchant payment — same Saga flow, different type + preflight.
	 */
	public TransactionResponse initiateMerchantPayment(MerchantPayRequest req,
			UserPrincipal      sender) {
		boolean lockAcquired = acquireIdempotencyLock(req.getIdempotencyKey());
		if (!lockAcquired) {
			return handleDuplicateRequest(req.getIdempotencyKey());
		}

		try {
			if (transactionRepository.existsByIdempotencyKey(req.getIdempotencyKey())) {
				return transactionRepository.findByIdempotencyKey(req.getIdempotencyKey())
						.map(mapper::toResponse)
						.orElseThrow(() -> new TransactionNotFoundException(
								"Transaction not found for idempotency key"));
			}

			PreflightService.PreflightResult preflight =
					preflightService.runForMerchantPayment(
							sender, req.getMerchantWalletId(), req.getAmount());

			Transaction txn = persistTransactionAndSaga(
					sender.getUserId(),
					preflight.getSenderWalletId(),
					preflight.getReceiverUserId(),
					preflight.getReceiverWalletId(),
					req.getAmount(),
					req.getDescription(),
					TransactionTypeEnum.MERCHANT_PAYMENT,
					req.getIdempotencyKey(),
					preflight.getFraudScore()
			);

			publishDebitCommand(txn);

			return mapper.toResponse(txn);

		} catch (Exception e) {
			releaseIdempotencyLock(req.getIdempotencyKey());
			throw e;
		}
	}

	// ── Read operations ───────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public TransactionResponse getTransaction(String txnId, String requestingUserId) {
		Transaction txn = transactionRepository.findById(txnId)
				.orElseThrow(() -> new TransactionNotFoundException(
						"Transaction not found: " + txnId));

		// Ownership check: only sender or receiver can view
		if (!txn.getSenderUserId().equals(requestingUserId) &&
				!requestingUserId.equals(txn.getReceiverUserId())) {
			throw new TransactionAccessDeniedException("Access denied to transaction: " + txnId);
		}

		return mapper.toResponse(txn);
	}

	@Transactional(readOnly = true)
	public PagedResponse<TransactionResponse> getUserTransactions(
			String userId, TransactionStatusEnum statusFilter, Pageable pageable) {

		Page<Transaction> page = (statusFilter != null)
				? transactionRepository.findBySenderUserIdAndStatusOrderByCreatedAtDesc(
				userId, statusFilter, pageable)
				: transactionRepository.findBySenderUserIdOrderByCreatedAtDesc(userId, pageable);

		return PagedResponse.from(page.map(mapper::toResponse));
	}

	// ── Admin operations ──────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public AdminTransactionResponse getTransactionAdmin(String txnId) {
		return transactionRepository.findById(txnId)
				.map(mapper::toAdminResponse)
				.orElseThrow(() -> new TransactionNotFoundException(
						"Transaction not found: " + txnId));
	}

	@Transactional(readOnly = true)
	public PagedResponse<AdminTransactionResponse> listTransactions(
			TransactionStatusEnum statusFilter, Pageable pageable) {

		Page<Transaction> page = (statusFilter != null)
				? transactionRepository.findByStatusOrderByCreatedAtDesc(statusFilter, pageable)
				: transactionRepository.findAllByOrderByCreatedAtDesc(pageable);

		return PagedResponse.from(page.map(mapper::toAdminResponse));
	}

	/**
	 * Admin-initiated manual reversal for a COMPLETED transaction.
	 *
	 * Only COMPLETED → REVERSING is valid.
	 * This publishes a WALLET_UNBLOCK_CMD and creates a REVERSE_DEBIT saga step.
	 * The SagaProcessor completes the reversal when it receives WALLET_UNBLOCKED.
	 *
	 * WHY allow admin reversal of COMPLETED transactions?
	 *   Dispute resolution, fraud recovery, error corrections.
	 *   Reversal is a controlled operation — admin only, logged, audited.
	 */
	@Transactional
	public AdminTransactionResponse reverseTransaction(String txnId, String adminId) {
		Transaction txn = transactionRepository.findById(txnId)
				.orElseThrow(() -> new TransactionNotFoundException(
						"Transaction not found: " + txnId));

		if (txn.getStatus() != TransactionStatusEnum.COMPLETED) {
			throw new TransactionNotReversibleException(
					"Only COMPLETED transactions can be reversed. Current status: " + txn.getStatus());
		}

		txn.setStatus(TransactionStatusEnum.REVERSING);
		transactionRepository.save(txn);

		SagaState reverseStep = SagaState.pending(txnId, SagaStepEnum.REVERSE_DEBIT);
		sagaStateRepository.save(reverseStep);

		walletCommandPublisher.publishUnblockCommand(txn, reverseStep.getCommandIdempotencyKey());

		log.info("Admin reversal initiated: txnId={} adminId={}", txnId, adminId);
		return mapper.toAdminResponse(txn);
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	/**
	 * Persists the transaction record and its initial Saga step atomically.
	 * Both must succeed or neither is committed.
	 */
	@Transactional
	protected Transaction persistTransactionAndSaga(
			String         senderUserId,
			String         senderWalletId,
			String         receiverUserId,
			String         receiverWalletId,
			java.math.BigDecimal amount,
			String         description,
			TransactionTypeEnum type,
			String         idempotencyKey,
			java.math.BigDecimal fraudScore) {

		Transaction txn = Transaction.builder()
				.id(UUID.randomUUID().toString())
				.senderUserId(senderUserId)
				.senderWalletId(senderWalletId)
				.receiverUserId(receiverUserId)
				.receiverWalletId(receiverWalletId)
				.amount(amount)
				.type(type)
				.status(TransactionStatusEnum.INITIATED)
				.idempotencyKey(idempotencyKey)
				.fraudScore(fraudScore)
				.description(description)
				.build();

		transactionRepository.save(txn);

		// Create the first Saga step — PREFLIGHT (already done, mark succeeded)
		SagaState preflight = SagaState.pending(txn.getId(), SagaStepEnum.PREFLIGHT);
		preflight.succeed();
		sagaStateRepository.save(preflight);

		// Create DEBIT_SENDER step — PENDING (waiting for Kafka response)
		SagaState debitStep = SagaState.pending(txn.getId(), SagaStepEnum.DEBIT_SENDER);
		sagaStateRepository.save(debitStep);

		// Update transaction status to PENDING_DEBIT
		txn.setStatus(TransactionStatusEnum.PENDING_DEBIT);
		transactionRepository.save(txn);

		txnEventPublisher.publishTxnInitiated(txn);

		return txn;
	}

	/**
	 * Publishes the WALLET_DEBIT_CMD after the DB transaction has committed.
	 * Called OUTSIDE @Transactional to ensure the DB commit happens first.
	 *
	 * WHY after commit?
	 *   If we publish inside the @Transactional method and the DB commit
	 *   fails afterward, Wallet Service will process a debit for a transaction
	 *   that doesn't exist in our DB — money moves but no record.
	 *   Publish after commit means: if publish fails, the transaction exists
	 *   in DB as PENDING_DEBIT and the SagaTimeoutJob will republish.
	 */
	private void publishDebitCommand(Transaction txn) {
		try {
			SagaState debitStep = sagaStateRepository
					.findByTransactionIdAndStep(txn.getId(), SagaStep.DEBIT_SENDER)
					.orElseThrow();
			walletCommandPublisher.publishDebitCommand(txn, debitStep.getCommandIdempotencyKey());
		} catch (Exception e) {
			// Kafka publish failed — SagaTimeoutJob will re-publish after timeout
			log.error("DEBIT_CMD publish failed for txnId={}: {}. Saga timeout job will retry.",
					txn.getId(), e.getMessage());
		}
	}

	private boolean acquireIdempotencyLock(String idempotencyKey) {
		try {
			Boolean acquired = redisTemplate.opsForValue()
					.setIfAbsent(
							TxnConstants.REDIS_TXN_LOCK_PREFIX + idempotencyKey,
							"1",
							Duration.ofSeconds(TxnConstants.IDEMPOTENCY_LOCK_TTL_SECONDS)
					);
			return Boolean.TRUE.equals(acquired);
		} catch (Exception e) {
			log.warn("Redis idempotency lock failed — falling back to DB check: {}", e.getMessage());
			return true; // Fail-open: allow DB UNIQUE constraint to be the guard
		}
	}

	private void releaseIdempotencyLock(String idempotencyKey) {
		try {
			redisTemplate.delete(TxnConstants.REDIS_TXN_LOCK_PREFIX + idempotencyKey);
		} catch (Exception e) {
			log.warn("Failed to release idempotency lock for key={}: {}", idempotencyKey, e.getMessage());
		}
	}

	private TransactionResponse handleDuplicateRequest(String idempotencyKey) {
		return transactionRepository.findByIdempotencyKey(idempotencyKey)
				.map(existing -> {
					log.debug("Returning existing transaction for idempotencyKey={}", idempotencyKey);
					return mapper.toResponse(existing);
				})
				.orElseThrow(() -> new DuplicateTransactionException(
						"Duplicate request in progress — please wait and retry"));
	}
}
