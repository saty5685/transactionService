package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * Abstract base for all Transaction Service domain exceptions.
 *
 * Same pattern as User Service and Wallet Service — carries errorCode and
 * httpStatus so GlobalExceptionHandler can build a consistent ErrorResponse
 * without a handler per subclass.
 */
@Getter
public abstract class TxnBaseException extends RuntimeException {

	private final String     errorCode;
	private final HttpStatus httpStatus;

	protected TxnBaseException(String errorCode, String message, HttpStatus httpStatus) {
		super(message);
		this.errorCode  = errorCode;
		this.httpStatus = httpStatus;
	}
}
