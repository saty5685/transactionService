package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

import com.deezyWallet.transaction.constants.TxnErrorCode;

/** Transaction is not in a state that allows reversal — HTTP 409 Conflict. */
public class TransactionNotReversibleException extends TxnBaseException {
	public TransactionNotReversibleException(String message) {
		super(TxnErrorCode.TRANSACTION_NOT_REVERSIBLE, message, HttpStatus.CONFLICT);
	}
}
