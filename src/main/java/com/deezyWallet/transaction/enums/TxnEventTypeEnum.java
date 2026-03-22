package com.deezyWallet.transaction.enums;

/**
 * Event types published to txn.events topic by Transaction Service.
 *
 * Consumed by:
 *   TXN_INITIATED  → Notification Service (txn confirmation push)
 *   TXN_COMPLETED  → Notification Service (success push + SMS to both parties)
 *                 → Ledger Service (record double-entry bookkeeping)
 *   TXN_FAILED     → Notification Service (failure alert)
 *   TXN_REVERSED   → Notification Service (reversal confirmation)
 *                 → Ledger Service (reversal ledger entry)
 */
public enum TxnEventTypeEnum {
	TXN_INITIATED(1),
	TXN_COMPLETED(2),
	TXN_FAILED(3),
	TXN_REVERSED(4),
	TXN_TIMED_OUT(5);

	private final int id;

	TxnEventTypeEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static TxnEventTypeEnum getEnumById(int id) {
		switch (id) {
			case 1 -> {
				return TXN_INITIATED;
			}
			case 2 -> {
				return TXN_COMPLETED;
			}
			case 3 -> {
				return TXN_FAILED;
			}
			case 4 -> {
				return TXN_REVERSED;
			}
			case 5 -> {
				return TXN_TIMED_OUT;
			}
			default -> {
				return null;
			}
		}
	}
}