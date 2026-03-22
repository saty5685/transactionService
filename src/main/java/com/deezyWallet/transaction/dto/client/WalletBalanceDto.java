package com.deezyWallet.transaction.dto.client;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for deserialising the Wallet Service internal balance response.
 *
 * Transaction Service calls GET /internal/v1/wallets/{walletId}/balance
 * and deserialises into this DTO for the preflight balance check.
 *
 * WHY a preflight balance check when Wallet Service checks balance again during debit?
 *   The preflight is a fast-fail optimisation — reject obvious insufficiency
 *   BEFORE persisting the transaction record and starting the Saga.
 *   Without the preflight, we would:
 *   1. Persist the transaction (DB write)
 *   2. Start the Saga (Kafka publish)
 *   3. Wallet Service debit fails (Kafka event back)
 *   4. Saga marks FAILED (another DB write + Kafka publish)
 *   All of that for a transaction that was doomed from the start.
 *
 *   The preflight adds one HTTP call but eliminates ~4 Kafka round-trips and
 *   2 DB writes for the (common) case of the user trying to overspend.
 *
 *   IMPORTANT: The preflight is NOT the authoritative balance check.
 *   Wallet Service's PESSIMISTIC_WRITE lock during actual debit is the
 *   authoritative check. The preflight can be stale by up to 30 seconds
 *   (Redis cache TTL). That's acceptable — it's a fast fail, not a guarantee.
 */
@Data
@NoArgsConstructor
public class WalletBalanceDto {
	private String     walletId;
	private BigDecimal availableBalance;
	private BigDecimal blockedBalance;
	private String     source; // "CACHE" or "DB"
}
