package com.example.javafxnative;

import java.util.concurrent.TimeUnit;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** A regular Spring bean that builds the UI once JavaFX hands us the primary stage. */
@Component
public class StageInitializer implements ApplicationListener<StageReadyEvent> {

	private static final Logger log = LoggerFactory.getLogger(StageInitializer.class);

	private final GreetingService greetings;

	private int clicks;

	StageInitializer(GreetingService greetings) {
		this.greetings = greetings;
	}

	@Override
	public void onApplicationEvent(StageReadyEvent event) {
		var greeting = new Label(this.greetings.greet());
		greeting.getStyleClass().add("greeting");

		var runtime = new Label("running on the " + this.greetings.runtime());
		runtime.getStyleClass().add("subtle");

		var startup = new Label("started in %d ms".formatted(
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - JavaFxNativeApplication.STARTED_AT)));
		startup.getStyleClass().add("subtle");

		var counter = new Label("no clicks yet");
		var button = new Button("Click me");
		button.setDefaultButton(true);
		button.setOnAction(e -> counter.setText("clicked %d time(s)".formatted(++this.clicks)));

		var layout = new VBox(12, greeting, runtime, startup, button, counter);
		layout.setAlignment(Pos.CENTER);
		layout.setPadding(new Insets(32));

		var scene = new Scene(layout, 420, 280);
		scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

		Stage stage = event.stage();
		stage.setTitle("JavaFX + Spring Boot + GraalVM");
		stage.setScene(scene);
		// Closing the window ends the process; Spring Boot's shutdown hook closes the context.
		// Exiting here also stops short of Glass' post-run-loop teardown, which in a native image
		// reports a spurious NullPointerException on the way out. Harmless - the exit code is 0
		// either way - but noisy.
		stage.setOnHidden(e -> System.exit(0));
		stage.setOnShown(e -> log.info("stage shown"));
		stage.show();

		// -Dsmoke.test=true closes the window by itself, which is how the native binary gets
		// verified without a human in front of it. See the README.
		if (Boolean.getBoolean("smoke.test")) {
			var pause = new PauseTransition(Duration.seconds(3));
			pause.setOnFinished(e -> {
				log.info("smoke test: closing the window");
				stage.close();
			});
			pause.play();
		}
	}

}
