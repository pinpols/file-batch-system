package io.github.pinpols.batch.console.domain.ops.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleClusterDiagnosticControllerTest {

  private final ConsoleClusterDiagnosticService diagnosticService =
      mock(ConsoleClusterDiagnosticService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleClusterDiagnosticController(diagnosticService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturnDiagnostic() throws Exception {
    when(diagnosticService.diagnose("t1")).thenReturn(Map.of("status", "healthy", "nodeCount", 3));

    mockMvc
        .perform(get("/api/console/ops/cluster-diagnostic").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.status").value("healthy"))
        .andExpect(jsonPath("$.data.nodeCount").value(3));
  }

  @Test
  void shouldReturnInstanceDiagnosis() throws Exception {
    when(diagnosticService.instanceDiagnosis("t1", 7L))
        .thenReturn(Map.of("healthy", false, "jobInstanceId", 7L));

    mockMvc
        .perform(get("/api/console/ops/cluster-diagnostic/instances/7").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.healthy").value(false))
        .andExpect(jsonPath("$.data.jobInstanceId").value(7));
  }
}
