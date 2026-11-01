package io.github.pinpols.batch.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse.Status;
import io.github.pinpols.batch.console.application.config.ConsoleConfigBatchOperationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置批量操作回查：原请求身份与权限边界")
class ConsoleConfigBatchRecoveryControllerTest {

  private static final String ENDPOINT = "/api/console/config/batch-operation";
  private static final String AUDIT = "audit-tenant";
  private static final String HEADER_TENANT = "header-tenant";
  private static final String KEY = "test-key";
  private static final String IMPORT_HASH =
      "f282396f442ce0b443afe5df0f4b138095992967773e9d08afa4478ef6a3bc65";

  @Mock
  private ConsoleConfigBatchOperationService service;

  @Mock
  private TenantIdResolver tenantGuard;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(metadataResolver);
    ConsoleConfigBatchRecoveryController controller =
        new ConsoleConfigBatchRecoveryController(service, tenantGuard, responseFactory);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(ConsoleApiExceptionHandler.forStandaloneTest(responseFactory))
        .build();
  }

  @ParameterizedTest
  @MethodSource("originalWritePaths")
  @DisplayName("按操作类型、原 POST 路径、租户头和原键查询原批次")
  void shouldFindByOriginalRequestIdentity(String kind, String expectedHash) throws Exception {
    ConfigBatchOperationRecoveryResponse response = response(kind);
    when(service.find(AUDIT, expectedHash, kind)).thenReturn(response);

    mockMvc
        .perform(get(ENDPOINT)
            .param("auditTenantId", AUDIT)
            .param("operationKind", kind)
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, " " + HEADER_TENANT + " ")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, " " + KEY + " "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.operationId").value("original-operation"))
        .andExpect(jsonPath("$.data.operationKind").value(kind))
        .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
        .andExpect(content().string(not(containsString(KEY))));

    verify(tenantGuard).assertTenantAllowed(AUDIT);
    verify(service).find(AUDIT, expectedHash, kind);
  }

  private static Stream<Arguments> originalWritePaths() {
    // 摘要预期由原请求身份字节独立计算，避免测试直接调用被测摘要实现。
    return Stream.of(
        Arguments.of(
            "TENANT_COPY", "ac6257230d89c3c69793451af2623bfbcf3f87d2f34359363814b65b9d910d19"),
        Arguments.of(
            "JOB_BUNDLE_CREATE",
            "8655278ba244712df057c9b01d6a497ac0a47c50d4bb3ec0aa0f0306f4843a3c"),
        Arguments.of("JOB_BUNDLE_IMPORT", IMPORT_HASH));
  }

  @Test
  @DisplayName("部署上下文路径参与原请求身份计算")
  void shouldIncludeApplicationContextPath() throws Exception {
    String hash = "82e08e687b3672104be58d2d6be447abe8fdd4a7b64841265a1b74ed3d59c8d2";
    when(service.find(AUDIT, hash, "JOB_BUNDLE_IMPORT")).thenReturn(response("JOB_BUNDLE_IMPORT"));

    mockMvc
        .perform(get("/console" + ENDPOINT)
            .contextPath("/console")
            .param("auditTenantId", AUDIT)
            .param("operationKind", "JOB_BUNDLE_IMPORT")
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, HEADER_TENANT)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, KEY))
        .andExpect(status().isOk());

    verify(service).find(AUDIT, hash, "JOB_BUNDLE_IMPORT");
  }

  @Test
  @DisplayName("回查接口声明仅管理员可访问")
  void shouldDeclareAdminOnlyAuthorization() {
    PreAuthorize authorization =
        ConsoleConfigBatchRecoveryController.class.getAnnotation(PreAuthorize.class);
    assertThat(authorization).isNotNull();
    assertThat(authorization.value()).isEqualTo(ConsoleSecurityExpressions.ADMIN_ONLY);
  }

  @Test
  @DisplayName("租户守卫拒绝时不查询原批次")
  void shouldStopBeforeLookupWhenTenantGuardRejects() throws Exception {
    doThrow(new AccessDeniedException("tenant denied"))
        .when(tenantGuard)
        .assertTenantAllowed(AUDIT);

    mockMvc
        .perform(get(ENDPOINT)
            .param("auditTenantId", AUDIT)
            .param("operationKind", "JOB_BUNDLE_IMPORT")
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, HEADER_TENANT)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, KEY))
        .andExpect(status().isForbidden());

    verify(service, never()).find(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("无对应原请求或操作类型不一致时返回未找到")
  void shouldReturnNotFoundForMissingOrDifferentOperation() throws Exception {
    when(service.find(AUDIT, IMPORT_HASH, "JOB_BUNDLE_IMPORT"))
        .thenThrow(BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey()));
    for (int attempt = 0; attempt < 2; attempt++) {
      mockMvc
          .perform(get(ENDPOINT)
              .param("auditTenantId", AUDIT)
              .param("operationKind", "JOB_BUNDLE_IMPORT")
              .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, HEADER_TENANT)
              .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, KEY))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
  }

  @Test
  @DisplayName("未知操作类型和空原键返回参数错误且不查询")
  void shouldRejectInvalidKindAndBlankKey() throws Exception {
    mockMvc
        .perform(get(ENDPOINT)
            .param("auditTenantId", AUDIT)
            .param("operationKind", "INVALID")
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, HEADER_TENANT)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, KEY))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get(ENDPOINT)
            .param("auditTenantId", AUDIT)
            .param("operationKind", "JOB_BUNDLE_IMPORT")
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, HEADER_TENANT)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, " "))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get(ENDPOINT)
            .param("auditTenantId", AUDIT)
            .param("operationKind", "JOB_BUNDLE_IMPORT")
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, HEADER_TENANT))
        .andExpect(status().isBadRequest());

    verify(service, never()).find(anyString(), anyString(), anyString());
  }

  private static ConfigBatchOperationRecoveryResponse response(String kind) {
    return new ConfigBatchOperationRecoveryResponse(
        "original-operation", kind, Map.of(), Status.UNKNOWN, List.of());
  }
}
