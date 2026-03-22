package com.deezyWallet.transaction.config;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.deezyWallet.transaction.constants.TxnConstants;
import com.deezyWallet.transaction.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Security configuration — Transaction Service.
 *
 * Route authorization matrix:
 * ─────────────────────────────────────────────────────────────────────
 *  /actuator/health              → Public (K8s liveness probe)
 *  /api/v1/admin/transactions/** → ROLE_ADMIN
 *  /internal/v1/transactions/**  → ROLE_INTERNAL_SERVICE
 *  /api/v1/transactions/**       → ROLE_USER or ROLE_MERCHANT
 * ─────────────────────────────────────────────────────────────────────
 *
 * No PasswordEncoder bean — Transaction Service never handles passwords.
 * No DaoAuthenticationProvider — authentication is JWT-only.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthFilter jwtAuthFilter;
	private final ObjectMapper  objectMapper;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) -> {
							res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
							res.setContentType(MediaType.APPLICATION_JSON_VALUE);
							objectMapper.writeValue(res.getOutputStream(), Map.of(
									"errorCode", "AUTH_FAILED",
									"message",   "Authentication required",
									"timestamp", LocalDateTime.now().toString()
							));
						})
						.accessDeniedHandler((req, res, ex) -> {
							res.setStatus(HttpServletResponse.SC_FORBIDDEN);
							res.setContentType(MediaType.APPLICATION_JSON_VALUE);
							objectMapper.writeValue(res.getOutputStream(), Map.of(
									"errorCode", "ACCESS_DENIED",
									"message",   "Insufficient permissions",
									"timestamp", LocalDateTime.now().toString()
							));
						})
				)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(TxnConstants.ACTUATOR_HEALTH).permitAll()
						.requestMatchers(TxnConstants.API_ADMIN_BASE + "/**").hasRole("ADMIN")
						.requestMatchers(TxnConstants.API_INTERNAL_BASE + "/**").hasRole("INTERNAL_SERVICE")
						.anyRequest().authenticated()
				)
				.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
