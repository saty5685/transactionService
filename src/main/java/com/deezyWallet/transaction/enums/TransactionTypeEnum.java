package com.deezyWallet.transaction.enums;

/**
 * Business type of the transaction — determines routing, limits, and ledger entries.
 *
 * P2P_TRANSFER:    User → User. Both wallets on our platform.
 * MERCHANT_PAYMENT: User → Merchant. Merchant has a ROLE_MERCHANT wallet.
 * WALLET_TOPUP:    External payment → User wallet. Initiated by Payment Service.
 * WALLET_WITHDRAWAL: User wallet → Bank account. Initiated via Payment Service.
 * REFUND:          Merchant-initiated reversal back to user.
 *
 * WHY not just "DEBIT" and "CREDIT"?
 *   The business type drives different validation rules, fraud models, and
 *   ledger entry formats. A P2P transfer needs both sender KYC and receiver
 *   existence checks. A top-up has no receiver wallet check (credit is from
 *   external). Conflating them into DEBIT/CREDIT loses this context.
 */
public enum TransactionTypeEnum {
	P2P_TRANSFER(1),
	MERCHANT_PAYMENT(2),
	WALLET_TOPUP(3),
	WALLET_WITHDRAWAL(4),
	REFUND(5);

	private final int id;

	TransactionTypeEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static TransactionTypeEnum getEnumById(int id) {
		switch (id) {
			case 1 -> {
				return P2P_TRANSFER;
			}
			case 2 -> {
				return MERCHANT_PAYMENT;
			}
			case 3 -> {
				return WALLET_TOPUP;
			}
			case 4 -> {
				return WALLET_WITHDRAWAL;
			}
			case 5 -> {
				return REFUND;
			}
			default -> {
				return null;
			}
		}
	}
}
