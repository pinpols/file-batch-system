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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultConfigReleaseApplyServiceTest {

  private ConsoleTenantConfigInitApplicationService initService;
  private ConsoleConfigCacheInvalidationService invalidationService;
  private DefaultConfigReleaseApplyService service;

  @BeforeEach
  void setUp() {
    initService = mock(ConsoleTenantConfigInitApplicationService.class);
    invalidationService = mock(ConsoleConfigCacheInvalidationService.class);
    service = new DefaultConfigReleaseApplyService(initService, invalidationService);
  }

  @Test
  void appliesJobReleaseThroughStrictTenantConfigHandler() {
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
  void rejectsPayloadWhoseIdentityDiffersFromReleaseKey() {
    assertThatThrownBy(() -> service.validate("JOB", "JOB1", "{\"jobCode\":\"JOB2\"}"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void rejectsUnsupportedConfigType() {
    assertThatThrownBy(() -> service.validate("UNKNOWN", "KEY1", "{}"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void canonicalizesSupportedTypeAliases() {
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
