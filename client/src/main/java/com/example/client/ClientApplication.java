package com.example.client;

import io.arconia.multitenancy.core.context.events.TenantContextAttachedEvent;
import io.arconia.multitenancy.web.context.resolvers.HttpRequestTenantResolver;
import io.arconia.multitenancy.web.context.resolvers.OAuth2TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContextChangedEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class ClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientApplication.class, args);
    }

    @EventListener
    void on(SecurityContextChangedEvent securityContextChangedEvent) {
        IO.println("Security context changed");
    }

    @EventListener
    void on(TenantContextAttachedEvent tenantContextAttachedEvent) {
        IO.println("Tenant context attached");
    }

    @Bean
    OAuth2TenantResolver tenantResolver() {
        return OAuth2TenantResolver.builder().tenantClaimName("tenant").build();
    }
}


@Controller
@ResponseBody
class MeController {

    private final RestClient http;

    MeController(RestClient.Builder http) {
        this.http = http.build();
    }

    @GetMapping("/")
    Whoami me(@RegisteredOAuth2AuthorizedClient("spring") OAuth2AuthorizedClient client) {
        return this.http
                .get()
                .uri("http://localhost:8081/whoami")
                .headers(h -> h
                        .setBearerAuth(client.getAccessToken().getTokenValue()))
                .retrieve()
                .body(Whoami.class);

    }
}

record Whoami(String tenant, String user) {
}