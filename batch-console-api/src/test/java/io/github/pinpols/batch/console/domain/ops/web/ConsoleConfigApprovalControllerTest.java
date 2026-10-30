package io.github.pinpols.batch.console.domain.ops.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApprovalApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseApprovalSubmitRequest;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleConfigApprovalDetailResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleConfigApprovalController submit/detail/approve/reject 4 端点透传 + idempotency. */
@DisplayName("配置发布审批接口:提交, 详情, 通过, 驳回四端点透传参数与幂等键, 并校验租户绑定")
@ExtendWith(MockitoExtension.class)
class ConsoleConfigApprovalControllerTest {

  @Mock
  private ConsoleConfigApprovalApplicationService service;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

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
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleConfigApprovalController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("提交审批:发布标识与请求体透传服务层, 返回状态改为待审批")
  void shouldForwardReleaseIdAndBody_whenSubmittingApproval() throws Exception {
    // 键与真实 detail() 输出一致：releaseId/tenantId/configType/configKey/configStatus/approval。
    when(service.submit(eq(7L), any(ConfigReleaseApprovalSubmitRequest.class)))
        .thenReturn(approvalDetail("ta", ConfigLifecycleStatus.PENDING_APPROVAL.code(), "PENDING"));
    mockMvc
        .perform(post("/api/console/config/releases/7/submit-approval")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"operatorId\":\"admin\",\"reason\":\"go\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.releaseId").value(7))
        .andExpect(
            jsonPath("$.data.configStatus").value(ConfigLifecycleStatus.PENDING_APPROVAL.code()))
        .andExpect(jsonPath("$.data.approval.approvalStatus").value("PENDING"));
    verify(service).submit(eq(7L), any(ConfigReleaseApprovalSubmitRequest.class));
  }

  @Test
  @DisplayName("审批详情:租户与发布标识透传服务层, 返回待审批与挂起中的审批态")
  void shouldForwardReleaseIdAndTenant_whenReadingApprovalDetail() throws Exception {
    when(service.detail("ta", 7L))
        .thenReturn(approvalDetail("ta", ConfigLifecycleStatus.PENDING_APPROVAL.code(), "PENDING"));
    mockMvc
        .perform(get("/api/console/config/releases/7/approval").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.configStatus").value(ConfigLifecycleStatus.PENDING_APPROVAL.code()))
        .andExpect(jsonPath("$.data.approval.approvalStatus").value("PENDING"));
    verify(service).detail("ta", 7L);
  }

  @Test
  @DisplayName("审批通过:审批标识与请求体透传, 返回已发布与已通过状态")
  void shouldForwardApprovalIdAndBody_whenApproving() throws Exception {
    when(service.approve(eq(100L), any(ConfigApprovalActionRequest.class)))
        .thenReturn(approvalDetail(null, "PUBLISHED", "APPROVED"));
    mockMvc
        .perform(post("/api/console/config/approvals/100/approve")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"operatorId\":\"admin\",\"reason\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.configStatus").value("PUBLISHED"))
        .andExpect(jsonPath("$.data.approval.approvalStatus").value("APPROVED"));
    verify(service).approve(eq(100L), any(ConfigApprovalActionRequest.class));
  }

  @Test
  @DisplayName("审批驳回:审批标识与请求体透传, 返回草稿与已驳回状态")
  void shouldForwardApprovalIdAndBody_whenRejecting() throws Exception {
    when(service.reject(eq(100L), any(ConfigApprovalActionRequest.class)))
        .thenReturn(approvalDetail(null, "DRAFT", "REJECTED"));
    mockMvc
        .perform(post("/api/console/config/approvals/100/reject")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"operatorId\":\"admin\",\"reason\":\"no\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.configStatus").value("DRAFT"))
        .andExpect(jsonPath("$.data.approval.approvalStatus").value("REJECTED"));
    verify(service).reject(eq(100L), any(ConfigApprovalActionRequest.class));
  }

  @Test
  @DisplayName("租户缺失:请求体未绑定租户时返回参数错误, 不进入业务处理")
  void shouldRejectMissingTenantId_whenApproving() throws Exception {
    // 操作人来自认证上下文；请求体只校验租户绑定。
    mockMvc
        .perform(post("/api/console/config/approvals/100/approve")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
            .contentType(APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  private static ConsoleConfigApprovalDetailResponse approvalDetail(
      String tenantId, String status, String approvalStatus) {
    return new ConsoleConfigApprovalDetailResponse(
        7L,
        tenantId,
        null,
        null,
        status,
        ConsoleConfigApprovalDetailResponse.ApprovalRow.builder()
            .id(100L)
            .approvalStatus(approvalStatus)
            .build());
  }
}
