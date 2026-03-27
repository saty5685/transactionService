package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

/**
 * Business validation failure before a transaction starts.
 *
 * Examples: self-transfer, receiver not found, KYC required, amount over limit.
 * Maps to HTTP 422 Unprocessable Entity — the request was well-formed
 * but violates business rules.
 *
 * WHY 422 and not 400?
 *   400 Bad Request = the HTTP request itself is malformed (invalid JSON,
 *   missing required field). Caught by @Valid / MethodArgumentNotValidException.
 *   422 Unprocessable Entity = the request is syntactically valid but
 *   semantically invalid for the current business state (receiver suspended,
 *   balance too low). The distinction helps clients route to the right error UI.
 */
public class TransactionValidationException extends TxnBaseException {
	public TransactionValidationException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.UNPROCESSABLE_ENTITY);
	}
}
