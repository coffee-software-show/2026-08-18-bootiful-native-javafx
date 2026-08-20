package com.example.bootiful_javafx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;

/*
 * Signs a desktop user in with the OAuth 2.0 authorization code grant + PKCE, driving the machine's real browser instead of an embedded one.
 */
@Service
class SystemBrowserOAuth2Login {

	private static final Logger log = LoggerFactory.getLogger(SystemBrowserOAuth2Login.class);

	private static final StringKeyGenerator STATE = new Base64StringKeyGenerator(Base64.getUrlEncoder());

	private final RestClientAuthorizationCodeTokenResponseClient accessTokens = new RestClientAuthorizationCodeTokenResponseClient();

	private final OidcIdTokenDecoderFactory idTokens = new OidcIdTokenDecoderFactory();

	private final OidcUserService users = new OidcUserService();

	private final ClientRegistrationRepository registrations;

	private final OAuth2AuthorizedClientService authorizedClients;

	private final AuthorizationBrowser browser;

	private final ApplicationEventPublisher events;

	/*
	 * The sign-in that is in flight, if there is one. A desktop app has one user, with
	 * one browser, in front of one window, so there is never more than one - and the
	 * `code_verifier` PKCE will need at the end of the flow rides along in its
	 * attributes.
	 */
	private volatile OAuth2AuthorizationRequest inFlight;

	SystemBrowserOAuth2Login(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService authorizedClients, AuthorizationBrowser browser,
			ApplicationEventPublisher events) {
		this.registrations = registrations;
		this.authorizedClients = authorizedClients;
		this.browser = browser;
		this.events = events;
	}

	// Opens the system browser and returns; the answer arrives at the redirect endpoint.
	void start(String registrationId) {
		var request = authorizationRequest(registration(registrationId));
		// remembered before the URL is handed to the OS: the browser can be back with the
		// code before open() has returned.
		this.inFlight = request;
		log.info("opening the system browser to sign in with [{}]", registrationId);
		this.browser.open(request.getAuthorizationRequestUri());
	}

	/*
	 * The other half, one browser round trip later: check that this is the answer to the
	 * question we asked, then trade the authorization code for tokens. Runs on the thread
	 * serving the redirect.
	 */
	UserSignedInEvent finish(String registrationId, Map<String, String> parameters) {
		var request = this.inFlight;
		this.inFlight = null;
		if (request == null) {
			// a reloaded page, a bookmarked redirect, somebody replaying a URL: whatever
			// this is, nobody in this app is waiting for it.
			throw new OAuth2AuthorizationException(new OAuth2Error("no_sign_in_in_flight"));
		}
		var response = authorizationResponse(request, parameters);
		var exchange = new OAuth2AuthorizationExchange(request, response);
		var event = new UserSignedInEvent(exchange(registration(registrationId), exchange));
		this.events.publishEvent(event);
		return event;
	}

	private ClientRegistration registration(String registrationId) {
		var registration = this.registrations.findByRegistrationId(registrationId);
		Assert.notNull(registration, () -> "there is no client registration called [" + registrationId + "]");
		return registration;
	}

	private OAuth2AuthorizationRequest authorizationRequest(ClientRegistration registration) {
		Assert.notNull(registration.getProviderDetails().getAuthorizationUri(), "the authorization URI is null");
		var builder = OAuth2AuthorizationRequest.authorizationCode()
			.clientId(registration.getClientId())
			.authorizationUri(registration.getProviderDetails().getAuthorizationUri())
			.redirectUri(registration.getRedirectUri())
			.scopes(registration.getScopes())
			.state(STATE.generateKey());
		/*
		 * PKCE (RFC 7636): this puts a code_challenge on the authorization request and
		 * stashes the code_verifier in the request's attributes; the token request picks
		 * it up from there.
		 */
		OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
		return builder.build();
	}

	private OAuth2AuthorizationResponse authorizationResponse(OAuth2AuthorizationRequest request,
			Map<String, String> parameters) {
		var error = parameters.get(OAuth2ParameterNames.ERROR);
		if (error != null) {
			throw new OAuth2AuthorizationException(
					new OAuth2Error(error, parameters.get(OAuth2ParameterNames.ERROR_DESCRIPTION),
							parameters.get(OAuth2ParameterNames.ERROR_URI)));
		}
		var state = parameters.get(OAuth2ParameterNames.STATE);
		if (!Objects.equals(request.getState(), state)) {
			throw new OAuth2AuthorizationException(new OAuth2Error("invalid_state_parameter"));
		}
		return OAuth2AuthorizationResponse.success(parameters.get(OAuth2ParameterNames.CODE))
			.redirectUri(Objects.requireNonNull(request.getRedirectUri()))
			.state(state)
			.build();
	}

	private OAuth2AuthenticationToken exchange(ClientRegistration registration, OAuth2AuthorizationExchange exchange) {
		var tokens = this.accessTokens
			.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration, exchange));
		var idToken = this.idToken(registration, tokens);
		var oidcUserRequest = new OidcUserRequest(registration, tokens.getAccessToken(), idToken,
				tokens.getAdditionalParameters());
		var user = this.users.loadUser(oidcUserRequest);
		var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(),
				registration.getRegistrationId());

		// give the token to Spring Security who'll handle refreshing it
		this.authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, user.getName(),
				tokens.getAccessToken(), tokens.getRefreshToken()), authentication);

		var context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.getContextHolderStrategy().setContext(context);
		return authentication;
	}

	private OidcIdToken idToken(ClientRegistration registration, OAuth2AccessTokenResponse tokens) {
		var value = (String) tokens.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN);
		Assert.hasText(value, "the token response carried no id_token; is 'openid' among the scopes?");
		var jwt = this.idTokens.createDecoder(registration).decode(value);
		return new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
	}

}
