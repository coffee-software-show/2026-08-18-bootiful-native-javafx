package com.example.bootiful_javafx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/* Signs a *desktop* user in with the OAuth 2.0 authorization code grant + PKCE, driving the
 machine's real browser instead of an embedded one.

 The flow leaves the process in the middle - the user is off typing a password on somebody else's
 web page - so it is written as two halves: `start` opens the browser, and `finish` picks the code
 up when the browser comes back to [AuthorizationCodeRedirectController]. Nothing waits in between;
 the app hears about the result as a [UserSignedInEvent].
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

	/*
	 * Opens the system browser and returns; the answer arrives at the redirect endpoint.
	 */
	void start(String registrationId) throws IOException {
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

	private static OAuth2AuthorizationRequest authorizationRequest(ClientRegistration registration) {
		var builder = OAuth2AuthorizationRequest.authorizationCode()
			.clientId(registration.getClientId())
			.authorizationUri(registration.getProviderDetails().getAuthorizationUri())
			.redirectUri(registration.getRedirectUri())
			.scopes(registration.getScopes())
			.state(STATE.generateKey());
		// PKCE (RFC 7636): this puts a code_challenge on the authorization request and
		// stashes the
		// code_verifier in the request's attributes; the token request picks it up from
		// there.
		OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
		return builder.build();
	}

	private static OAuth2AuthorizationResponse authorizationResponse(OAuth2AuthorizationRequest request,
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
			.redirectUri(request.getRedirectUri())
			.state(state)
			.build();
	}

	private OAuth2AuthenticationToken exchange(ClientRegistration registration, OAuth2AuthorizationExchange exchange) {
		var tokens = this.accessTokens
			.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration, exchange));
		var user = this.users.loadUser(new OidcUserRequest(registration, tokens.getAccessToken(),
				idToken(registration, tokens), tokens.getAdditionalParameters()));
		var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(),
				registration.getRegistrationId());

		// hand the tokens to Spring Security. From here on nothing else in the app
		// touches them:
		// the OAuth2AuthorizedClientManager hands them out and quietly spends the refresh
		// token
		// when the access token is close to expiring.
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
		// signature (against the provider's JWK Set), issuer, audience, expiry - all of
		// it.
		var jwt = this.idTokens.createDecoder(registration).decode(value);
		return new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
	}

}

/*
 * The redirect target of the authorization code flow, served by the app's own embedded
 * Tomcat: the browser arrives here with the authorization code, and this is the page the
 * user is left looking at. `/login/oauth2/code/{registrationId}` is Spring Security's own
 * convention for the redirect endpoint, and it is what the client registration's
 * `redirect-uri` names - the port it names is `server.port`.
 */
@Controller
class AuthorizationCodeRedirectController {

	private final SystemBrowserOAuth2Login login;

	AuthorizationCodeRedirectController(SystemBrowserOAuth2Login login) {
		this.login = login;
	}

	@GetMapping("/login/oauth2/code/{registrationId}")
	String signedIn(@PathVariable String registrationId, @RequestParam Map<String, String> parameters, Model model) {
		model.addAttribute("name", this.login.finish(registrationId, parameters).name());
		return "signed-in";
	}

}

/*
 * The sign-in worked. This is what the window - and anything else that cares - waits for.
 */
class UserSignedInEvent extends ApplicationEvent {

	UserSignedInEvent(OAuth2AuthenticationToken authentication) {
		super(authentication);
	}

	OAuth2AuthenticationToken authentication() {
		return (OAuth2AuthenticationToken) getSource();
	}

	OidcUser user() {
		return (OidcUser) authentication().getPrincipal();
	}


	String name() {
		return user().getPreferredUsername() != null ? user().getPreferredUsername() : user().getName();
	}

}

/*
 * Plugs the interactive sign-in into the
 * [org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager], as the last
 * resort behind the refresh token grant: when there is no access token, or the one we
 * hold has run out and there is nothing to refresh it with, ask the user again.
 *
 * That last resort is not hypothetical here. Spring Authorization Server - correctly -
 * will not issue a refresh token to a *public* client, because there is nowhere safe on a
 * user's laptop to keep one. For this pairing the browser is the renewal mechanism.
 */
@Component
class SystemBrowserOAuth2AuthorizedClientProvider implements OAuth2AuthorizedClientProvider {

	private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	/*
	 * The one place where an errand in a browser has to look like a method call:
	 * OAuth2AuthorizedClientManager hands clients back by return value, so authorize()
	 * has to stand there until the user is done. A queue of one is enough - there is only
	 * ever one sign-in - and it means a browser that beats us back to this line still
	 * gets heard.
	 */
	private final BlockingQueue<UserSignedInEvent> signIns = new ArrayBlockingQueue<>(1);

	private final SystemBrowserOAuth2Login login;

	private final OAuth2AuthorizedClientService authorizedClients;

	private final Duration timeout;

	SystemBrowserOAuth2AuthorizedClientProvider(SystemBrowserOAuth2Login login,
			OAuth2AuthorizedClientService authorizedClients,
			@Value("${bootiful.oauth2.login-timeout:2m}") Duration timeout) {
		this.login = login;
		this.authorizedClients = authorizedClients;
		this.timeout = timeout;
	}

	@EventListener
	void on(UserSignedInEvent event) {
		this.signIns.offer(event);
	}

	@Override
	public OAuth2AuthorizedClient authorize(OAuth2AuthorizationContext context) {
		var registration = context.getClientRegistration();
		var current = context.getAuthorizedClient();
		if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(registration.getAuthorizationGrantType())
				|| (current != null && !expired(current.getAccessToken()))) {
			return null;
		}
		try {
			// whoever signed in before this call did not do it in answer to this call
			this.signIns.clear();
			this.login.start(registration.getRegistrationId());
			var event = this.signIns.poll(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (event == null) {
				throw new OAuth2AuthorizationException(new OAuth2Error("browser_login_timed_out"));
			}
			return this.authorizedClients.loadAuthorizedClient(registration.getRegistrationId(),
					event.authentication().getName());
		}
		catch (IOException ioe) {
			throw new OAuth2AuthorizationException(new OAuth2Error("browser_login_failed", ioe.getMessage(), null),
					ioe);
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new OAuth2AuthorizationException(new OAuth2Error("browser_login_interrupted"));
		}
	}

	private static boolean expired(OAuth2AccessToken token) {
		var expiresAt = token.getExpiresAt();
		return expiresAt != null && Instant.now().isAfter(expiresAt.minus(CLOCK_SKEW));
	}

}

@FunctionalInterface
interface AuthorizationBrowser {

	void open(String authorizationRequestUri) throws IOException;

}

@Component
class SystemBrowser implements AuthorizationBrowser {

	@Override
	public void open(String authorizationRequestUri) throws IOException {
		var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		var command = os.contains("mac") ? List.of("open", authorizationRequestUri)
				: os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", authorizationRequestUri)
						: List.of("xdg-open", authorizationRequestUri);
		new ProcessBuilder(command).start();
	}

}
