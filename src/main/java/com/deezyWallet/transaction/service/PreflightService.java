package com.deezyWallet.transaction.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.constants.TxnErrorCode;
import com.deezyWallet.transaction.dto.client.FraudEvaluationDto;
import com.deezyWallet.transaction.dto.client.UserStatusDto;
import com.deezyWallet.transaction.dto.client.WalletBalanceDto;
import com.deezyWallet.transaction.enums.TransactionTypeEnum;
import com.deezyWallet.transaction.exception.ExternalServiceException;
import com.deezyWallet.transaction.exception.FraudRejectedException;
import com.deezyWallet.transaction.exception.FraudServiceException;
import com.deezyWallet.transaction.exception.TransactionValidationException;
import com.deezyWallet.transaction.security.UserPrincipal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PreflightService — synchronous checks run BEFORE the Saga starts.
 *
 * PURPOSE:
 *   Fast-fail on conditions that would make the Saga certain to fail.
 *   Every check here saves 4 Kafka round-trips and 2 DB writes that
 *   would result from a doomed Saga running to completion before failing.
 *
 * CHECKS (in order):
 *   1. Self-transfer guard — sender cannot pay themselves
 *   2. Sender status — must be ACTIVE (from User Service internal API)
 *   3. Sender KYC — must be VERIFIED for high-value transfers
 *   4. Receiver status — must be ACTIVE and wallet operational
 *   5. Balance preflight — sender must have sufficient available balance
 *   6. Amount limits — within per-transaction type ceilings
 *   7. Fraud evaluation — score below threshold (fail-safe: reject on timeout)
 *
 * TRANSACTION STRATEGY:
 *   NO @Transactional — all checks are read-only sync HTTP calls.
 *   No DB writes happen here. Holding a DB transaction open during
 *   3 HTTP calls (user status, balance, fraud) would exhaust the pool.
 *
 * FAIL-SAFE vs FAIL-OPEN on external service errors:
 *   User Service timeout   → throw (preflight fails, txn rejected) — FAIL-SAFE
 *   Wallet Service timeout → throw (preflight fails, txn rejected) — FAIL-SAFE
 *   Fraud Service timeout  → throw FRAUD_SERVICE_UNAVAILABLE       — FAIL-SAFE
 *
 *   WHY fail-safe for all?
 *     A payment system must never approve a transaction when it can't
 *     verify the preconditions. Failing safe means a brief outage rejects
 *     some legitimate transactions — annoying but recoverable.
 *     Failing open means approving fraudulent or over-limit transactions —
 *     not recoverable (money is gone).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreflightService {

	private final RestTemplate restTemplate;

	@Value("${services.user-service.base-url}")
	private String userServiceBaseUrl;

	@Value("${services.wallet-service.base-url}")
	private String walletServiceBaseUrl;

	@Value("${services.fraud-service.base-url}")
	private String fraudServiceBaseUrl;

	// Token injection handled automatically by InternalAuthInterceptor on RestTemplate

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Runs all preflight checks for a P2P transfer.
	 *
	 * @param senderPrincipal  authenticated sender from JWT
	 * @param receiverIdentifier userId or phone number of the receiver
	 * @param amount           requested transfer amount
	 * @return resolved receiver userId (for callers that need it)
	 */
	public PreflightResult runForTransfer(UserPrincipal senderPrincipal,
			String        receiverIdentifier,
			BigDecimal    amount) {
		// 1. Self-transfer guard
		validateNotSelfTransfer(senderPrincipal.getUserId(), receiverIdentifier);

		// 2. Sender status (re-verify against source of truth — don't trust JWT alone)
		UserStatusDto senderStatus = fetchUserStatus(senderPrincipal.getUserId());
		validateSenderActive(senderStatus);

		// 3. KYC check for high-value transfers
		if (amount.compareTo(new BigDecimal("10000.00")) >= 0) {
			validateSenderKyc(senderStatus);
		}

		// 4. Receiver resolution + status
		String receiverUserId = resolveReceiverId(receiverIdentifier);
		UserStatusDto receiverStatus = fetchUserStatus(receiverUserId);
		validateReceiverActive(receiverStatus);

		// 5. Amount ceiling
		validateAmountLimit(amount, TransactionTypeEnum.P2P_TRANSFER);

		// 6. Sender wallet balance preflight
		WalletBalanceDto senderBalance = fetchWalletBalance(senderPrincipal.getUserId());
		validateSufficientBalance(senderBalance, amount);

		// 7. Receiver wallet ID (needed for the Saga credit command)
		String receiverWalletId = fetchWalletId(receiverUserId);

		// 8. Fraud evaluation
		BigDecimal fraudScore = evaluateFraud(
				senderPrincipal, receiverUserId, senderBalance.getWalletId(),
				receiverWalletId, amount, TransactionTypeEnum.P2P_TRANSFER);

		return PreflightResult.builder()
				.senderWalletId(senderBalance.getWalletId())
				.receiverUserId(receiverUserId)
				.receiverWalletId(receiverWalletId)
				.fraudScore(fraudScore)
				.build();
	}

	/**
	 * Runs all preflight checks for a merchant payment.
	 * Skips receiver KYC (merchants are pre-verified during onboarding).
	 */
	public PreflightResult runForMerchantPayment(UserPrincipal senderPrincipal,
			String        merchantWalletId,
			BigDecimal    amount) {
		// 1. Sender status
		UserStatusDto senderStatus = fetchUserStatus(senderPrincipal.getUserId());
		validateSenderActive(senderStatus);

		// 2. KYC for amounts >= threshold
		if (amount.compareTo(new BigDecimal("50000.00")) >= 0) {
			validateSenderKyc(senderStatus);
		}

		// 3. Amount ceiling
		validateAmountLimit(amount, TransactionTypeEnum.MERCHANT_PAYMENT);

		// 4. Sender balance preflight
		WalletBalanceDto senderBalance = fetchWalletBalance(senderPrincipal.getUserId());
		validateSufficientBalance(senderBalance, amount);

		// 5. Resolve merchant's userId from walletId
		String merchantUserId = fetchUserIdByWalletId(merchantWalletId);

		// 6. Fraud evaluation
		BigDecimal fraudScore = evaluateFraud(
				senderPrincipal, merchantUserId, senderBalance.getWalletId(),
				merchantWalletId, amount, TransactionTypeEnum.MERCHANT_PAYMENT);

		return PreflightResult.builder()
				.senderWalletId(senderBalance.getWalletId())
				.receiverUserId(merchantUserId)
				.receiverWalletId(merchantWalletId)
				.fraudScore(fraudScore)
				.build();
	}

	// ── Validation helpers ────────────────────────────────────────────────────

	private void validateNotSelfTransfer(String senderUserId, String receiverIdentifier) {
		if (senderUserId.equals(receiverIdentifier)) {
			throw new TransactionValidationException(
					TxnErrorCode.SELF_TRANSFER_NOT_ALLOWED,
					"Cannot transfer funds to yourself");
		}
	}

	private void validateSenderActive(UserStatusDto status) {
		if (!status.isCanTransact()) {
			throw new TransactionValidationException(
					TxnErrorCode.SENDER_NOT_ACTIVE,
					"Your account is not eligible to make transactions");
		}
	}

	private void validateSenderKyc(UserStatusDto status) {
		if (!"VERIFIED".equals(status.getKycStatus())) {
			throw new TransactionValidationException(
					TxnErrorCode.SENDER_KYC_REQUIRED,
					"KYC verification required for transactions above this amount");
		}
	}

	private void validateReceiverActive(UserStatusDto status) {
		if (!status.isCanTransact()) {
			throw new TransactionValidationException(
					TxnErrorCode.RECEIVER_NOT_ACTIVE,
					"The recipient's account cannot receive transfers at this time");
		}
	}

	private void validateAmountLimit(BigDecimal amount, TransactionTypeEnum type) {
		BigDecimal ceiling = switch (type) {
			case P2P_TRANSFER      -> TxnConstants.MAX_P2P_AMOUNT;
			case MERCHANT_PAYMENT  -> TxnConstants.MAX_MERCHANT_AMOUNT;
			default                -> TxnConstants.MAX_P2P_AMOUNT;
		};
		if (amount.compareTo(ceiling) > 0) {
			throw new TransactionValidationException(
					TxnErrorCode.INVALID_AMOUNT,
					"Amount exceeds the maximum allowed for this transaction type: ₹" + ceiling);
		}
	}

	private void validateSufficientBalance(WalletBalanceDto balance, BigDecimal amount) {
		// availableBalance = total balance - blockedBalance
		if (balance.getAvailableBalance().compareTo(amount) < 0) {
			throw new TransactionValidationException(
					TxnErrorCode.INSUFFICIENT_BALANCE,
					"Insufficient wallet balance for this transaction");
		}
	}

	// ── External service calls ────────────────────────────────────────────────

	private UserStatusDto fetchUserStatus(String userId) {
		try {
			ResponseEntity<UserStatusDto> resp = restTemplate.getForEntity(
					userServiceBaseUrl + "/internal/v1/users/" + userId + "/status",
					UserStatusDto.class);
			if (resp.getBody() == null) {
				throw new TransactionValidationException(
						TxnErrorCode.RECEIVER_NOT_FOUND, "User not found: " + userId);
			}
			return resp.getBody();
		} catch (ResourceAccessException e) {
			log.error("User Service unreachable during preflight: {}", e.getMessage());
			throw new ExternalServiceException("User Service temporarily unavailable");
		}
	}

	private WalletBalanceDto fetchWalletBalance(String userId) {
		try {
			ResponseEntity<WalletBalanceDto> resp = restTemplate.getForEntity(
					walletServiceBaseUrl + "/internal/v1/wallets/user/" + userId + "/balance",
					WalletBalanceDto.class);
			if (resp.getBody() == null) {
				throw new ExternalServiceException("Could not retrieve wallet balance");
			}
			return resp.getBody();
		} catch (ResourceAccessException e) {
			log.error("Wallet Service unreachable during balance preflight: {}", e.getMessage());
			throw new ExternalServiceException("Wallet Service temporarily unavailable");
		}
	}

	private String fetchWalletId(String userId) {
		WalletBalanceDto balance = fetchWalletBalance(userId);
		return balance.getWalletId();
	}

	private String fetchUserIdByWalletId(String walletId) {
		try {
			// GET /internal/v1/wallets/{walletId}/status returns walletId + userId
			var resp = restTemplate.getForEntity(
					walletServiceBaseUrl + "/internal/v1/wallets/" + walletId + "/status",
					java.util.Map.class);
			if (resp.getBody() == null) {
				throw new TransactionValidationException(
						TxnErrorCode.RECEIVER_NOT_FOUND, "Merchant wallet not found");
			}
			return (String) resp.getBody().get("userId");
		} catch (ResourceAccessException e) {
			throw new ExternalServiceException("Wallet Service temporarily unavailable");
		}
	}

	private String resolveReceiverId(String identifier) {
		// If UUID format → use directly as userId
		// If phone number format → look up via User Service
		if (identifier.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
			return identifier;
		}
		// Phone number lookup
		try {
			var resp = restTemplate.getForEntity(
					userServiceBaseUrl + "/internal/v1/users/phone/" + identifier + "/status",
					UserStatusDto.class);
			if (resp.getBody() == null || resp.getBody().getUserId() == null) {
				throw new TransactionValidationException(
						TxnErrorCode.RECEIVER_NOT_FOUND, "Recipient not found");
			}
			return resp.getBody().getUserId();
		} catch (ResourceAccessException e) {
			throw new ExternalServiceException("User Service temporarily unavailable");
		}
	}

	private BigDecimal evaluateFraud(UserPrincipal  sender,
			String         receiverUserId,
			String         senderWalletId,
			String         receiverWalletId,
			BigDecimal     amount,
			TransactionTypeEnum type) {
		FraudEvaluationDto.Request req = FraudEvaluationDto.Request.builder()
				.transactionType(type.name())
				.senderUserId(sender.getUserId())
				.senderWalletId(senderWalletId)
				.receiverUserId(receiverUserId)
				.receiverWalletId(receiverWalletId)
				.amount(amount)
				.currency("INR")
				.senderKycStatus(sender.getKycStatus())
				.build();

		try {
			ResponseEntity<FraudEvaluationDto.Response> resp = restTemplate.postForEntity(
					fraudServiceBaseUrl + "/fraud/evaluate",
					req,
					FraudEvaluationDto.Response.class);

			FraudEvaluationDto.Response fraudResp = resp.getBody();
			if (fraudResp == null) {
				throw new FraudServiceException(TxnErrorCode.FRAUD_SERVICE_UNAVAILABLE,
						"No response from Fraud Service");
			}

			if ("DECLINE".equals(fraudResp.getDecision()) ||
					fraudResp.getScore() > TxnConstants.FRAUD_SCORE_THRESHOLD) {
				log.warn("Transaction rejected by fraud evaluation: score={} decision={}",
						fraudResp.getScore(), fraudResp.getDecision());
				throw new FraudRejectedException(TxnErrorCode.FRAUD_SCORE_EXCEEDED,
						"Transaction declined due to risk assessment");
			}

			return BigDecimal.valueOf(fraudResp.getScore());

		} catch (ResourceAccessException e) {
			// Fraud Service timeout — FAIL-SAFE: reject the transaction
			log.error("Fraud Service timeout during evaluation: {}", e.getMessage());
			throw new FraudServiceException(TxnErrorCode.FRAUD_SERVICE_UNAVAILABLE,
					"Risk assessment service is temporarily unavailable. Please try again.");
		}
	}

	// ── Result carrier ────────────────────────────────────────────────────────

	@lombok.Value
	@lombok.Builder
	public static class PreflightResult {
		String     senderWalletId;
		String     receiverUserId;
		String     receiverWalletId;
		BigDecimal fraudScore;
	}
}
