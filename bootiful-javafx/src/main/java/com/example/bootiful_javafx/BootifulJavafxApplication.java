package com.example.bootiful_javafx;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import static com.example.bootiful_javafx.Threads.onTheFxThread;
import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

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
@SpringBootApplication(exclude = {ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@ImportRuntimeHints(JavaFxRuntimeHints.class)
public class BootifulJavafxApplication {

    public static void main(String[] args) {
        // A desktop app has one user, not one user per thread. MODE_GLOBAL means the
        // principal
        // established by the sign-in is the principal every other thread - the JavaFX
        // application
        // thread, the RestClient interceptor - sees.
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_GLOBAL);

        /*
         * The Spring context is started here, on the main thread, on purpose:
         *
         * 1. Spring Boot's AOT processor calls this main() at build time and abandons the
         * run as soon as the context has been prepared, so we never reach the UI and no
         * window pops up during the build. 2. SpringApplication deduces the
         * "main application class" by walking the stack for a main() method, and the
         * AOT-generated initializer is looked up by that class name. A JavaFX launcher
         * thread has no main() on its stack, so booting the context from there would
         * silently fall back to the non-AOT path.
         */

        var ac = new SpringApplicationBuilder().sources(BootifulJavafxApplication.class).headless(false).run(args);
        /*
         * Platform.startup(), NOT Application.launch(). On macOS the AppKit event loop
         * has to run on the process' first thread. The java launcher arranges that by
         * moving main() onto a secondary thread and parking thread 0 in a CoreFoundation
         * run loop; a GraalVM native image has no such launcher, so main() *is* thread 0.
         * Application.launch() would hand toolkit startup to a "JavaFX-Launcher" thread,
         * which then waits forever for a main thread that is itself blocked inside
         * launch() - a silent deadlock with no window and no stack trace. Calling
         * Platform.startup() here lets Glass see it is already on the main thread and run
         * the event loop in place. This is also correct on the JVM.
         */
        Platform.startup(() -> ac.publishEvent(new StageReadyEvent(new Stage())));

    }

    /// Non-web apps get no `OAuth2AuthorizedClientManager` from Spring Boot, so here is
    /// one. It
    /// keeps the access token alive out of the refresh token where there is one, and
    /// falls back to
    /// asking the user again in the browser where there isn't.
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

class StageReadyEvent extends ApplicationEvent {

    public StageReadyEvent(Stage stage) {
        super(stage);
    }

    public Stage stage() {
        return (Stage) getSource();
    }

}

@Component
class StageInitializer {


    private final SystemBrowserOAuth2Login login;

    private final RestClient http;

    private final String registrationId;

    private final String api;

    private final String smokeTest;

    private final CountDownLatch signedIn = new CountDownLatch(1);

    private Label greeting;

    private Label status;

    private TextArea output;

    private Button signIn;

    private Button call;

    StageInitializer(SystemBrowserOAuth2Login login, RestClient http,
                     @Value("${bootiful.oauth2.registration-id}") String registrationId,
                     @Value("${bootiful.api-uri}") String api, @Value("${smoke.test:}") String smokeTest) {
        this.login = login;
        this.http = http;
        this.registrationId = registrationId;
        this.api = api;
        this.smokeTest = smokeTest;
    }

    @EventListener
    void on(StageReadyEvent event) {
        this.greeting = new Label("Hello, stranger.");
        this.greeting.getStyleClass().add("greeting");

        this.status = new Label("not signed in");
        this.status.getStyleClass().add("subtle");

        this.output = new TextArea();
        this.output.setEditable(false);
        this.output.setWrapText(true);
        this.output.setPrefRowCount(10);

        this.signIn = new Button("Sign in with your browser");
        this.signIn.setDefaultButton(true);

        this.call = new Button("Call the API");
        this.call.setDisable(true);

        this.signIn.setOnAction(e -> {
            this.status.setText("finish signing in over in your browser...");
            Threads.offTheFxThread(() -> {
                this.login.start(this.registrationId);
            }, failure -> {
                this.status.setText("sign-in failed");
                var message = failure.getMessage();
                this.output.setText(failure.getClass().getSimpleName() + (message != null ? ": " + message : ""));
            });
        });

        this.call.setOnAction(e -> {
            this.call.setDisable(true);
            this.status.setText("calling " + this.api + "...");
            Threads.offTheFxThread(() -> {
                // no token found
                var body = this.http //
                        .get() //
                        .uri(this.api) //
                        .attributes(clientRegistrationId(this.registrationId))//
                        .retrieve()//
                        .body(String.class);
                Threads.onTheFxThread(() -> {
                    this.status.setText("200 from " + this.api);
                    this.output.setText(body);
                    this.call.setDisable(false);
                });
            }, failure -> {
                this.status.setText("the call failed");
                this.call.setDisable(false);
                var message = StringUtils.hasText(failure.getMessage()) ? failure.getMessage() : "";
                IO.println(failure.getClass().getSimpleName() + message);
            });
        });

        var buttons = new HBox(12, this.signIn, this.call);
        buttons.setAlignment(Pos.CENTER);

        var layout = new VBox(12, this.greeting, this.status, buttons, this.output);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(32));

        var scene = new Scene(layout, 620, 480);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());

        var stage = event.stage();
        stage.setTitle("JavaFX + Spring Boot + GraalVM");
        stage.setScene(scene);
        stage.setOnHidden(e -> System.exit(0));
        stage.setOnShown(e -> IO.println("stage shown"));
        stage.show();
    }


    @EventListener
    void on(UserSignedInEvent event) {
        Threads.onTheFxThread(() -> {
            this.greeting.setText("Hello, " + event.name() + ".");
            this.status.setText("signed in via '%s'".formatted(event.authentication().getAuthorizedClientRegistrationId()));
            this.output.setText(claims(event.user().getClaims()));
            this.call.setDisable(false);
            this.signedIn.countDown();
        });
    }

    private static String claims(Map<String, Object> claims) {
        return claims.entrySet()
                .stream()
                .map(claim -> "%s: %s".formatted(claim.getKey(), claim.getValue()))
                .sorted()
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("");
    }

}
