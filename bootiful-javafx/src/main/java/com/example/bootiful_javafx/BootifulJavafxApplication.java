package com.example.bootiful_javafx;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class BootifulJavafxApplication {

    public static void main(String[] args) {
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

}

class StageReadyEvent extends ApplicationEvent {

    public StageReadyEvent(Stage stage) {
        super(stage);
    }

    public Stage stage() {
        return (Stage) getSource();
    }

}

@Service
class GreetingService {

    String greet() {
        return "Hello, world!";
    }
}

@Component
class StageInitializer {

    private static final Logger log = LoggerFactory.getLogger(StageInitializer.class);

    private final GreetingService greetings;

    private final AtomicInteger  clicks = new AtomicInteger(0);

    StageInitializer(GreetingService greetings) {
        this.greetings = greetings;
    }

    @EventListener
    void on(StageReadyEvent event) {
        var greeting = new Label(this.greetings.greet());
        greeting.getStyleClass().add("greeting");

        var counter = new Label("no clicks yet");
        var button = new Button("Click me");
        button.setDefaultButton(true);
        button.setOnAction(e -> counter.setText("clicked %d time(s)".formatted(this.clicks.getAndIncrement())));

        var layout = new VBox(12, greeting, button, counter);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(32));

        var scene = new Scene(layout, 420, 280);
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

    }

}
