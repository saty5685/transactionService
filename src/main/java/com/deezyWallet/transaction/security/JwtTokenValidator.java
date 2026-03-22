package com.deezyWallet.transaction.security;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.deezyWallet.transaction.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

/**
 * JWT validator for Transaction Service — validates only, never issues.
 *
 * Identical pattern to Wallet Service's JwtTokenValidator.
 * Both are resource servers sharing the same signing key.
 *
 * Transaction Service needs to read additional claims beyond userId and roles:
 *   kycStatus — to enforce KYC_VERIFIED requirement on high-value transactions
 *               without a synchronous call to User Service on every request.
 *               (Low-value txns skip this check; high-value txns re-verify via HTTP)
 */
@Component
@RequiredArgsConstructor
public class JwtTokenValidator {

	private final JwtProperties jwtProperties;

	private SecretKey signingKey() {
		return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
	}

	/**
	 * Validates and extracts all claims from a signed JWT.
	 * @throws JwtException on invalid signature, expired token, or malformed input.
	 */
	public Claims validateAndExtract(String token) {
		return Jwts.parser()
				.verifyWith(signingKey())
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}
}
