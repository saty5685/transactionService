package com.deezyWallet.transaction.dto.client;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for deserialising the User Service internal status response.
 *
 * Mirrors UserStatusResponse from User Service — kept as a separate class
 * to avoid compile-time coupling between services.
 *
 * Transaction Service calls GET /internal/v1/users/{userId}/status
 * and deserializes the response into this DTO.
 *
 * WHY not share a common library?
 *   Shared libraries create a coupling point — a change to UserStatusResponse
 *   requires rebuilding and redeploying Transaction Service even if it doesn't
 *   use the new field. Separate DTOs allow independent evolution.
 *   Jackson ignores unknown fields (@JsonIgnoreProperties on by default for
 *   deserialization) — new fields added to User Service are simply ignored here.
 */
@Data
@NoArgsConstructor
public class UserStatusDto {
	private String  userId;
	private String  status;      // "ACTIVE", "SUSPENDED", etc.
	private String  kycStatus;   // "VERIFIED", "UNVERIFIED", etc.
	private boolean canTransact;
	private boolean loginLocked;
}
