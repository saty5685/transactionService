package com.deezyWallet.transaction.enums;

/**
 * Types of commands published to wallet.commands topic.
 *
 * Mirrors the WalletCommandType enum in Wallet Service — kept in sync.
 * Transaction Service publishes these; Wallet Service consumes them.
 *
 * In a shared-library setup this would be a common library enum.
 * We duplicate it here to avoid coupling services at compile time.
 * The string value in the Kafka message is the source of truth.
 */
public enum WalletCommandTypeEnum {
	WALLET_DEBIT(1),
	WALLET_CREDIT(2),
	WALLET_UNBLOCK(3);

	private final int id;

	WalletCommandTypeEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static WalletCommandTypeEnum getEnumById(int id) {
		switch (id) {
			case 1 -> {
				return WALLET_DEBIT;
			}
			case 2 -> {
				return WALLET_CREDIT;
			}
			case 3 -> {
				return WALLET_UNBLOCK;
			}
			default -> {
				return null;
			}
		}
	}
}
