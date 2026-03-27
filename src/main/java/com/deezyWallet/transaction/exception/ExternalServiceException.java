package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

import com.deezyWallet.transaction.constants.TxnErrorCode;

/**
 * A required upstream service (User Service, Wallet Service) is unreachable.
 * HTTP 503 Service Unavailable.
 */
public class ExternalServiceException extends TxnBaseException {
	public ExternalServiceException(String message) {
		super(TxnErrorCode.INTERNAL_ERROR, message, HttpStatus.SERVICE_UNAVAILABLE);
	}
}