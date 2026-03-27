package com.deezyWallet.transaction.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezyWallet.transaction.enums.TxnEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published to txn.events when a transaction fails.
 * Consumed by Notification Service → sends failure alert to sender only.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TxnFailedEvent {
	private String        eventId;
	private TxnEventTypeEnum  eventType;        // TXN_FAILED
	private String        transactionId;
	private String        senderUserId;
	private BigDecimal    amount;
	private String        failureReason;
	private LocalDateTime occurredAt;
}
