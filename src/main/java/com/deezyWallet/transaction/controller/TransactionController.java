package com.deezyWallet.transaction.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.dto.request.MerchantPayRequest;
import com.deezyWallet.transaction.dto.request.TransferRequest;
import com.deezyWallet.transaction.dto.response.PagedResponse;
import com.deezyWallet.transaction.dto.response.TransactionResponse;
import com.deezyWallet.transaction.enums.TransactionStatusEnum;
import com.deezyWallet.transaction.security.UserPrincipal;
import com.deezyWallet.transaction.service.TransactionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * TransactionController — user-facing transaction endpoints.
 *
 * BASE PATH: /api/v1/transactions
 * AUTH:      Any authenticated user (ROLE_USER or ROLE_MERCHANT)
 *
 * ALL MUTATION ENDPOINTS require an Idempotency-Key header.
 *   Required header is declared with @RequestHeader — Spring returns 400
 *   automatically if it's missing (caught by MissingRequestHeaderException handler).
 *   The header value is ignored here — it's embedded in the request body.
 *   Having it as a header too follows the industry convention (Stripe, PayPal)
 *   and signals to API gateways/proxies that the endpoint is idempotent.
 *
 * WHY the same key in both header and body?
 *   The header is for infrastructure (gateways, proxies can read headers without
 *   parsing bodies). The body is for the application (type-safe, validated as UUID).
 *   They should always be the same value — the controller doesn't compare them,
 *   the body value is the authoritative one for our idempotency logic.
 *
 * RESPONSE CODES:
 *   POST /transfer    → 202 Accepted  (Saga is async — txn is initiated, not yet complete)
 *   POST /pay         → 202 Accepted
 *   GET  /me          → 200 OK
 *   GET  /me/{txnId}  → 200 OK
 *
 * WHY 202 Accepted instead of 201 Created?
 *   201 Created implies the resource is fully created.
 *   The transaction is INITIATED but not yet COMPLETED — the Saga is running.
 *   202 Accepted correctly signals "we've received and started processing your request,
 *   check back for the final status". Clients poll GET /me/{txnId} for completion.
 */
@RestController
@RequestMapping(TxnConstants.API_TXN_BASE)
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

	private final TransactionService transactionService;

	// ── POST /api/v1/transactions/transfer ────────────────────────────────────

	/**
	 * Initiates a P2P transfer.
	 *
	 * Returns 202 immediately with INITIATED/PENDING_DEBIT status.
	 * The Saga runs asynchronously — client polls GET /me/{txnId} for completion.
	 * On duplicate idempotencyKey, returns the existing transaction (200 OK, not 202).
	 */
	@PostMapping("/transfer")
	public ResponseEntity<TransactionResponse> transfer(
			@Valid @RequestBody TransferRequest req,
			@RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
			@AuthenticationPrincipal UserPrincipal principal) {

		TransactionResponse response = transactionService.initiateTransfer(req, principal);

		// If this was an idempotent repeat (existing txn returned), use 200
		// If this is a new txn just initiated, use 202
		HttpStatus status = response.getStatus() == com.deezyWallet.transaction.enums.TransactionStatusEnum.INITIATED
				|| response.getStatus() == TransactionStatusEnum.PENDING_DEBIT
				? HttpStatus.ACCEPTED
				: HttpStatus.OK;

		return ResponseEntity.status(status).body(response);
	}

	// ── POST /api/v1/transactions/pay ─────────────────────────────────────────

	/**
	 * Initiates a merchant payment.
	 * Same Saga flow as P2P transfer — same response semantics.
	 */
	@PostMapping("/pay")
	public ResponseEntity<TransactionResponse> merchantPay(
			@Valid @RequestBody MerchantPayRequest req,
			@RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
			@AuthenticationPrincipal UserPrincipal principal) {

		TransactionResponse response = transactionService.initiateMerchantPayment(req, principal);

		HttpStatus status = response.getStatus() == TransactionStatusEnum.INITIATED
				|| response.getStatus() == TransactionStatusEnum.PENDING_DEBIT
				? HttpStatus.ACCEPTED
				: HttpStatus.OK;

		return ResponseEntity.status(status).body(response);
	}

	// ── GET /api/v1/transactions/me ───────────────────────────────────────────

	/**
	 * Returns the authenticated user's transaction history.
	 *
	 * Paginated — default 20 per page, sorted by createdAt descending.
	 * Optional status filter: ?status=COMPLETED
	 */
	@GetMapping("/me")
	public ResponseEntity<PagedResponse<TransactionResponse>> getMyTransactions(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) TransactionStatusEnum status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable) {

		return ResponseEntity.ok(
				transactionService.getUserTransactions(principal.getUserId(), status, pageable));
	}

	// ── GET /api/v1/transactions/me/{txnId} ───────────────────────────────────

	/**
	 * Returns a specific transaction for the authenticated user.
	 *
	 * Returns 403 if the user doesn't own the transaction (IDOR protection).
	 * The error message is deliberately vague — cannot distinguish "not found"
	 * from "not yours" to prevent transaction ID enumeration.
	 */
	@GetMapping("/me/{txnId}")
	public ResponseEntity<TransactionResponse> getMyTransaction(
			@PathVariable String txnId,
			@AuthenticationPrincipal UserPrincipal principal) {

		return ResponseEntity.ok(
				transactionService.getTransaction(txnId, principal.getUserId()));
	}
}
