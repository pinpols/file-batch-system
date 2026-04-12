package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleFilePipelineObservabilityControllerTest {

  private final ConsoleQueryApplicationService queryApplicationService =
      mock(ConsoleQueryApplicationService.class);
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
                new ConsoleFilePipelineObservabilityController(
                    queryApplicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturnFilePipelinesFromLegacyPath() throws Exception {
    when(queryApplicationService.filePipelines(any()))
        .thenReturn(
            new PageResponse<>(
                1L,
                1,
                20,
                List.of(
                    new ConsoleFilePipelineResponse(
                        1L,
                        "t1",
                        1001L,
                        "file-001",
                        "IMPORT",
                        2001L,
                        3001L,
                        "RECEIVE",
                        "PARSE",
                        "RUNNING",
                        "trace-1",
                        Instant.EPOCH,
                        null,
                        Instant.EPOCH,
                        Instant.EPOCH))));

    mockMvc
        .perform(get("/api/console/file-pipeline-observability").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].jobCode").value("file-001"))
        .andExpect(jsonPath("$.data.items[0].runStatus").value("RUNNING"));
  }

  @Test
  void shouldReturnFilePipelineDetailFromLegacyPath() throws Exception {
    when(queryApplicationService.filePipelineDetail(anyString(), anyLong()))
        .thenReturn(
            new ConsoleFilePipelineResponse(
                1L,
                "t1",
                1001L,
                "file-001",
                "IMPORT",
                2001L,
                3001L,
                "RECEIVE",
                "PARSE",
                "SUCCESS",
                "trace-1",
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH));

    mockMvc
        .perform(get("/api/console/file-pipeline-observability/1").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.jobCode").value("file-001"))
        .andExpect(jsonPath("$.data.runStatus").value("SUCCESS"));
  }
}
