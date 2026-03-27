package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

import com.deezyWallet.transaction.constants.TxnErrorCode;

/**
 * Idempotency key conflict where the existing transaction isn't yet in DB
 * (another thread is still processing the first request).
 *
 * HTTP 409 Conflict — client should wait briefly and retry.
 * Distinct from the normal idempotency case where we return the existing txn
 * with 200 OK. This 409 only fires when the lock is held but DB has no record yet.
 */
public class DuplicateTransactionException extends TxnBaseException {
	public DuplicateTransactionException(String message) {
		super(TxnErrorCode.DUPLICATE_TRANSACTION, message, HttpStatus.CONFLICT);
	}
}
