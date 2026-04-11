package com.example.batch.console.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.batch.console.support.ConsoleRoles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "batch.console.security")
public class ConsoleSecurityProperties {

    private boolean enabled = true;
    private String sharedSecret = "console-secret";
    private String tenantHeader = "X-Tenant-Id";
    private String userHeader = "X-Console-User";
    private String roleHeader = "X-Console-Roles";
    private String tokenHeader = "X-Console-Token";
    private String defaultTenantId = "";
    private List<String> allowedTenants = new ArrayList<>();
    private List<String> defaultAuthorities =
            new ArrayList<>(List.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR, ConsoleRoles.CONFIG_ADMIN));
    private boolean legacyHeaderAuthEnabled = true;
    private boolean singleSessionEnabled = true;
    private String jwtIssuer = "batch-console-api";
    private String jwtSecret = "console-jwt-secret-change-me";
    private Duration jwtTtl = Duration.ofHours(8);
    private Duration jwtClockSkew = Duration.ofMinutes(1);
    private Duration sessionStateTtl = Duration.ofDays(30);
}
