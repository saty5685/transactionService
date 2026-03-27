package com.deezyWallet.transaction.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.TxnEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published to txn.events when a transaction is reversed (compensation complete).
 * Consumed by:
 *   - Notification Service → informs sender of refund
 *   - Ledger Service       → records reversal entry
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TxnReversedEvent {
	private String        eventId;
	private TxnEventTypeEnum  eventType;        // TXN_REVERSED
	private String        transactionId;
	private String        senderUserId;
	private String        senderWalletId;
	private BigDecimal    amount;
	private LocalDateTime reversedAt;
	private LocalDateTime occurredAt;
}
