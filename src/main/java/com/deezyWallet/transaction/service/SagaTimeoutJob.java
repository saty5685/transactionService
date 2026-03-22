package com.deezyWallet.transaction.service;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.entity.SagaState;
import com.deezyWallet.transaction.entity.Transaction;
import com.deezyWallet.transaction.enums.*;
import com.deezyWallet.transaction.event.WalletCommandPublisher;
import com.deezyWallet.transaction.repository.SagaStateRepository;
import com.deezyWallet.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SagaTimeoutJob — scheduled recovery for stuck Saga steps.
 *
 * Runs every 60 seconds and:
 *   1. Queries saga_state for PENDING steps older than their timeout threshold
 *   2. For each timed-out step:
 *      a. Loads the transaction with PESSIMISTIC_WRITE lock
 *      b. Verifies the transaction is still in a pending state
 *         (Saga processor may have advanced it between the query and the lock)
 *      c. Determines the appropriate compensation action
 *      d. Applies it atomically
 *
 * WHY a single cutoffTime for all step types?
 *   We use the tightest timeout (DEBIT) as the cutoff for the query.
 *   Steps with longer timeouts (CREDIT, REVERSAL) will be returned by the
 *   query but won't have their logic triggered until their own threshold passes.
 *   The per-step logic below checks the actual threshold.
 *
 * @EnableScheduling is required on a @Configuration class.
 *
 * DISTRIBUTED DEPLOYMENT NOTE:
 *   If Transaction Service runs with N replicas, all N will run this job
 *   simultaneously. The PESSIMISTIC_WRITE lock on the transaction row ensures
 *   only one instance processes each timed-out step. The others will find
 *   the row locked, wait, then find the status already terminal and skip.
 *
 *   Production hardening: use ShedLock or Spring's @Scheduled with a
 *   distributed lock to run the job on only ONE replica at a time.
 *   The current approach (per-row locking) is correct but generates extra
 *   DB lock contention. ShedLock is the recommended next step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutJob {

	private final SagaStateRepository   sagaStateRepository;
	private final TransactionRepository transactionRepository;
	private final WalletCommandPublisher walletCommandPublisher;

	@Scheduled(fixedDelayString = "${saga.timeout-job.interval-ms:60000}")
	public void detectAndHandleTimeouts() {
		// Use the tightest timeout as the query cutoff
		LocalDateTime cutoff = LocalDateTime.now()
				.minusSeconds(TxnConstants.SAGA_DEBIT_TIMEOUT_SECONDS);

		List<SagaState> timedOutSteps = sagaStateRepository.findTimedOutSteps(cutoff);

		if (timedOutSteps.isEmpty()) {
			return; // Nothing to do — common case
		}

		log.warn("SagaTimeoutJob found {} stuck Saga step(s)", timedOutSteps.size());

		for (SagaState step : timedOutSteps) {
			try {
				handleTimedOutStep(step);
			} catch (Exception e) {
				// Never let one failed step block processing of others
				log.error("Error handling timed-out step id={} txnId={}: {}",
						step.getId(), step.getTransactionId(), e.getMessage(), e);
			}
		}
	}

	@Transactional
	protected void handleTimedOutStep(SagaState step) {
		// Load transaction with PESSIMISTIC_WRITE lock
		Transaction txn = transactionRepository
				.findByIdWithLock(step.getTransactionId())
				.orElse(null);

		if (txn == null) {
			log.error("SAGA ORPHAN: SagaState id={} references non-existent txnId={}",
					step.getId(), step.getTransactionId());
			sagaStateRepository.markStepTimedOut(step.getId(), LocalDateTime.now());
			return;
		}

		// Double-check: has the Saga processor advanced the transaction since the query?
		if (txn.getStatus().isTerminal()) {
			log.debug("Timeout job: txnId={} already terminal — skipping", txn.getId());
			// The saga step will remain PENDING but the txn is terminal — safe, just stale
			sagaStateRepository.markStepTimedOut(step.getId(), LocalDateTime.now());
			return;
		}

		// Verify this step has genuinely timed out (not just query lag)
		int timeoutSeconds = switch (step.getStep()) {
			case DEBIT_SENDER   -> TxnConstants.SAGA_DEBIT_TIMEOUT_SECONDS;
			case CREDIT_RECEIVER-> TxnConstants.SAGA_CREDIT_TIMEOUT_SECONDS;
			case REVERSE_DEBIT  -> TxnConstants.SAGA_REVERSAL_TIMEOUT_SECONDS;
			default             -> TxnConstants.SAGA_DEBIT_TIMEOUT_SECONDS;
		};

		if (step.getStartedAt().isAfter(LocalDateTime.now().minusSeconds(timeoutSeconds))) {
			// Not yet timed out for this step type — skip
			return;
		}

		log.warn("Timeout confirmed: txnId={} step={} startedAt={}",
				txn.getId(), step.getStep(), step.getStartedAt());

		sagaStateRepository.markStepTimedOut(step.getId(), LocalDateTime.now());

		switch (step.getStep()) {
			case DEBIT_SENDER -> handleDebitTimeout(txn);
			case CREDIT_RECEIVER -> handleCreditTimeout(txn);
			case REVERSE_DEBIT -> handleReversalTimeout(txn);
			default -> log.warn("Unknown step type in timeout handler: {}", step.getStep());
		}
	}

	/**
	 * Debit step timed out — no money moved yet.
	 * Safe to mark TIMED_OUT directly — no compensation needed.
	 *
	 * WHY not retry the debit command?
	 *   The DEBIT_CMD was published with an idempotency key.
	 *   If Wallet Service processed it but the ACK was lost, retrying would
	 *   result in a duplicate key rejection — money wouldn't double-debit.
	 *   But we can't distinguish "never processed" from "processed but ACK lost".
	 *   Safest: mark TIMED_OUT and let the user retry if they want to proceed.
	 */
	private void handleDebitTimeout(Transaction txn) {
		transactionRepository.updateStatusWithFailure(
				txn.getId(), TransactionStatusEnum.TIMED_OUT,
				TxnConstants.REDIS_TXN_LOCK_PREFIX + "DEBIT_TIMEOUT",
				LocalDateTime.now(), txn.getVersion());
		log.warn("Transaction TIMED_OUT at debit step: txnId={}", txn.getId());
	}

	/**
	 * Credit step timed out — debit already succeeded.
	 * Must compensate: publish WALLET_UNBLOCK_CMD to return funds to sender.
	 *
	 * WHY not retry the credit command?
	 *   Same uncertainty as debit timeout — can't distinguish lost vs unprocessed.
	 *   Compensation is the safe choice: return money to sender, let them retry.
	 *
	 * WHY REVERSING not FAILED?
	 *   Compensation is in progress — another async step must complete.
	 *   FAILED would imply no further processing, which is wrong here.
	 */
	private void handleCreditTimeout(Transaction txn) {
		transactionRepository.updateStatusWithFailure(
				txn.getId(), TransactionStatusEnum.REVERSING,
				"Credit step timed out — reversal initiated",
				LocalDateTime.now(), txn.getVersion());

		SagaState reverseStep = SagaState.pending(txn.getId(), SagaStepEnum.REVERSE_DEBIT);
		sagaStateRepository.save(reverseStep);

		walletCommandPublisher.publishUnblockCommand(txn, reverseStep.getCommandIdempotencyKey());

		log.warn("Credit timeout — reversal initiated: txnId={}", txn.getId());
	}

	/**
	 * Reversal step timed out — funds may be in inconsistent state.
	 * Requires manual intervention from operations team.
	 * REVERSAL_FAILED is terminal — no further automated processing.
	 */
	private void handleReversalTimeout(Transaction txn) {
		transactionRepository.updateStatusWithFailure(
				txn.getId(), TransactionStatusEnum.REVERSAL_FAILED,
				"Reversal timed out — manual intervention required",
				LocalDateTime.now(), txn.getVersion());

		log.error("CRITICAL: Reversal timed out for txnId={} — manual intervention required",
				txn.getId());
		// In production: trigger a PagerDuty/OpsGenie alert here
	}
}