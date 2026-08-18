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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
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
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

@SpringBootApplication
@ImportRuntimeHints(JavaFxRuntimeHints.class)
public class BootifulJavafxApplication {

    public static void main(String[] args) {
        // A desktop app has one user, not one user per thread. MODE_GLOBAL means the principal
        // established by the sign-in is the principal every other thread - the JavaFX application
        // thread, the RestClient interceptor - sees.
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_GLOBAL);

        // The Spring context is started here, on the main thread, on purpose:
        //
        //  1. Spring Boot's AOT processor calls this main() at build time and abandons the run
        //     as soon as the context has been prepared, so we never reach the UI and no window
        //     pops up during the build.
        //  2. SpringApplication deduces the "main application class" by walking the stack for a
        //     main() method, and the AOT-generated initializer is looked up by that class name.
        //     A JavaFX launcher thread has no main() on its stack, so booting the context from
        //     there would silently fall back to the non-AOT path.

        var ac = new SpringApplicationBuilder()
                .sources(BootifulJavafxApplication.class)
                .headless(false)
                .run(args);
        // Platform.startup(), NOT Application.launch(). On macOS the AppKit event loop has to run
        // on the process' first thread. The java launcher arranges that by moving main() onto a
        // secondary thread and parking thread 0 in a CoreFoundation run loop; a GraalVM native
        // image has no such launcher, so main() *is* thread 0. Application.launch() would hand
        // toolkit startup to a "JavaFX-Launcher" thread, which then waits forever for a main
        // thread that is itself blocked inside launch() - a silent deadlock with no window and
        // no stack trace. Calling Platform.startup() here lets Glass see it is already on the
        // main thread and run the event loop in place. This is also correct on the JVM.
        Platform.startup(() -> ac.publishEvent(new StageReadyEvent(new Stage())));

    }

    /// Non-web apps get no `OAuth2AuthorizedClientManager` from Spring Boot, so here is one. It
    /// keeps the access token alive out of the refresh token where there is one, and falls back to
    /// asking the user again in the browser where there isn't.
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientService authorizedClients,
                                                          SystemBrowserOAuth2Login login) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken()
                .provider(new SystemBrowserOAuth2AuthorizedClientProvider(login, authorizedClients))
                .build());
        return manager;
    }

    /// A `RestClient` that puts `Authorization: Bearer ...` on every request for which a
    /// registration id was named - see [#clientRegistrationId(String)] at the call site.
    @Bean
    RestClient restClient(RestClient.Builder builder, OAuth2AuthorizedClientManager authorizedClientManager) {
        return builder
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager))
                .build();
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

    private static final Logger log = LoggerFactory.getLogger(StageInitializer.class);

    private final SystemBrowserOAuth2Login login;

    private final RestClient http;

    private final String registrationId;

    private final String api;

    private final String smokeTest;

    private final CountDownLatch signedIn = new CountDownLatch(1);

    StageInitializer(SystemBrowserOAuth2Login login, RestClient http,
                     @Value("${bootiful.oauth2.registration-id}") String registrationId,
                     @Value("${bootiful.api-uri}") String api,
                     @Value("${smoke.test:}") String smokeTest) {
        this.login = login;
        this.http = http;
        this.registrationId = registrationId;
        this.api = api;
        this.smokeTest = smokeTest;
    }

    @EventListener
    void on(StageReadyEvent event) {
        var greeting = new Label("Hello, stranger.");
        greeting.getStyleClass().add("greeting");

        var status = new Label("not signed in");
        status.getStyleClass().add("subtle");

        var output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        output.setPrefRowCount(10);

        var signIn = new Button("Sign in with your browser");
        signIn.setDefaultButton(true);

        var call = new Button("Call the API");
        call.setDisable(true);

        signIn.setOnAction(e -> {
            signIn.setDisable(true);
            status.setText("finish signing in over in your browser...");
            offTheFxThread(() -> {
                var authentication = this.login.login(this.registrationId);
                var user = (OidcUser) authentication.getPrincipal();
                onTheFxThread(() -> {
                    greeting.setText("Hello, " + name(user) + ".");
                    status.setText("signed in via '%s'".formatted(authentication.getAuthorizedClientRegistrationId()));
                    output.setText(claims(user.getClaims()));
                    call.setDisable(false);
                    this.signedIn.countDown();
                });
            }, failure -> {
                signIn.setDisable(false);
                status.setText("sign-in failed");
                output.setText(describe(failure));
            });
        });

        call.setOnAction(e -> {
            call.setDisable(true);
            status.setText("calling " + this.api + "...");
            offTheFxThread(() -> {
                // No token handling here: the interceptor asks the OAuth2AuthorizedClientManager
                // for the authorized client, refreshing it first if the access token has aged out.
                var body = this.http.get()
                        .uri(this.api)
                        .attributes(clientRegistrationId(this.registrationId))
                        .retrieve()
                        .body(String.class);
                onTheFxThread(() -> {
                    status.setText("200 from " + this.api);
                    output.setText(body);
                    call.setDisable(false);
                });
            }, failure -> {
                status.setText("the call failed");
                output.setText(describe(failure));
                call.setDisable(false);
            });
        });

        var buttons = new HBox(12, signIn, call);
        buttons.setAlignment(Pos.CENTER);

        var layout = new VBox(12, greeting, status, buttons, output);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(32));

        var scene = new Scene(layout, 620, 480);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());

        var stage = event.stage();
        stage.setTitle("JavaFX + Spring Boot + GraalVM");
        stage.setScene(scene);
        // Closing the window ends the process; Spring Boot's shutdown hook closes the context.
        // Exiting here also stops short of Glass' post-run-loop teardown, which in a native image
        // reports a spurious NullPointerException on the way out. Harmless - the exit code is 0
        // either way - but noisy.
        stage.setOnHidden(e -> System.exit(0));
        stage.setOnShown(e -> log.info("stage shown"));
        stage.show();

        if (!this.smokeTest.isBlank()) {
            smokeTest(signIn, call, output);
        }
    }

    /// `-Dsmoke.test=true` drives the window once and quits; `-Dsmoke.test=login` signs in first.
    /// This is what makes `mvn -Pagent spring-boot:run` useful: the native-image agent only records
    /// what the run actually touches, and only writes its file on a clean JVM shutdown.
    ///
    /// Toggling `disable` is not busywork. A disabled control renders at reduced opacity, which is
    /// the one thing in this window that drags in JavaFX's effects pipeline - and the effects
    /// pipeline finds its renderer by name, so nothing but a real run will discover it.
    private void smokeTest(Button signIn, Button call, TextArea output) {
        Thread.ofVirtual().name("smoke-test").start(() -> {
            try {
                pause(Duration.ofSeconds(2));
                if ("login".equalsIgnoreCase(this.smokeTest)) {
                    onTheFxThread(signIn::fire);
                    if (this.signedIn.await(90, TimeUnit.SECONDS)) {
                        onTheFxThread(call::fire);
                        pause(Duration.ofSeconds(5));
                    }
                    else {
                        log.warn("smoke test: nobody signed in");
                    }
                }
                for (var disabled : new boolean[]{false, true, false}) {
                    onTheFxThread(() -> {
                        call.setDisable(disabled);
                        signIn.setDisable(!disabled);
                        output.setText("smoke test: call %s".formatted(disabled ? "disabled" : "enabled"));
                    });
                    pause(Duration.ofSeconds(1));
                }
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            finally {
                log.info("smoke test done");
                System.exit(0);
            }
        });
    }

    private static void pause(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }

    /// Sign-in and HTTP calls block; the JavaFX application thread must not. Failures come back on
    /// the FX thread too, so the handler can touch the scene graph.
    private static void offTheFxThread(Work work, Consumer<Throwable> onFailure) {
        Thread.ofVirtual().name("bootiful-javafx-worker").start(() -> {
            try {
                work.run();
            }
            catch (Throwable throwable) {
                log.warn("background work failed", throwable);
                onTheFxThread(() -> onFailure.accept(throwable));
            }
        });
    }

    private static void onTheFxThread(Runnable runnable) {
        Platform.runLater(runnable);
    }

    private static String name(OidcUser user) {
        return user.getPreferredUsername() != null ? user.getPreferredUsername() : user.getName();
    }

    private static String claims(Map<String, Object> claims) {
        return claims.entrySet()
                .stream()
                .map(claim -> "%s: %s".formatted(claim.getKey(), claim.getValue()))
                .sorted()
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("");
    }

    private static String describe(Throwable throwable) {
        var message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    @FunctionalInterface
    interface Work {

        void run() throws Exception;

    }

}
