package com.example.service;

import io.arconia.multitenancy.core.context.TenantContext;
import io.arconia.multitenancy.web.context.resolvers.OAuth2TenantResolver;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.Objects;

@SpringBootApplication
public class ServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }

    @Bean
    OAuth2TenantResolver tenantResolver() {
        return OAuth2TenantResolver
                .builder()
                .tenantClaimName("tenant")
                .build();
    }
}

@Controller
@ResponseBody
class TenantController {

    @GetMapping("/whoami")
    Map<String, String> me() {
        var userName = Objects.requireNonNull(
                        SecurityContextHolder
                                .getContextHolderStrategy()
                                .getContext()
                                .getAuthentication())
                .getName();
        var tenantName = TenantContext.getRequiredTenantIdentifier();
        return Map.of("user", userName, "tenant", tenantName);
    }
}