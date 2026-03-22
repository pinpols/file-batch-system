package com.example.batch.console.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.console.security")
public class ConsoleSecurityProperties {

    private boolean enabled = true;
    private String sharedSecret = "console-secret";
    private String tenantHeader = "X-Tenant-Id";
    private String userHeader = "X-Console-User";
    private String roleHeader = "X-Console-Roles";
    private String tokenHeader = "X-Console-Token";
    private String defaultTenantId = "default-tenant";
    private List<String> allowedTenants = new ArrayList<>(List.of("default-tenant"));
    private List<String> defaultAuthorities = new ArrayList<>(List.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"));
}
