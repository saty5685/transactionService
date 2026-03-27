package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

/**
 * Transaction rejected by fraud evaluation.
 *
 * HTTP 403 Forbidden — the transaction is blocked by policy.
 *
 * IMPORTANT: message must be generic — never reveal the fraud score or model logic.
 *   "Transaction declined due to risk assessment" is the right level of vagueness.
 *   "Your fraud score was 0.89 which exceeds our 0.75 threshold" is not.
 *   Revealing score details helps attackers calibrate their fraud patterns.
 */
public class FraudRejectedException extends TxnBaseException {
	public FraudRejectedException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.FORBIDDEN);
	}
}
