package com.example.bootiful_javafx;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;

import java.util.Objects;

record UserSignedInEvent(OAuth2AuthenticationToken authentication) {

	OidcUser user() {
		return (OidcUser) authentication.getPrincipal();
	}

	String name() {
		return StringUtils.hasText(user().getPreferredUsername())
				? Objects.requireNonNull(user().getPreferredUsername()) : user().getName();
	}
}
