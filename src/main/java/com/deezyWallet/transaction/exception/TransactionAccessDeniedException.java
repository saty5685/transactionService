package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

import com.deezyWallet.transaction.constants.TxnErrorCode;

/**
 * Authenticated user tried to access a transaction they don't own.
 *
 * HTTP 403 Forbidden — vague message to prevent IDOR enumeration.
 * We return the same error whether the transaction doesn't exist or
 * the user just doesn't own it — prevents "does this txnId exist?" probing.
 */
public class TransactionAccessDeniedException extends TxnBaseException {
	public TransactionAccessDeniedException(String message) {
		super(TxnErrorCode.ACCESS_DENIED, "Access denied", HttpStatus.FORBIDDEN);
	}
}
