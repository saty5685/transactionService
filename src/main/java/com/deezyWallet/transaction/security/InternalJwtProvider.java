package com.deezyWallet.transaction.security;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.deezyWallet.transaction.config.JwtProperties;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates and caches a service-to-service JWT for inter-service calls.
 *
 * This component allows Transaction Service to call internal endpoints
 * on other services (Wallet Service, User Service) that require
 * ROLE_INTERNAL_SERVICE.
 *
 * TOKEN LIFECYCLE:
 *   - Generated on application startup via @PostConstruct
 *   - Cached in-memory (volatile field for thread visibility)
 *   - Auto-regenerated when remaining TTL < REFRESH_BEFORE_EXPIRY_MS
 *   - getToken() is called on every outbound HTTP request (via InternalAuthInterceptor)
 *
 * WHY generate locally instead of calling auth-service?
 *   All services share the same HMAC-SHA512 secret. Any service with the
 *   secret can sign a valid JWT. Calling auth-service would:
 *   - Add a circular dependency (Transaction → Auth for token, Auth → Transaction for events)
 *   - Add a single point of failure (auth-service down = all inter-service calls fail)
 *   - Add network latency on every token refresh
 *   Local generation eliminates all three problems.
 *
 * SECURITY:
 *   - Token contains ONLY ROLE_INTERNAL_SERVICE — cannot access user endpoints
 *   - sub = "transaction-service" — identity is auditable in server logs
 *   - 24-hour expiry — limits blast radius if a token leaks from memory dump
 *   - Never returned to external clients or logged
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalJwtProvider {

	private static final String SERVICE_NAME = "transaction-service";
	private static final long   TOKEN_EXPIRY_MS = 86_400_000L;          // 24 hours
	private static final long   REFRESH_BEFORE_EXPIRY_MS = 3_600_000L;  // refresh when < 1h remaining
	private static final String ROLE_INTERNAL_SERVICE = "ROLE_INTERNAL_SERVICE";

	private final JwtProperties jwtProperties;
	private final ReentrantLock tokenLock = new ReentrantLock();

	private volatile String cachedToken;
	private volatile long   tokenExpiresAtMs;

	@PostConstruct
	void init() {
		refreshToken();
		log.info("Internal service JWT generated for sub={}", SERVICE_NAME);
	}

	/**
	 * Returns a valid internal service JWT.
	 * Auto-refreshes if the cached token is close to expiry.
	 * Thread-safe — concurrent callers share the same cached token.
	 */
	public String getToken() {
		if (shouldRefresh()) {
			tokenLock.lock();
			try {
				// Double-check after acquiring lock
				if (shouldRefresh()) {
					refreshToken();
				}
			} finally {
				tokenLock.unlock();
			}
		}
		return cachedToken;
	}

	private boolean shouldRefresh() {
		return cachedToken == null
				|| System.currentTimeMillis() > (tokenExpiresAtMs - REFRESH_BEFORE_EXPIRY_MS);
	}

	private void refreshToken() {
		long now = System.currentTimeMillis();
		long expiresAt = now + TOKEN_EXPIRY_MS;

		SecretKey key = Keys.hmacShaKeyFor(
				Decoders.BASE64.decode(jwtProperties.getSecret()));

		this.cachedToken = Jwts.builder()
				.subject(SERVICE_NAME)
				.issuer(jwtProperties.getIssuer())
				.claim("roles", List.of(ROLE_INTERNAL_SERVICE))
				.id(UUID.randomUUID().toString())
				.issuedAt(new Date(now))
				.expiration(new Date(expiresAt))
				.signWith(key, SignatureAlgorithm.HS512)
				.compact();

		this.tokenExpiresAtMs = expiresAt;
		log.debug("Internal JWT refreshed, expires at {}", new Date(expiresAt));
	}
}

