package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchWindowUpdateRequest;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpdateParam;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 除真实HTTP持久化验证外，单独守护错误身份与缓存失效边界，不依赖数据库报格式错误。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("窗口更新拒绝：固定错误上下文且无写入或缓存失效副作用")
class ConsoleBatchWindowUpdateValidationTest {

  @Mock
  private BatchWindowMapper windows;

  @Mock
  private TenantIdResolver tenants;

  @Mock
  private ConsoleConfigCacheInvalidationService cache;

  @InjectMocks
  private DefaultConsoleBatchWindowApplicationService service;

  @BeforeEach
  void prepareWindow() {
    when(tenants.resolveTenant("ta")).thenReturn("ta");
    when(windows.selectById("ta", 1L))
        .thenReturn(new BatchWindowRow(
            1L,
            "ta",
            "fixture_window",
            "窗口夹具",
            "UTC",
            "01:00:00",
            "02:00:00",
            "FINISH_RUNNING",
            "WAIT",
            false,
            true,
            null,
            null,
            null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"timezone", "startTime", "endTime"})
  @DisplayName("非法时区或时间格式以稳定参数名拒绝，不调用更新和缓存")
  void shouldRejectInvalidFormats_beforeWriteAndCacheInvalidation(String field) {
    BatchWindowUpdateRequest request = new BatchWindowUpdateRequest();
    request.setTenantId("ta");
    switch (field) {
      case "timezone" -> request.setTimezone("Mars/Phobos");
      case "startTime" -> request.setStartTime("not-a-time");
      case "endTime" -> request.setEndTime("not-a-time");
      default -> throw new IllegalArgumentException("unexpected fixture field");
    }

    assertThatThrownBy(() -> service.update(1L, request))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
          assertThat(failure.getMessageKey()).isEqualTo(ResultCode.INVALID_ARGUMENT.detailKey());
          assertThat(failure.getMessageArgs()).containsExactly(field);
        });

    verify(windows, never()).update(any(BatchWindowUpdateParam.class));
    verifyNoInteractions(cache);
  }
}
