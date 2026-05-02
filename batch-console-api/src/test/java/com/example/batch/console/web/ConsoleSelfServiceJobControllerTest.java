package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleSelfServiceJobService;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleSelfServiceJobControllerTest {

  private final ConsoleSelfServiceJobService selfServiceJobService =
      mock(ConsoleSelfServiceJobService.class);
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
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleSelfServiceJobController(
                    selfServiceJobService, responseFactory, requestMetadataResolver))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldSubmitRerunRequest() throws Exception {
    when(selfServiceJobService.requestRerun(
            any(ConsoleSelfServiceJobService.RerunParam.class), anyString(), anyString()))
        .thenReturn("APR-20260101-001");

    mockMvc
        .perform(
            post("/api/console/self-service/jobs/rerun-request")
                .header("Idempotency-Key", "idem-1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId":"t1",
                      "jobCode":"import-job-001",
                      "bizDate":"2026-01-01",
                      "targetInstanceNo":"INS-001",
                      "reason":"Data correction"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("APR-20260101-001"));
  }

  @Test
  void shouldSubmitCompensationRequest() throws Exception {
    when(selfServiceJobService.requestCompensation(
            any(ConsoleSelfServiceJobService.CompensationParam.class), anyString(), anyString()))
        .thenReturn("APR-20260101-002");

    mockMvc
        .perform(
            post("/api/console/self-service/jobs/compensation-request")
                .header("Idempotency-Key", "idem-2")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId":"t1",
                      "jobCode":"import-job-001",
                      "bizDate":"2026-01-01",
                      "compensationType":"FULL",
                      "targetInstanceNo":"INS-001",
                      "reason":"Missing records"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("APR-20260101-002"));
  }
}
