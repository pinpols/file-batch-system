package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleApprovalApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleApprovalControllerTest {

  private final ConsoleApprovalApplicationService approvalApplicationService =
      mock(ConsoleApprovalApplicationService.class);
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
                new ConsoleApprovalController(approvalApplicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/console/approvals/appr-001/approve")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","operatorId":"u1","reason":"ok"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

    verifyNoInteractions(approvalApplicationService);
  }

  @Test
  void shouldApproveAndReturnCommonResponseOnSuccess() throws Exception {
    when(approvalApplicationService.approve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("OK");

    mockMvc
        .perform(
            post("/api/console/approvals/appr-001/approve")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","operatorId":"u1","reason":"ok"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("OK"));

    verify(approvalApplicationService).approve("t1", "appr-001", "u1", "ok");
  }

  @Test
  void shouldReturn400WhenRequestBodyInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/console/approvals/appr-001/reject")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"","operatorId":"","reason":"ok"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
