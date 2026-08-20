package com.example.bootiful_javafx;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/*
 * There is a web server in here, but this is not a web application. Tomcat is up for one
 * reason - to catch the browser on its way back from the authorization server - and the
 * servlet security stack that Spring Boot would otherwise put in front of it is not just
 * unnecessary, it is actively wrong here, twice over:
 *
 * - `OAuth2ClientWebSecurityAutoConfiguration` would map `oauth2Login()` onto
 * `/login/oauth2/code/*` and swallow the redirect this app needs to read itself.
 * - `SecurityContextHolderFilter` clears the `SecurityContextHolder` at the end of every
 * request, and with `MODE_GLOBAL` there is only one context to clear: the signed-in
 * desktop user's. A single hit on this port would sign them out.
 */
@SpringBootApplication(exclude = { ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
		UserDetailsServiceAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class })
@ImportRuntimeHints(JavaFxRuntimeHints.class)
public class BootifulJavafxApplication {

	public static void main(String[] args) {
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_GLOBAL);
		var applicationContext = new SpringApplicationBuilder()//
			.sources(BootifulJavafxApplication.class)//
			.headless(false)//
			.run(args);
		Platform.startup(() -> applicationContext.publishEvent(new StageReadyEvent(new Stage())));
	}

	// Non-web apps get no `OAuth2AuthorizedClientManager` from Spring Boot
	@Bean
	OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService authorizedClients, SystemBrowserOAuth2AuthorizedClientProvider browser) {
		var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
		manager.setAuthorizedClientProvider(
				OAuth2AuthorizedClientProviderBuilder.builder().refreshToken().provider(browser).build());
		return manager;
	}

	@Bean
	RestClient restClient(RestClient.Builder builder, OAuth2AuthorizedClientManager authorizedClientManager) {
		return builder.requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager)).build();
	}

}
