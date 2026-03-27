package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

/**
 * Fraud Service is unreachable or returned an error.
 *
 * HTTP 503 Service Unavailable — the transaction cannot be processed right now.
 * Client should retry after a brief delay.
 */
public class FraudServiceException extends TxnBaseException {
	public FraudServiceException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.SERVICE_UNAVAILABLE);
	}
}
