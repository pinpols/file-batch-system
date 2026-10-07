package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("配置发布单应用服务: 严格上送,载荷校验与类型别名规范化")
class DefaultConfigReleaseApplyServiceTest {

  private ConsoleTenantConfigInitApplicationService initService;
  private ConsoleConfigCacheInvalidationService invalidationService;
  private DefaultConfigReleaseApplyService service;

  @BeforeEach
  void setUp() {
    initService = mock(ConsoleTenantConfigInitApplicationService.class);
    invalidationService = mock(ConsoleConfigCacheInvalidationService.class);
    service = new DefaultConfigReleaseApplyService(
        initService, invalidationService, new ConfigReleaseTypeRegistry());
  }

  @Test
  @DisplayName("应用作业类发布单时,按严格模式上送并清理对应缓存")
  void shouldApplyJobReleaseThroughStrictHandler_whenReleaseIsApplied() {
    ConfigReleaseEntity release = release("JOB", "JOB1", "{\"jobCode\":\"JOB1\"}");
    when(initService.batchInit(any(), eq("approver"), eq("operation-1")))
        .thenReturn(new TenantConfigBatchInitResponse("operation-1", 1, 1, 0, false, List.of()));

    service.apply(release, "approver", "operation-1");

    ArgumentCaptor<TenantConfigBatchInitRequest> requestCaptor =
        ArgumentCaptor.forClass(TenantConfigBatchInitRequest.class);
    verify(initService).batchInit(requestCaptor.capture(), eq("approver"), eq("operation-1"));
    TenantConfigBatchInitRequest request = requestCaptor.getValue();
    assertThat(request.isStrict()).isTrue();
    assertThat(request.getTargetTenantIds()).containsExactly("t1");
    assertThat(request.getJobDefinitions())
        .singleElement()
        .extracting("jobCode")
        .isEqualTo("JOB1");
    verify(invalidationService).evictJobDefinition("t1", "JOB1");
  }

  @Test
  @DisplayName("载荷内的标识与发布单键不一致时,校验直接拒绝")
  void shouldReject_whenPayloadIdentityDiffersFromReleaseKey() {
    assertThatThrownBy(() -> service.validate("JOB", "JOB1", "{\"jobCode\":\"JOB2\"}"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("配置类型不在支持范围内时,校验直接拒绝")
  void shouldReject_whenConfigTypeIsUnsupported() {
    assertThatThrownBy(() -> service.validate("UNKNOWN", "KEY1", "{}"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("受支持的配置类型别名,被规范化成标准类型码")
  void shouldCanonicalize_whenConfigTypeAliasIsSupported() {
    assertThat(service.canonicalType(" job_definition ")).isEqualTo("JOB");
    assertThat(service.canonicalType("queue")).isEqualTo("RESOURCE_QUEUE");
  }

  private static ConfigReleaseEntity release(String type, String key, String payload) {
    ConfigReleaseEntity release = new ConfigReleaseEntity();
    release.setTenantId("t1");
    release.setConfigType(type);
    release.setConfigKey(key);
    release.setConfigPayload(payload);
    return release;
  }
}
