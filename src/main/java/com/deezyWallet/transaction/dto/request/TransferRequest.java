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
 * P2P transfer request — sender sends money to another user.
 *
 * receiverIdentifier: accepts either a userId (UUID format) or a phone number.
 *   This allows senders to pay by phone number without knowing the receiver's UUID.
 *   TransactionService resolves the identifier to a walletId via User/Wallet Service.
 *
 * idempotencyKey: required on all mutation endpoints.
 *   UUID format enforced — prevents accidental use of non-unique keys.
 *   The client MUST generate a fresh UUID per intent (not per retry).
 *   On retry with the same key, the existing transaction is returned (200).
 *
 * amount validation: @DecimalMin("1.00") ensures no dust transactions.
 *   Max is enforced at service layer (TxnConstants.MAX_P2P_AMOUNT) where
 *   the exact limit is visible alongside the business context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {

	@NotBlank(message = "Receiver identifier is required")
	private String receiverIdentifier; // userId (UUID) or phone number (+91XXXXXXXXXX)

	@NotNull(message = "Amount is required")
	@DecimalMin(value = "1.00", message = "Minimum transfer amount is ₹1.00")
	@Digits(integer = 15, fraction = 4,
			message = "Amount must have at most 15 integer digits and 4 decimal places")
	private BigDecimal amount;

	@Size(max = 500, message = "Description must not exceed 500 characters")
	private String description;

	@NotBlank(message = "Idempotency key is required")
	@Pattern(
			regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
			message = "Idempotency key must be a valid UUID"
	)
	private String idempotencyKey;
}
