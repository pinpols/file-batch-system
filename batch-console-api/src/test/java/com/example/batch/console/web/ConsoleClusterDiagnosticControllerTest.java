package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import com.example.batch.console.domain.ops.web.ConsoleClusterDiagnosticController;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
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
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);

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
}
