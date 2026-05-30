package com.example.batch.console.domain.job.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.job.application.ConsoleJobApprovalService;
import com.example.batch.console.domain.job.application.ConsoleJobRecoveryService;
import com.example.batch.console.domain.job.application.ConsoleJobTriggerService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleJobControllerTest {

  private final ConsoleJobTriggerService triggerService = mock(ConsoleJobTriggerService.class);
  private final ConsoleJobRecoveryService recoveryService = mock(ConsoleJobRecoveryService.class);
  private final ConsoleJobApprovalService approvalService = mock(ConsoleJobApprovalService.class);
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
                new ConsoleJobController(
                    triggerService, recoveryService, approvalService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/console/jobs/trigger")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

    verifyNoInteractions(triggerService);
  }

  @Test
  void shouldTriggerAndReturnCommonResponseOnSuccess() throws Exception {
    when(triggerService.trigger(any(), anyString())).thenReturn("OK");

    mockMvc
        .perform(
            post("/api/console/jobs/trigger")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("OK"));
  }

  @Test
  void shouldAllowDryRunWithoutIdempotencyHeader() throws Exception {
    when(triggerService.dryRunTrigger(any())).thenReturn(Map.of("dryRun", true, "valid", true));

    mockMvc
        .perform(
            post("/api/console/jobs/trigger")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27","dryRun":true}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.dryRun").value(true))
        .andExpect(jsonPath("$.data.valid").value(true));

    verify(triggerService).dryRunTrigger(any());
  }

  @Test
  void shouldBatchTriggerJobs() throws Exception {
    when(triggerService.batchTrigger(any(), anyString()))
        .thenReturn(List.of(Map.of("index", 0, "status", "SUCCESS", "instanceNo", "INS-1")));

    mockMvc
        .perform(
            post("/api/console/jobs/batch-trigger")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-batch-001")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    [{"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}]
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].instanceNo").value("INS-1"));
  }
}
