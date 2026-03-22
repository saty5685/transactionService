package com.deezyWallet.transaction.enums;

/**
 * Full lifecycle state machine for a transaction.
 *
 * VALID TRANSITIONS:
 * ─────────────────────────────────────────────────────────────────────
 *  INITIATED ──────────────────────────────→ PENDING_DEBIT
 *  PENDING_DEBIT ──── WALLET_DEBITED ──────→ PENDING_CREDIT
 *  PENDING_DEBIT ──── WALLET_DEBIT_FAILED ─→ FAILED          (terminal)
 *  PENDING_DEBIT ──── timeout ─────────────→ TIMED_OUT        (terminal)
 *  PENDING_CREDIT ─── WALLET_CREDITED ─────→ COMPLETED        (terminal)
 *  PENDING_CREDIT ─── credit failure ──────→ REVERSING
 *  REVERSING ──────── WALLET_UNBLOCKED ────→ REVERSED         (terminal)
 *  REVERSING ──────── timeout ─────────────→ REVERSAL_FAILED  (manual intervention)
 * ─────────────────────────────────────────────────────────────────────
 *
 * WHY PENDING_DEBIT and PENDING_CREDIT as separate states?
 *   Each represents a distinct Saga step with its own command/event pair.
 *   Separating them allows the timeout job to know exactly WHICH step is
 *   stuck — it can then issue the correct compensation command without
 *   needing to inspect the saga_state log.
 *
 * Terminal states: COMPLETED, FAILED, TIMED_OUT, REVERSED, REVERSAL_FAILED.
 *   Terminal states must NEVER be transitioned out of.
 *   isTerminal() enforces this at the application layer. The saga processor
 *   checks this before applying any state change.
 */
public enum TransactionStatusEnum {

	/** Preflight checks passed; Saga not yet started */
	INITIATED(1),

	/** WALLET_DEBIT_CMD published; awaiting WALLET_DEBITED or WALLET_DEBIT_FAILED */
	PENDING_DEBIT(2),

	/** Debit confirmed; WALLET_CREDIT_CMD published; awaiting WALLET_CREDITED */
	PENDING_CREDIT(3),

	/** Both legs completed successfully — terminal */
	COMPLETED(4),

	/** Debit failed before any money moved — terminal */
	FAILED(5),

	/** Timed out in a PENDING_* state — terminal (manual review or auto-reversal) */
	TIMED_OUT(6),

	/**
	 * Credit failed after successful debit — compensation in progress.
	 * Wallet Service has blocked the sender's funds (via blockFunds Saga step).
	 * WALLET_UNBLOCK_CMD published; awaiting WALLET_UNBLOCKED.
	 */
	REVERSING(7),

	/** Compensation completed; funds returned to sender — terminal */
	REVERSED(8),

	/**
	 * Reversal itself failed — requires manual intervention.
	 * Funds may be in an inconsistent state. Operations team must resolve.
	 * Terminal: no further automated transitions.
	 */
	REVERSAL_FAILED(9);

	private final int id;

	TransactionStatusEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static TransactionStatusEnum getEnumById(int id) {
		switch (id) {
			case 1 -> {
				return INITIATED;
			}
			case 2 -> {
				return PENDING_DEBIT;
			}
			case 3 -> {
				return PENDING_CREDIT;
			}
			case 4 -> {
				return COMPLETED;
			}
			case 5 -> {
				return FAILED;
			}
			case 6 -> {
				return TIMED_OUT;
			}
			case 7 -> {
				return REVERSING;
			}
			case 8 -> {
				return REVERSED;
			}
			case 9 -> {
				return REVERSAL_FAILED;
			}
			default -> {
				return null;
			}
		}
	}

	public boolean isTerminal() {
		return switch (this) {
			case COMPLETED, FAILED, TIMED_OUT, REVERSED, REVERSAL_FAILED -> true;
			default -> false;
		};
	}

	public boolean isPending() {
		return this == PENDING_DEBIT || this == PENDING_CREDIT || this == REVERSING;
	}
}
