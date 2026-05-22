package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleSystemParameterService;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleGovernanceControllerTest {

  private final ConsoleSystemParameterService parameterService =
      mock(ConsoleSystemParameterService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleGovernanceController(
                    parameterService, responseFactory, requestMetadataResolver, tenantGuard))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldListGovernanceParams() throws Exception {
    when(parameterService.getValue("t1", "governance.outbox.circuit-breaker.failure-threshold"))
        .thenReturn(Optional.of("5"));
    when(parameterService.getValue(anyString(), anyString())).thenReturn(Optional.empty());
    when(parameterService.getValue("t1", "governance.outbox.circuit-breaker.failure-threshold"))
        .thenReturn(Optional.of("5"));

    mockMvc
        .perform(get("/api/console/ops/governance").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(
            jsonPath("$.data['governance.outbox.circuit-breaker.failure-threshold']").value("5"));
  }

  @Test
  void shouldUpdateGovernanceParam() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ops/governance")
                .param("tenantId", "t1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"key":"governance.outbox.circuit-breaker.failure-threshold","value":"5"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(parameterService)
        .upsert(
            "t1",
            "governance.outbox.circuit-breaker.failure-threshold",
            "5",
            "Governance parameter: governance.outbox.circuit-breaker.failure-threshold",
            "operator-1");
  }

  @Test
  void shouldResetGovernanceParam() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ops/governance/reset")
                .param("tenantId", "t1")
                .param("key", "governance.x"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(parameterService).delete("t1", "governance.x");
  }
}
