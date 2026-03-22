package com.deezyWallet.transaction.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Merchant payment request — payer sends money to a merchant wallet.
 *
 * merchantWalletId vs merchantId:
 *   We accept the merchant's walletId directly (not userId) to avoid
 *   an extra lookup in the common case. Merchants know their walletId —
 *   it's embedded in their QR codes and payment links.
 *
 * merchantRef: the merchant's own order/reference ID.
 *   Stored on the transaction for merchant reconciliation.
 *   Helps merchants match wallet transactions to their orders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantPayRequest {

	@NotBlank(message = "Merchant wallet ID is required")
	private String merchantWalletId;

	@NotNull(message = "Amount is required")
	@DecimalMin(value = "1.00", message = "Minimum payment amount is ₹1.00")
	@Digits(integer = 15, fraction = 4)
	private BigDecimal amount;

	@Size(max = 500)
	private String description;

	/** Merchant's own order/reference ID for reconciliation */
	@Size(max = 100)
	private String merchantRef;

	@NotBlank(message = "Idempotency key is required")
	@Pattern(
			regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
			message = "Idempotency key must be a valid UUID"
	)
	private String idempotencyKey;
}
