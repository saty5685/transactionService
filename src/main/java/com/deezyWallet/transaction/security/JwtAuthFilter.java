package com.deezyWallet.transaction.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.deezyWallet.transaction.constants.TxnConstants;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT authentication filter — extracts and validates Bearer tokens.
 *
 * Identical pattern to Wallet Service JwtAuthFilter.
 * Transaction Service has no token blacklist check — blacklisting is
 * managed by User Service. A logged-out user's access token can still
 * reach Transaction Service within the 15-minute window.
 *
 * Production hardening option: Transaction Service queries User Service's
 * token blacklist Redis (shared Redis cluster) on high-value transactions.
 * For now, the short access token TTL (15 min) is the mitigation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtTokenValidator tokenValidator;

	@Override
	protected void doFilterInternal(HttpServletRequest  request,
			HttpServletResponse response,
			FilterChain         chain)
			throws ServletException, IOException {

		String token = extractBearerToken(request);
		if (StringUtils.hasText(token)) {
			try {
				Claims claims = tokenValidator.validateAndExtract(token);
				setAuthentication(claims, request);
			} catch (JwtException e) {
				log.debug("JWT validation failed: {}", e.getMessage());
				SecurityContextHolder.clearContext();
			}
		}
		chain.doFilter(request, response);
	}

	@SuppressWarnings("unchecked")
	private void setAuthentication(Claims claims, HttpServletRequest request) {
		List<String> roles = claims.get(TxnConstants.JWT_CLAIM_ROLES, List.class);

		UserPrincipal principal = new UserPrincipal(
				claims.getSubject(),
				claims.get(TxnConstants.JWT_CLAIM_EMAIL,      String.class),
				claims.get(TxnConstants.JWT_CLAIM_KYC_STATUS, String.class),
				roles != null ? roles : List.of()
		);

		UsernamePasswordAuthenticationToken auth =
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(auth);
	}

	private String extractBearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
			return header.substring(7);
		}
		return null;
	}
}