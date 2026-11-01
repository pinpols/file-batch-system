package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox 清理保留天数在应用服务入口生效")
class OutboxOpsApplicationServiceTest {

  @Mock
  private OutboxEventMapper outboxMapper;

  @Mock
  private JobExecutionLogMapper logMapper;

  @ParameterizedTest
  @CsvSource({"0, false", "-1, false", "0, true", "-1, true"})
  @DisplayName("零或负保留天数在预演和实删时均拒绝且不访问数据库")
  void shouldRejectInvalidRetentionForAllModes(int retainDays, boolean dryRun) {
    OutboxOpsApplicationService service = new OutboxOpsApplicationService(outboxMapper, logMapper);

    assertThatThrownBy(() -> service.cleanup("tenant-a", retainDays, dryRun, "operator", "reason"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
    verifyNoInteractions(outboxMapper, logMapper);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("兼容入口也拒绝零或负保留天数")
  void shouldRejectInvalidRetentionForLegacyEntry(int retainDays) {
    OutboxOpsApplicationService service = new OutboxOpsApplicationService(outboxMapper, logMapper);

    assertThatThrownBy(() -> service.cleanup("tenant-a", retainDays))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
    verifyNoInteractions(outboxMapper, logMapper);
  }

  @Test
  @DisplayName("正保留天数预演只计数，实际清理才删除")
  void shouldKeepDryRunAndDeleteBehaviorForPositiveRetention() {
    OutboxOpsApplicationService service = new OutboxOpsApplicationService(outboxMapper, logMapper);

    service.cleanup("tenant-a", 1, true, "operator", "reason");
    verify(outboxMapper).countPublishedBefore(eq("tenant-a"), any(Instant.class));
    verify(outboxMapper).countGiveUpBefore(eq("tenant-a"), any(Instant.class));
    verify(outboxMapper, never()).deletePublishedBefore(eq("tenant-a"), any(Instant.class));
    verify(outboxMapper, never()).deleteGiveUpBefore(eq("tenant-a"), any(Instant.class));

    service.cleanup("tenant-a", 1, false, "operator", "reason");
    verify(outboxMapper).deletePublishedBefore(eq("tenant-a"), any(Instant.class));
    verify(outboxMapper).deleteGiveUpBefore(eq("tenant-a"), any(Instant.class));
  }
}
