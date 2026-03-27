package com.deezyWallet.transaction.exception;

import org.springframework.http.HttpStatus;

import com.deezyWallet.transaction.constants.TxnErrorCode;

/** Transaction ID does not exist in the DB — HTTP 404. */
public class TransactionNotFoundException extends TxnBaseException {
	public TransactionNotFoundException(String message) {
		super(TxnErrorCode.TRANSACTION_NOT_FOUND, message, HttpStatus.NOT_FOUND);
	}
}
