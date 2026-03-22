package com.deezyWallet.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * JWT configuration — Transaction Service is a RESOURCE SERVER only.
 *
 * It validates tokens issued by User Service but NEVER issues tokens itself.
 * Only the secret is needed — no expiry settings (those are the issuer's concern).
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
	private String issuer = "digital-wallet-platform";
}
