package com.example.bootiful_javafx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// Signs a *desktop* user in with the OAuth 2.0 authorization code grant + PKCE, driving the
/// machine's real browser instead of an embedded one. The pieces are all Spring Security's - this
/// class only supplies the two things a servlet app would otherwise get from the container: a place
/// for the authorization server to redirect back to ([LoopbackRedirectListener]) and a way to put
/// the authorization URL in front of the user ([SystemBrowser]).
///
/// A native browser is not a nicety. It is the only way the user can see the address bar and the TLS
/// padlock of the site they are typing their password into, it reuses any session they already have
/// there, and it keeps the credentials out of reach of this process. That is the whole point of
/// RFC 8252, "OAuth 2.0 for Native Apps".
@Service
class SystemBrowserOAuth2Login {

    private static final Logger log = LoggerFactory.getLogger(SystemBrowserOAuth2Login.class);

    private static final StringKeyGenerator STATE = new Base64StringKeyGenerator(Base64.getUrlEncoder());

    private final RestClientAuthorizationCodeTokenResponseClient accessTokens =
            new RestClientAuthorizationCodeTokenResponseClient();

    private final OidcIdTokenDecoderFactory idTokens = new OidcIdTokenDecoderFactory();

    private final OidcUserService users = new OidcUserService();

    private final ClientRegistrationRepository registrations;

    private final OAuth2AuthorizedClientService authorizedClients;

    private final AuthorizationBrowser browser;

    private final Duration timeout;

    SystemBrowserOAuth2Login(ClientRegistrationRepository registrations,
                             OAuth2AuthorizedClientService authorizedClients,
                             AuthorizationBrowser browser,
                             @Value("${bootiful.oauth2.login-timeout:2m}") Duration timeout) {
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
        this.browser = browser;
        this.timeout = timeout;
    }

    /// Blocks until the user finishes (or abandons) the flow in their browser, so call this off the
    /// JavaFX application thread.
    OAuth2AuthenticationToken login(String registrationId) throws IOException {
        var registration = this.registrations.findByRegistrationId(registrationId);
        Assert.notNull(registration, () -> "there is no client registration called [" + registrationId + "]");

        var authorizationRequest = authorizationRequest(registration);

        // bind the listener *before* handing the URL to the OS: the browser cannot beat us back.
        try (var redirect = new LoopbackRedirectListener(URI.create(registration.getRedirectUri()))) {
            log.info("opening the system browser to sign in with [{}]", registrationId);
            this.browser.open(authorizationRequest.getAuthorizationRequestUri());
            var response = authorizationResponse(authorizationRequest, redirect.await(this.timeout));
            return exchange(registration, new OAuth2AuthorizationExchange(authorizationRequest, response));
        }
    }

    private static OAuth2AuthorizationRequest authorizationRequest(ClientRegistration registration) {
        var builder = OAuth2AuthorizationRequest.authorizationCode()
                .clientId(registration.getClientId())
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(STATE.generateKey());
        // PKCE (RFC 7636): this puts a code_challenge on the authorization request and stashes the
        // code_verifier in the request's attributes; the token request picks it up from there.
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        return builder.build();
    }

