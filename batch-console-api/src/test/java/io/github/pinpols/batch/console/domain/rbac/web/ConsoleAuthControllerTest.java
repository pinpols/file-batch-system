package io.github.pinpols.batch.console.domain.rbac.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.observability.service.SseTicketService;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleAuthApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleLoginKeyPairService;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
    ConsoleRequestMetadataResolver requestMetadataResolver =
        Mockito.mock(ConsoleRequestMetadataResolver.class);
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", null, null, null, "127.0.0.1"));
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleAuthController(
                    authApplicationService,
                    new ConsoleResponseFactory(requestMetadataResolver),
                    Mockito.mock(SseTicketService.class),
                    securityProperties,
                    Mockito.mock(ConsoleJwtService.class),
                    Mockito.mock(ConsoleLoginKeyPairService.class),
                    Mockito.mock(
                        io.github.pinpols.batch.console.domain.rbac.service
                            .ConsoleUserAccountService.class)))
            .build();
  }

  @Test
  void shouldLoginWithBuiltInAccount() throws Exception {
    when(authApplicationService.login(any()))
        .thenReturn(
            new ConsoleAuthTokenResponse(
                "jwt-token",
                "Bearer",
                Instant.parse("2026-04-05T00:00:00Z"),
                Instant.parse("2026-04-05T08:00:00Z"),
                "admin",
                "default-tenant",
                Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"),
                false));

    mockMvc
        .perform(
            post("/api/console/auth/login")
                .contentType("application/json")
                .content(
                    """
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
