package com.example.javafxnative;

import org.springframework.core.NativeDetector;
import org.springframework.stereotype.Service;

@Service
public class GreetingService {

	private final GreetingProperties properties;

	GreetingService(GreetingProperties properties) {
		this.properties = properties;
	}

	public String greet() {
		return "Hello, %s!".formatted(this.properties.name());
	}

	public String runtime() {
		return NativeDetector.inNativeImage() ? "GraalVM native image" : "JVM";
	}

}