    private static OAuth2AuthorizationResponse authorizationResponse(OAuth2AuthorizationRequest request,
                                                                     Map<String, String> parameters) {
        var error = parameters.get(OAuth2ParameterNames.ERROR);
        if (error != null) {
            throw new OAuth2AuthorizationException(new OAuth2Error(error,
                    parameters.get(OAuth2ParameterNames.ERROR_DESCRIPTION),
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
        var tokens = this.accessTokens.getTokenResponse(
                new OAuth2AuthorizationCodeGrantRequest(registration, exchange));
        var user = this.users.loadUser(new OidcUserRequest(registration, tokens.getAccessToken(),
                idToken(registration, tokens), tokens.getAdditionalParameters()));
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(),
                registration.getRegistrationId());

        // hand the tokens to Spring Security. From here on nothing else in the app touches them:
        // the OAuth2AuthorizedClientManager hands them out and quietly spends the refresh token
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
        // signature (against the provider's JWK Set), issuer, audience, expiry - all of it.
        var jwt = this.idTokens.createDecoder(registration).decode(value);
        return new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
    }

}

/// Plugs the interactive sign-in into the [org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager],
/// as the last resort behind the refresh token grant: when there is no access token, or the one we
/// hold has run out and there is nothing to refresh it with, ask the user again.
///
/// That last resort is not hypothetical here. Spring Authorization Server - correctly - will not
/// issue a refresh token to a *public* client, because there is nowhere safe on a user's laptop to
/// keep one. For this pairing the browser is the renewal mechanism.
class SystemBrowserOAuth2AuthorizedClientProvider implements OAuth2AuthorizedClientProvider {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final SystemBrowserOAuth2Login login;

    private final OAuth2AuthorizedClientService authorizedClients;

    SystemBrowserOAuth2AuthorizedClientProvider(SystemBrowserOAuth2Login login,
                                                OAuth2AuthorizedClientService authorizedClients) {
        this.login = login;
        this.authorizedClients = authorizedClients;
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
            var authentication = this.login.login(registration.getRegistrationId());
            return this.authorizedClients.loadAuthorizedClient(registration.getRegistrationId(),
                    authentication.getName());
        }
        catch (IOException ioe) {
            throw new OAuth2AuthorizationException(new OAuth2Error("browser_login_failed", ioe.getMessage(), null), ioe);
        }
    }

    private static boolean expired(OAuth2AccessToken token) {
        var expiresAt = token.getExpiresAt();
        return expiresAt != null && Instant.now().isAfter(expiresAt.minus(CLOCK_SKEW));
    }

}

/// The redirect target of the authorization code flow: a socket on the loopback interface that
/// lives exactly as long as one sign-in. It speaks just enough HTTP to read the query string the
/// browser was redirected to and to say something friendly back to the user.
class LoopbackRedirectListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoopbackRedirectListener.class);

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>Signed in</title></head>
            <body style="font-family: system-ui, sans-serif; text-align: center; padding-top: 15vh">
            <h1>You're signed in.</h1>
            <p>You can close this window and go back to the app.</p>
            </body>
            </html>
            """;

    private final ServerSocket socket;

    private final String path;

    LoopbackRedirectListener(URI redirectUri) throws IOException {
        Assert.isTrue(redirectUri.getPort() > 0,
                () -> "the redirect-uri [" + redirectUri + "] must name the port the app should listen on");
        this.path = redirectUri.getPath();
        this.socket = new ServerSocket();
        this.socket.setReuseAddress(true);
        this.socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), redirectUri.getPort()));
        log.debug("listening for the authorization response on {}", redirectUri);
    }

    /// The authorization response parameters, once the browser delivers them.
    Map<String, String> await(Duration timeout) throws IOException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                throw new SocketTimeoutException("gave up waiting for the authorization response");
            }
            this.socket.setSoTimeout((int) remaining);
            var parameters = accept();
            if (parameters != null) {
                return parameters;
            }
        }
    }

    private Map<String, String> accept() throws IOException {
        try (var connection = this.socket.accept()) {
            connection.setSoTimeout(5_000);
            var in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            var parameters = parameters(in.readLine());
            for (var header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
                // drain the request headers so the browser doesn't see the response as a reset
            }
            respond(connection, parameters != null);
            return parameters;
        }
        catch (SocketTimeoutException ste) {
            throw ste;
        }
        catch (IOException ioe) {
            // browsers routinely open speculative connections and abandon them. Keep listening.
            log.debug("ignoring a failed connection to the redirect listener", ioe);
            return null;
        }
    }

    /// `GET /login/oauth2/code/javafx?code=...&state=... HTTP/1.1` -> the query parameters, or
    /// `null` for anything that isn't the redirect we're waiting for.
    private Map<String, String> parameters(String requestLine) {
        if (requestLine == null) {
            return null;
        }
        var parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        var target = URI.create(parts[1]);
        if (!this.path.equals(target.getPath()) || target.getRawQuery() == null) {
            return null;
        }
        var parameters = new LinkedHashMap<String, String>();
        for (var pair : target.getRawQuery().split("&")) {
            var separator = pair.indexOf('=');
            var name = separator < 0 ? pair : pair.substring(0, separator);
            var value = separator < 0 ? "" : pair.substring(separator + 1);
            parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters.containsKey(OAuth2ParameterNames.CODE) || parameters.containsKey(OAuth2ParameterNames.ERROR)
                ? parameters : null;
    }

    private static void respond(Socket connection, boolean matched) throws IOException {
        var body = matched ? PAGE.getBytes(StandardCharsets.UTF_8) : new byte[0];
        var head = """
                HTTP/1.1 %s\r
                Content-Type: text/html; charset=utf-8\r
                Content-Length: %d\r
                Connection: close\r
                \r
                """.formatted(matched ? "200 OK" : "404 Not Found", body.length);
        var out = connection.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        this.socket.close();
    }

}

/// How the authorization request gets in front of the user. There is only one sane answer in
/// production - [SystemBrowser] - but naming the step keeps the flow testable without one.
@FunctionalInterface
interface AuthorizationBrowser {

    void open(String authorizationRequestUri) throws IOException;

}

/// Hands the URL to whatever the OS considers the user's browser. `java.awt.Desktop` would do this
/// too, but dragging AWT into a JavaFX process - and into a GraalVM native image - to shell out to
/// `open(1)` is a poor trade.
@Component
class SystemBrowser implements AuthorizationBrowser {

    private static final Logger log = LoggerFactory.getLogger(SystemBrowser.class);

    @Override
    public void open(String authorizationRequestUri) throws IOException {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var command = os.contains("mac") ? List.of("open", authorizationRequestUri)
                : os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", authorizationRequestUri)
                : List.of("xdg-open", authorizationRequestUri);
        log.debug("running {}", command);
        new ProcessBuilder(command).start();
    }

}
