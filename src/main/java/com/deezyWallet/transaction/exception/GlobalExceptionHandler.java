package com.deezyWallet.transaction.exception;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.deezyWallet.transaction.constants.TxnErrorCode;
import com.deezyWallet.transaction.dto.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised exception-to-HTTP-response mapping for Transaction Service.
 *
 * HANDLER HIERARCHY:
 * ─────────────────────────────────────────────────────────────────────
 *  TransactionValidationException → 422  (business rule failure)
 *  FraudRejectedException         → 403  (generic message — no score revealed)
 *  FraudServiceException          → 503
 *  ExternalServiceException       → 503
 *  TransactionAccessDeniedException → 403
 *  TransactionNotFoundException   → 404
 *  TransactionNotReversibleException → 409
 *  DuplicateTransactionException  → 409
 *  TxnBaseException (catch-all)   → uses embedded httpStatus
 *  MethodArgumentNotValidException → 400 with field errors
 *  MissingRequestHeaderException  → 400 (missing Idempotency-Key header)
 *  DataIntegrityViolationException → 409 (race on idempotency_key UNIQUE)
 *  ObjectOptimisticLockingFailureException → 409 (version conflict)
 *  AccessDeniedException          → 403
 *  Exception (catch-all)          → 500, no internal detail
 * ─────────────────────────────────────────────────────────────────────
 *
 * SECURITY RULES:
 *   FraudRejectedException: always returns generic message regardless of
 *   the actual fraud score or reason. Never echoes exception.getMessage().
 *
 *   TransactionAccessDeniedException: always returns "Access denied" — not
 *   "Transaction not found for user X" which would confirm existence.
 *
 *   Exception catch-all: logs full stack trace internally, returns nothing
 *   useful externally — no Java class names, no stack trace, no DB details.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	// ── Fraud — always generic message ────────────────────────────────────────

	@ExceptionHandler(FraudRejectedException.class)
	public ResponseEntity<ErrorResponse> handleFraudRejected(FraudRejectedException ex) {
		// Never echo fraud model details — generic message only
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ErrorResponse.of(ex.getErrorCode(),
						"Transaction declined due to risk assessment"));
	}

	// ── All other domain exceptions — use embedded httpStatus ─────────────────

	@ExceptionHandler(TxnBaseException.class)
	public ResponseEntity<ErrorResponse> handleDomain(TxnBaseException ex) {
		// 4xx = WARN, 5xx = ERROR
		if (ex.getHttpStatus().is5xxServerError()) {
			log.error("Domain exception: errorCode={} status={} message={}",
					ex.getErrorCode(), ex.getHttpStatus(), ex.getMessage());
		} else {
			log.warn("Domain exception: errorCode={} status={} message={}",
					ex.getErrorCode(), ex.getHttpStatus(), ex.getMessage());
		}
		return ResponseEntity.status(ex.getHttpStatus())
				.body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
	}

	// ── Bean Validation (@Valid on request bodies) ────────────────────────────

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
				.getFieldErrors()
				.stream()
				.map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
				.toList();

		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ErrorResponse.ofValidation(TxnErrorCode.VALIDATION_FAILED, fieldErrors));
	}

	// ── Missing Idempotency-Key header ────────────────────────────────────────

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ErrorResponse.of(TxnErrorCode.VALIDATION_FAILED,
						"Required header missing: " + ex.getHeaderName()));
	}

	// ── DB unique constraint race (idempotency_key) ───────────────────────────

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation (likely duplicate idempotency key): {}",
				ex.getMostSpecificCause().getMessage());
		// Schema detail must never reach the client
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of(TxnErrorCode.DUPLICATE_TRANSACTION,
						"A transaction with this idempotency key already exists"));
	}

	// ── Optimistic lock conflict (version check failed) ───────────────────────

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLock(
			ObjectOptimisticLockingFailureException ex) {
		log.warn("Optimistic lock conflict on transaction: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of(TxnErrorCode.TRANSACTION_ALREADY_TERMINAL,
						"Transaction was concurrently modified. Please retry."));
	}

	// ── Spring Security access denied ─────────────────────────────────────────

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ErrorResponse.of(TxnErrorCode.ACCESS_DENIED, "Insufficient permissions"));
	}

	// ── Catch-all — never leak internal detail ────────────────────────────────

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unhandled exception: {}", ex.getMessage(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of(TxnErrorCode.INTERNAL_ERROR,
						"An unexpected error occurred. Please try again."));
	}
}
