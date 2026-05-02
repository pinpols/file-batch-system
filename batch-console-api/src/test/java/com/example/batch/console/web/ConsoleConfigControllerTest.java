package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleConfigApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import com.example.batch.console.web.response.ops.ConsoleSecretVersionResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleConfigControllerTest {

  private final ConsoleConfigApplicationService configApplicationService =
      mock(ConsoleConfigApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleConfigController(configApplicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturnConfigReleaseListAsDtos() throws Exception {
    when(configApplicationService.configReleases(any()))
        .thenReturn(
            List.of(
                new ConsoleConfigReleaseResponse(
                    1L,
                    "t1",
                    "FILE",
                    "KEY",
                    "Name",
                    "DRAFT",
                    1,
                    "{}",
                    "{\"a\":1}",
                    Instant.EPOCH,
                    Instant.EPOCH,
                    null,
                    null,
                    "u1",
                    "u2",
                    Instant.EPOCH,
                    Instant.EPOCH)));

    mockMvc
        .perform(get("/api/console/config/releases").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].configKey").value("KEY"));
  }

  @Test
  void shouldPublishConfigRelease() throws Exception {
    when(configApplicationService.publishConfigRelease(anyLong(), any())).thenReturn("PUBLISHED");

    mockMvc
        .perform(
            post("/api/console/config/releases/1/publish")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","operatorId":"u1","traceId":"trace-1","reason":"ok"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("PUBLISHED"));

    verify(configApplicationService).publishConfigRelease(anyLong(), any());
  }

  @Test
  void shouldReturnSecretVersionAndChangeLogDtos() throws Exception {
    when(configApplicationService.secretVersions(any()))
        .thenReturn(
            List.of(
                new ConsoleSecretVersionResponse(
                    1L,
                    "t1",
                    "REF",
                    "Secret",
                    2,
                    "ACTIVE",
                    true,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    "{}",
                    "reason",
                    "u1",
                    "u2",
                    Instant.EPOCH,
                    Instant.EPOCH)));
    when(configApplicationService.configChangeLogs(any()))
        .thenReturn(
            List.of(
                new ConsoleConfigChangeLogResponse(
                    2L,
                    "t1",
                    "FILE",
                    "KEY",
                    1,
                    "CREATE",
                    "SUCCESS",
                    "API",
                    "u1",
                    "trace-1",
                    "{}",
                    Instant.EPOCH)));

    mockMvc
        .perform(get("/api/console/config/secrets").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].secretRef").value("REF"));

    mockMvc
        .perform(get("/api/console/config/change-logs").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].changeAction").value("CREATE"));
  }
}
