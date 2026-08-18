package com.example.javafxnative;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties(GreetingProperties.class)
public class JavaFxNativeApplication {

	static final long STARTED_AT = System.nanoTime();

	static ConfigurableApplicationContext APPLICATION_CONTEXT;

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
		APPLICATION_CONTEXT = new SpringApplicationBuilder()
				.sources(JavaFxNativeApplication.class)
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
		Platform.startup(() -> APPLICATION_CONTEXT.publishEvent(new StageReadyEvent(new Stage())));
	}

}
