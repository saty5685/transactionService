package com.deezyWallet.transaction.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Intercepts outgoing RestTemplate requests and injects the internal service JWT.
 *
 * Adds "Authorization: Bearer <token>" to every outgoing HTTP request
 * made via the RestTemplate bean. The token is obtained from InternalJwtProvider
 * which handles caching and auto-refresh.
 *
 * SCOPE:
 *   This interceptor is registered on the RestTemplate used for internal
 *   service-to-service calls (PreflightService → Wallet Service, User Service).
 *   If Transaction Service later needs to call external APIs that should NOT
 *   receive internal JWTs, use a separate @Qualifier("externalRestTemplate")
 *   bean without this interceptor.
 *
 * WHY an interceptor instead of manually setting headers in PreflightService?
 *   - DRY: every fetch method (fetchUserStatus, fetchWalletBalance, etc.)
 *     would need the same 3-line boilerplate for HttpHeaders + HttpEntity
 *   - Separation of concerns: PreflightService focuses on business logic,
 *     not auth plumbing
 *   - Testability: can mock InternalJwtProvider in tests without touching
 *     every HTTP call site
 */
@Component
@RequiredArgsConstructor
public class InternalAuthInterceptor implements ClientHttpRequestInterceptor {

	private final InternalJwtProvider jwtProvider;

	@Override
	public ClientHttpResponse intercept(HttpRequest request,
										byte[] body,
										ClientHttpRequestExecution execution) throws IOException {
		request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.getToken());
		return execution.execute(request, body);
	}
}

