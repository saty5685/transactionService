package com.deezyWallet.transaction.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.TxnEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published to txn.events when a transaction completes successfully.
 *
 * Consumed by:
 *   - Notification Service → sends push + SMS to both parties
 *   - Ledger Service       → records double-entry bookkeeping entries
 *
 * WHY include both userIds AND walletIds?
 *   Notification Service needs userIds (to look up push tokens).
 *   Ledger Service needs walletIds (to record which wallets moved).
 *   Including both avoids two separate event types or an extra lookup.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TxnCompletedEvent {
	private String        eventId;
	private TxnEventTypeEnum  eventType;        // TXN_COMPLETED
	private String        transactionId;
	private String        transactionType;  // "P2P_TRANSFER", "MERCHANT_PAYMENT"
	private String        senderUserId;
	private String        senderWalletId;
	private String        receiverUserId;
	private String        receiverWalletId;
	private BigDecimal    amount;
	private String        currency;
	private LocalDateTime completedAt;
	private LocalDateTime occurredAt;
}
