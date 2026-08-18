package com.example.javafxnative;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code application.properties}. Configuration property binding is reflective, so this
 * type only works in a native image because Spring Boot's AOT engine contributed the reflection
 * hints for it at build time.
 */
@ConfigurationProperties(prefix = "greeting")
public record GreetingProperties(String name) {
}
