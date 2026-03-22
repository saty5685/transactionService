package com.deezyWallet.transaction.security;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Authenticated principal extracted from JWT for Transaction Service requests.
 *
 * Carries kycStatus from JWT claims — used by TransactionService to:
 *   1. Check KYC_VERIFIED for high-value transfers without a User Service call
 *   2. Store fraud context (KYC level affects fraud model risk scoring)
 *
 * walletId is NOT in the JWT — the service looks it up from Wallet Service
 * internal endpoint when needed (once per request, cached in Redis).
 */
@Getter
@AllArgsConstructor
public class UserPrincipal implements UserDetails {

	private final String       userId;
	private final String       email;
	private final String       kycStatus;   // "VERIFIED", "UNVERIFIED", etc.
	private final List<String> roles;

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return roles.stream()
				.map(SimpleGrantedAuthority::new)
				.collect(Collectors.toList());
	}

	public boolean isKycVerified() {
		return "VERIFIED".equals(kycStatus);
	}

	@Override public String  getPassword()            { return null; }
	@Override public String  getUsername()            { return email; }
	@Override public boolean isAccountNonExpired()    { return true; }
	@Override public boolean isAccountNonLocked()     { return true; }
	@Override public boolean isCredentialsNonExpired(){ return true; }
	@Override public boolean isEnabled()              { return true; }
}