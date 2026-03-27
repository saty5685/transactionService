package com.deezyWallet.transaction.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.dto.response.TransactionResponse;
import com.deezyWallet.transaction.service.TransactionService;

import lombok.RequiredArgsConstructor;

/**
 * InternalTransactionController — machine-to-machine endpoints.
 *
 * BASE PATH: /internal/v1/transactions
 * AUTH:      ROLE_INTERNAL_SERVICE (enforced at SecurityConfig route level)
 *
 * WHO CALLS THIS:
 *   - Ledger Service   → GET /{txnId}/status to enrich ledger entries
 *   - Fraud Service    → GET /{txnId}/status for ML feedback (was it really fraud?)
 *   - Notification Svc → GET /{txnId}/status before sending delayed notifications
 *
 * NETWORK ISOLATION:
 *   /internal/** is blocked at the API Gateway. Only reachable within the
 *   Kubernetes cluster via service DNS.
 *
 * Returns TransactionResponse (not AdminTransactionResponse) — no fraudScore
 * or internal fields even for internal callers. Each service gets only what
 * it needs. Ledger Service needs amounts and wallet IDs — not the fraud score.
 *
 * ENDPOINT INVENTORY:
 *   GET /{txnId}/status  — transaction status by ID
 */
@RestController
@RequestMapping(TxnConstants.API_INTERNAL_BASE)
@RequiredArgsConstructor
public class InternalTransactionController {

	private final TransactionService transactionService;

	/**
	 * Returns the current status of a transaction.
	 *
	 * No ownership check — internal callers are trusted (verified by JWT role).
	 * Returns TransactionResponse — consumer reads status, amount, parties.
	 */
	@GetMapping("/{txnId}/status")
	public ResponseEntity<TransactionResponse> getTransactionStatus(
			@PathVariable String txnId) {
		// Internal callers don't have a userId — pass null, service skips ownership check
		// We reuse getTransaction but with a special INTERNAL path that bypasses ownership
		return ResponseEntity.ok(transactionService.getTransactionInternal(txnId));
	}
}
