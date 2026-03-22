package com.deezyWallet.transaction.enums;

/**
 * Status of an individual Saga step.
 *
 * PENDING:   Command published; waiting for Kafka response event.
 * SUCCEEDED: Corresponding success event received.
 * FAILED:    Corresponding failure event received.
 * TIMED_OUT: No event received within the step's timeout window.
 *            Timeout job transitions this and triggers compensation.
 * SKIPPED:   Step was not needed (e.g. REVERSE_DEBIT skipped if debit never happened).
 */
public enum SagaStepStatusEnum {
	PENDING(1),
	SUCCEEDED(2),
	FAILED(3),
	TIMED_OUT(4),
	SKIPPED(5);

	private final int id;

	SagaStepStatusEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static SagaStepStatusEnum getEnumById(int id) {
		switch (id) {
			case 1 -> {
				return PENDING;
			}
			case 2 -> {
				return SUCCEEDED;
			}
			case 3 -> {
				return FAILED;
			}
			case 4 -> {
				return TIMED_OUT;
			}
			case 5 -> {
				return SKIPPED;
			}
			default -> {
				return null;
			}
		}
	}
}
