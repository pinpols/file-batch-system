package io.github.pinpols.batch.console.domain.ops.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApprovalApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseApprovalSubmitRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleConfigApprovalController submit/detail/approve/reject 4 端点透传 + idempotency. */
class ConsoleConfigApprovalControllerTest {

  private final ConsoleConfigApprovalApplicationService service =
      mock(ConsoleConfigApprovalApplicationService.class);
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
                new ConsoleConfigApprovalController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void submitApprovalShouldPassReleaseIdAndForwardBody() throws Exception {
    when(service.submit(eq(7L), any(ConfigReleaseApprovalSubmitRequest.class)))
        .thenReturn(Map.of("approvalId", 100));
    mockMvc
        .perform(
            post("/api/console/config/releases/7/submit-approval")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\",\"operatorId\":\"admin\",\"reason\":\"go\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approvalId").value(100));
    verify(service).submit(eq(7L), any(ConfigReleaseApprovalSubmitRequest.class));
  }

  @Test
  void approvalDetailShouldPassReleaseIdAndTenant() throws Exception {
    when(service.detail("ta", 7L)).thenReturn(Map.of("status", "PENDING"));
    mockMvc
        .perform(get("/api/console/config/releases/7/approval").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"));
    verify(service).detail("ta", 7L);
  }

  @Test
  void approveShouldPassApprovalIdAndBody() throws Exception {
    when(service.approve(eq(100L), any(ConfigApprovalActionRequest.class)))
        .thenReturn(Map.of("status", "APPROVED"));
    mockMvc
        .perform(
            post("/api/console/config/approvals/100/approve")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\",\"operatorId\":\"admin\",\"reason\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("APPROVED"));
    verify(service).approve(eq(100L), any(ConfigApprovalActionRequest.class));
  }

  @Test
  void rejectShouldPassApprovalIdAndBody() throws Exception {
    when(service.reject(eq(100L), any(ConfigApprovalActionRequest.class)))
        .thenReturn(Map.of("status", "REJECTED"));
    mockMvc
        .perform(
            post("/api/console/config/approvals/100/reject")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\",\"operatorId\":\"admin\",\"reason\":\"no\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    verify(service).reject(eq(100L), any(ConfigApprovalActionRequest.class));
  }

  @Test
  void approveShouldRejectMissingOperatorId() throws Exception {
    // @NotBlank operatorId
    mockMvc
        .perform(
            post("/api/console/config/approvals/100/approve")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\"}"))
        .andExpect(status().isBadRequest());
  }
}
