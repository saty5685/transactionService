package com.deezyWallet.transaction.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.dto.response.AdminTransactionResponse;
import com.deezyWallet.transaction.dto.response.PagedResponse;
import com.deezyWallet.transaction.enums.TransactionStatusEnum;
import com.deezyWallet.transaction.security.UserPrincipal;
import com.deezyWallet.transaction.service.TransactionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AdminTransactionController — transaction management for platform admins.
 *
 * BASE PATH: /api/v1/admin/transactions
 * AUTH:      ROLE_ADMIN (enforced at SecurityConfig route level)
 *
 * Returns AdminTransactionResponse — includes internal fields (fraudScore,
 * failureReason, walletIds, version) not exposed to end users.
 *
 * The adminId used in audit trails comes from the JWT principal —
 * never from the request body. Server-authoritative audit trail.
 *
 * ENDPOINT INVENTORY:
 *   GET  /               — list all transactions (paginated, status filter)
 *   GET  /{txnId}        — get any transaction with internal fields
 *   POST /{txnId}/reverse — manual reversal of a COMPLETED transaction
 */
@RestController
@RequestMapping(TxnConstants.API_ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
public class AdminTransactionController {

	private final TransactionService transactionService;

	// ── GET /api/v1/admin/transactions ────────────────────────────────────────

	@GetMapping
	public ResponseEntity<PagedResponse<AdminTransactionResponse>> listTransactions(
			@RequestParam(required = false) TransactionStatusEnum status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable) {

		return ResponseEntity.ok(transactionService.listTransactions(status, pageable));
	}

	// ── GET /api/v1/admin/transactions/{txnId} ────────────────────────────────

	@GetMapping("/{txnId}")
	public ResponseEntity<AdminTransactionResponse> getTransaction(@PathVariable String txnId) {
		return ResponseEntity.ok(transactionService.getTransactionAdmin(txnId));
	}

	// ── POST /api/v1/admin/transactions/{txnId}/reverse ───────────────────────

	/**
	 * Initiates a manual reversal of a COMPLETED transaction.
	 *
	 * Only COMPLETED transactions can be reversed.
	 * The adminId is captured from the JWT principal — immutable audit trail.
	 * Triggers the REVERSE_DEBIT Saga step and publishes WALLET_UNBLOCK_CMD.
	 *
	 * Returns 202 Accepted — reversal is async (Saga step must complete).
	 */
	@PostMapping("/{txnId}/reverse")
	public ResponseEntity<AdminTransactionResponse> reverseTransaction(
			@PathVariable String txnId,
			@AuthenticationPrincipal UserPrincipal adminPrincipal) {

		log.info("Admin reversal requested: txnId={} adminId={}", txnId, adminPrincipal.getUserId());
		AdminTransactionResponse response =
				transactionService.reverseTransaction(txnId, adminPrincipal.getUserId());
		return ResponseEntity.accepted().body(response);
	}
}
