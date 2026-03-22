package com.deezyWallet.transaction.enums;

/**
 * Discrete steps in the Saga state machine — one row per step in saga_state table.
 *
 * Each step has a corresponding command published to Kafka and an expected
 * response event. The saga_state table records: step, status, timestamp.
 *
 * PREFLIGHT:       Sync checks — user status, fraud score, balance preflight.
 *                  No Kafka involved. Fails fast before any money moves.
 * DEBIT_SENDER:    WALLET_DEBIT_CMD → WALLET_DEBITED / WALLET_DEBIT_FAILED
 * CREDIT_RECEIVER: WALLET_CREDIT_CMD → WALLET_CREDITED
 * REVERSE_DEBIT:   Compensation — WALLET_UNBLOCK_CMD → WALLET_UNBLOCKED
 *                  Only reached if CREDIT_RECEIVER fails after DEBIT_SENDER succeeded.
 *
 * WHY store step in DB?
 *   The Saga processor needs to know which step to issue compensation for.
 *   Storing step + status + timestamp enables:
 *   - Timeout detection (step PENDING older than threshold)
 *   - Compensation routing (which command to issue)
 *   - Audit trail (full Saga history per transaction)
 *   - Replay (re-publish a step's command if the event was lost)
 */
public enum SagaStepEnum {
	PREFLIGHT(1),
	DEBIT_SENDER(2),
	CREDIT_RECEIVER(3),
	REVERSE_DEBIT(4);

	private final int id;

	SagaStepEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static SagaStepEnum getEnumById(int id) {
		switch (id) {
			case 1 -> {
				return PREFLIGHT;
			}
			case 2 -> {
				return DEBIT_SENDER;
			}
			case 3 -> {
				return CREDIT_RECEIVER;
			}
			case 4 -> {
				return REVERSE_DEBIT;
			}
			default -> {
				return null;
			}
		}
	}
}
