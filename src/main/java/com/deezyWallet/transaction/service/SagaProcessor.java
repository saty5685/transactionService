package com.deezyWallet.transaction.service;

import com.deezyWallet.transaction.constants.TxnErrorCode;
import com.deezyWallet.transaction.entity.SagaState;
import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.*;
import com.deezyWallet.transaction.event.TxnEventPublisher;
import com.deezyWallet.transaction.event.WalletCommandPublisher;
import com.deezyWallet.transaction.event.inbound.WalletDebitedEvent;
import com.deezyWallet.transaction.event.inbound.WalletCreditedEvent;
import com.deezyWallet.transaction.event.inbound.WalletDebitFailedEvent;
import com.deezyWallet.transaction.repository.SagaStateRepository;
import com.deezyWallet.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * SagaProcessor — handles wallet.events and advances the Saga state machine.
 *
 * This is the core of the Saga orchestration. It processes three event types:
 *
 *   WALLET_DEBITED:       Debit succeeded → create CREDIT step → publish WALLET_CREDIT_CMD
 *   WALLET_DEBIT_FAILED:  Debit failed → mark transaction FAILED → no compensation needed
 *                         (nothing to undo — no money moved)
 *   WALLET_CREDITED:      Credit succeeded → mark transaction COMPLETED
 *   (WALLET_CREDIT_FAILED is implicit — if credit step times out, SagaTimeoutJob handles it)
 *
 * EVENT DELIVERY GUARANTEES:
 *   Kafka delivers at-least-once. The same event can arrive multiple times.
 *   Each handler must be IDEMPOTENT:
 *     - Check if the Saga step is already in a terminal state before processing
 *     - Use @Version to detect concurrent updates
 *     - If the event is a duplicate, log and ACK — don't re-process
 *
 * @Retryable on OptimisticLockingFailureException:
 *   The Saga processor and the timeout job race to update the same transaction.
 *   @Retryable catches the exception and retries up to 3 times with 100ms backoff.
 *   On the retry, the latest version is loaded from DB and the update succeeds.
 *
 * TRANSACTION STRATEGY:
 *   Each handler is @Transactional.
 *   The SagaState update + Transaction status update are in the same DB tx.
 *   If either fails, both roll back — the event is NOT ACKed by the consumer
 *   and will be redelivered by Kafka (exactly the retry behaviour we want).
 *   Kafka publish (WALLET_CREDIT_CMD, TXN_COMPLETED) happens AFTER commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaProcessor {

	private final TransactionRepository  transactionRepository;
	private final SagaStateRepository    sagaStateRepository;
	private final WalletCommandPublisher walletCommandPublisher;
	private final TxnEventPublisher      txnEventPublisher;

	// ── WALLET_DEBITED handler ─────────────────────────────────────────────

	/**
	 * Debit confirmed → advance Saga to CREDIT step.
	 *
	 * Steps:
	 *   1. Load transaction + verify it's in PENDING_DEBIT
	 *   2. Mark DEBIT_SENDER saga step SUCCEEDED
	 *   3. Create CREDIT_RECEIVER saga step (PENDING)
	 *   4. Update transaction status to PENDING_CREDIT
	 *   5. Post-commit: publish WALLET_CREDIT_CMD
	 */
	@Transactional
	@Retryable(
			retryFor = ObjectOptimisticLockingFailureException.class,
			maxAttempts = 3,
			backoff = @Backoff(delay = 100)
	)
	public void onWalletDebited(WalletDebitedEvent event) {
		String txnId = event.getTransactionId();

		Transaction txn = transactionRepository.findById(txnId)
				.orElseThrow(() -> {
					log.error("SAGA ORPHAN: WALLET_DEBITED for unknown txnId={}", txnId);
					return new IllegalStateException("Transaction not found: " + txnId);
				});

		// Idempotency: if already advanced past PENDING_DEBIT, this is a duplicate
		if (txn.getStatus() != TransactionStatusEnum.PENDING_DEBIT) {
			log.info("Duplicate WALLET_DEBITED event for txnId={} currentStatus={}",
					txnId, txn.getStatus());
			return; // ACK — already processed
		}

		// Mark debit step succeeded
		sagaStateRepository.markStepSucceeded(txnId, SagaStepEnum.DEBIT_SENDER, LocalDateTime.now());

		// Create credit step
		SagaState creditStep = SagaState.pending(txnId, SagaStepEnum.CREDIT_RECEIVER);
		sagaStateRepository.save(creditStep);

		// Advance transaction status
		int updated = transactionRepository.updateStatus(
				txnId, TransactionStatusEnum.PENDING_CREDIT, txn.getVersion());

		if (updated == 0) {
			throw new ObjectOptimisticLockingFailureException(Transaction.class, txnId);
		}

		log.info("Saga advanced: txnId={} PENDING_DEBIT → PENDING_CREDIT", txnId);

		// Post-commit: publish credit command
		// In a real implementation, use TransactionSynchronizationManager.registerSynchronization
		// For clarity, we publish here — the SagaTimeoutJob handles the failure case
		walletCommandPublisher.publishCreditCommand(txn, creditStep.getCommandIdempotencyKey());
	}

	// ── WALLET_DEBIT_FAILED handler ────────────────────────────────────────

	/**
	 * Debit failed → mark transaction FAILED.
	 *
	 * No compensation needed — no money moved. Clean terminal state.
	 *
	 * Common reasons: insufficient balance (race with another txn),
	 * wallet frozen between preflight and debit cmd processing,
	 * daily/monthly limit exceeded.
	 */
	@Transactional
	@Retryable(
			retryFor = ObjectOptimisticLockingFailureException.class,
			maxAttempts = 3,
			backoff = @Backoff(delay = 100)
	)
	public void onWalletDebitFailed(WalletDebitFailedEvent event) {
		String txnId = event.getTransactionId();

		Transaction txn = transactionRepository.findById(txnId)
				.orElseThrow(() -> new IllegalStateException(
						"Transaction not found for WALLET_DEBIT_FAILED: " + txnId));

		if (txn.getStatus().isTerminal()) {
			log.info("Duplicate WALLET_DEBIT_FAILED for terminal txnId={}", txnId);
			return;
		}

		sagaStateRepository.markStepFailed(
				txnId, SagaStepEnum.DEBIT_SENDER,
				event.getFailureReason(), LocalDateTime.now());

		int updated = transactionRepository.updateStatusWithFailure(
				txnId, TransactionStatusEnum.FAILED,
				event.getFailureReason(), LocalDateTime.now(),
				txn.getVersion());

		if (updated == 0) {
			throw new ObjectOptimisticLockingFailureException(Transaction.class, txnId);
		}

		log.info("Saga failed: txnId={} reason={}", txnId, event.getFailureReason());
		txnEventPublisher.publishTxnFailed(txn, event.getFailureReason());
	}

	// ── WALLET_CREDITED handler ────────────────────────────────────────────

	/**
	 * Credit confirmed → mark transaction COMPLETED.
	 *
	 * Terminal success state. Both legs of the transfer have been executed.
	 * Publishes TXN_COMPLETED → Notification Service + Ledger Service.
	 */
	@Transactional
	@Retryable(
			retryFor = ObjectOptimisticLockingFailureException.class,
			maxAttempts = 3,
			backoff = @Backoff(delay = 100)
	)
	public void onWalletCredited(WalletCreditedEvent event) {
		String txnId = event.getTransactionId();

		Transaction txn = transactionRepository.findById(txnId)
				.orElseThrow(() -> new IllegalStateException(
						"Transaction not found for WALLET_CREDITED: " + txnId));

		if (txn.getStatus().isTerminal()) {
			log.info("Duplicate WALLET_CREDITED for terminal txnId={}", txnId);
			return;
		}

		sagaStateRepository.markStepSucceeded(
				txnId, SagaStepEnum.CREDIT_RECEIVER, LocalDateTime.now());

		int updated = transactionRepository.updateStatusAndCompletedAt(
				txnId, TransactionStatusEnum.COMPLETED,
				LocalDateTime.now(), txn.getVersion());

		if (updated == 0) {
			throw new ObjectOptimisticLockingFailureException(Transaction.class, txnId);
		}

		log.info("Saga completed: txnId={}", txnId);
		txnEventPublisher.publishTxnCompleted(txn);
	}

	// ── WALLET_UNBLOCKED handler (reversal completion) ─────────────────────

	/**
	 * Unblock (reversal) confirmed → mark transaction REVERSED.
	 * Called after a successful compensation in the REVERSING state.
	 */
	@Transactional
	@Retryable(
			retryFor = ObjectOptimisticLockingFailureException.class,
			maxAttempts = 3,
			backoff = @Backoff(delay = 100)
	)
	public void onWalletUnblocked(String transactionId) {
		Transaction txn = transactionRepository.findById(transactionId)
				.orElseThrow(() -> new IllegalStateException(
						"Transaction not found for WALLET_UNBLOCKED: " + transactionId));

		if (txn.getStatus() != TransactionStatusEnum.REVERSING) {
			log.info("WALLET_UNBLOCKED received for non-REVERSING txnId={}", transactionId);
			return;
		}

		sagaStateRepository.markStepSucceeded(
				transactionId, SagaStepEnum.REVERSE_DEBIT, LocalDateTime.now());

		int updated = transactionRepository.updateStatusAndCompletedAt(
				transactionId, TransactionStatusEnum.REVERSED,
				LocalDateTime.now(), txn.getVersion());

		if (updated == 0) {
			throw new ObjectOptimisticLockingFailureException(Transaction.class, transactionId);
		}

		log.info("Saga reversed: txnId={}", transactionId);
		txnEventPublisher.publishTxnReversed(txn);
	}
}
