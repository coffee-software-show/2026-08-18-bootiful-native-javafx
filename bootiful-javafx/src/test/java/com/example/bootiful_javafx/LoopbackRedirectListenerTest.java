package com.example.bootiful_javafx;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class LoopbackRedirectListenerTest {

    private static final URI REDIRECT_URI = URI.create("http://127.0.0.1:8386/login/oauth2/code/javafx");

    @Test
    void deliversTheAuthorizationResponse() throws Exception {
        try (var listener = new LoopbackRedirectListener(REDIRECT_URI); var http = HttpClient.newHttpClient()) {
            var response = http.sendAsync(get(REDIRECT_URI + "?code=abc&state=xyz%2F1"),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(listener.await(Duration.ofSeconds(5)))
                    .containsEntry("code", "abc")
                    .containsEntry("state", "xyz/1");
            assertThat(response.get().statusCode()).isEqualTo(200);
            assertThat(response.get().body()).contains("You're signed in.");
        }
    }

    @Test
    void keepsListeningPastRequestsThatArentTheRedirect() throws Exception {
        try (var listener = new LoopbackRedirectListener(REDIRECT_URI); var http = HttpClient.newHttpClient()) {
            assertThat(http.send(get("http://127.0.0.1:8386/favicon.ico"), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(404);
            assertThat(http.send(get(REDIRECT_URI.toString()), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(404);
            http.sendAsync(get(REDIRECT_URI + "?error=access_denied"), HttpResponse.BodyHandlers.ofString());
            assertThat(listener.await(Duration.ofSeconds(5))).containsEntry("error", "access_denied");
        }
    }

    @Test
    void givesUpWhenNobodyComesBack() throws Exception {
        try (var listener = new LoopbackRedirectListener(REDIRECT_URI)) {
            assertThatExceptionOfType(SocketTimeoutException.class)
                    .isThrownBy(() -> listener.await(Duration.ofMillis(250)));
        }
    }

    private static HttpRequest get(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).GET().build();
    }

}
