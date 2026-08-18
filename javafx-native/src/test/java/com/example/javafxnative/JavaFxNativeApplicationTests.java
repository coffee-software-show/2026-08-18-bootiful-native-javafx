package com.example.javafxnative;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the Spring context only - no JavaFX toolkit, so this runs headless in CI.
 */
@SpringBootTest
class JavaFxNativeApplicationTests {

	@Autowired
	private GreetingService greetings;

	@Test
	void greetingIsBoundFromConfigurationProperties() {
		assertThat(this.greetings.greet()).isEqualTo("Hello, JavaFX!");
	}

}
