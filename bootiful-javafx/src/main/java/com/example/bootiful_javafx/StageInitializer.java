package com.example.bootiful_javafx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

@Component
class StageInitializer {

	private final SystemBrowserOAuth2Login login;

	private final RestClient http;

	private final String registrationId;

	private final String api;

	private final CountDownLatch signedIn = new CountDownLatch(1);

	private Label greeting, status;

	private TextArea output;

	private Button signIn, call;

	StageInitializer(SystemBrowserOAuth2Login login, //
			RestClient http, //
			@Value("${bootiful.oauth2.registration-id}") String registrationId, //
			@Value("${bootiful.api-uri}") String api) {
		this.login = login;
		this.http = http;
		this.registrationId = registrationId;
		this.api = api;
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
			this.status
				.setText("signed in via '%s'".formatted(event.authentication().getAuthorizedClientRegistrationId()));
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
