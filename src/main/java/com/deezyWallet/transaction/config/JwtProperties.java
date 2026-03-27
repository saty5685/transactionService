package com.deezyWallet.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * JWT configuration for Transaction Service.
 *
 * Primary role: RESOURCE SERVER — validates tokens issued by User Service.
 * Secondary role: generates ROLE_INTERNAL_SERVICE tokens for outbound
 * service-to-service calls (via InternalJwtProvider).
 *
 * The secret MUST match User Service's jwt.secret exactly.
 * Both services read from the same Vault/Kubernetes Secret in production.
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
	/** Base64-encoded HMAC-SHA512 secret — same value as User Service */
	private String secret;

	/** Token issuer claim — must match across all services */
	private String issuer = "digital-wallet-platform";

	/** Service-to-service token lifetime in milliseconds. Default: 86_400_000 (24h) */
	private long serviceExpiryMs = 86_400_000L;
}
