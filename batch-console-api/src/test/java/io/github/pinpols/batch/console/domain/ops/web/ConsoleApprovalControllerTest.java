package io.github.pinpols.batch.console.domain.ops.web;

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

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleApprovalApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("审批接口:缺少幂等键与请求体非法时拒绝, 成功时透传操作人与原因并返回业务结果")
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
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    // R4-P0-2：Controller 现在依赖 ConsoleTenantGuard 强校验请求体 tenantId。
    // 测试用 mock guard，resolveTenant 简单返回入参（与原 body 直传等价）。
    io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard tenantGuard =
        mock(io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant(any())).thenAnswer(inv -> inv.getArgument(0));

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleApprovalController(approvalApplicationService, responseFactory, tenantGuard))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("幂等缺失:未携带幂等键时返回参数错误, 且不调用审批服务")
  void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
    mockMvc
        .perform(post("/api/console/approvals/appr-001/approve")
            .contentType(APPLICATION_JSON)
            .content("""
                    {"tenantId":"t1","operatorId":"u1","reason":"ok"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

    verifyNoInteractions(approvalApplicationService);
  }

  @Test
  @DisplayName("审批成功:通过后返回成功响应, 并以租户与审批人参数调用服务")
  void shouldApproveAndReturnCommonResponseOnSuccess() throws Exception {
    when(approvalApplicationService.approve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("OK");

    mockMvc
        .perform(post("/api/console/approvals/appr-001/approve")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
            .contentType(APPLICATION_JSON)
            .content("""
                    {"tenantId":"t1","operatorId":"u1","reason":"ok"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("OK"));

    verify(approvalApplicationService).approve("t1", "appr-001", "u1", "ok");
  }

  @Test
  @DisplayName("请求体非法:租户与操作人为空时返回校验错误, 不进入业务处理")
  void shouldReturn400WhenRequestBodyInvalid() throws Exception {
    mockMvc
        .perform(post("/api/console/approvals/appr-001/reject")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
            .contentType(APPLICATION_JSON)
            .content("""
                    {"tenantId":"","operatorId":"","reason":"ok"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
