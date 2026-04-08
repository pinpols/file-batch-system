package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.service.ConsoleAuthApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleAuthTokenResponse;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConsoleAuthControllerTest {

    private MockMvc mockMvc;
    private ConsoleAuthApplicationService authApplicationService;

    @BeforeEach
    void setUp() {
        ConsoleSecurityProperties securityProperties = new ConsoleSecurityProperties();
        authApplicationService = Mockito.mock(ConsoleAuthApplicationService.class);
        ConsoleRequestMetadataResolver requestMetadataResolver = Mockito.mock(ConsoleRequestMetadataResolver.class);
        when(requestMetadataResolver.current()).thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", null, null, null, "127.0.0.1"));
        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ConsoleAuthController(authApplicationService, new ConsoleResponseFactory(requestMetadataResolver)))
                .build();
    }

    @Test
    void shouldLoginWithBuiltInAccount() throws Exception {
        when(authApplicationService.login(any())).thenReturn(new ConsoleAuthTokenResponse(
                "jwt-token",
                "Bearer",
                Instant.parse("2026-04-05T00:00:00Z"),
                Instant.parse("2026-04-05T08:00:00Z"),
                "admin",
                "default-tenant",
                Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN")
        ));

        mockMvc.perform(post("/api/console/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "admin123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.tenantId").value("default-tenant"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }
}
