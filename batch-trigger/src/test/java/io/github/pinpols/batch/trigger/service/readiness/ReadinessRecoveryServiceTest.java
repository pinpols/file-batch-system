package io.github.pinpols.batch.trigger.service.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.readiness.ReadinessRecoveryService.RecoveryLaunchOutcome;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Readiness 恢复入口校验完整触发身份")
class ReadinessRecoveryServiceTest {

  @Mock private TriggerDefinitionLoader definitionLoader;
  @Mock private TriggerService triggerService;

  @ParameterizedTest
  @MethodSource("invalidIdentities")
  @DisplayName("租户、Workflow 标识或 fire 时间无效时拒绝且不访问定义")
  void shouldRejectIncompleteIdentity(String tenantId, Long workflowId, Instant fireTime) {
    ReadinessRecoveryService service = new ReadinessRecoveryService(definitionLoader, triggerService);

    assertThat(service.recover(tenantId, workflowId, fireTime))
        .isEqualTo(RecoveryLaunchOutcome.INVALID_REQUEST);
    verifyNoInteractions(definitionLoader, triggerService);
  }

  private static Stream<Arguments> invalidIdentities() {
    Instant fireTime = Instant.parse("2026-10-26T00:00:00Z");
    return Stream.of(
        Arguments.of(null, 1L, fireTime),
        Arguments.of("", 1L, fireTime),
        Arguments.of("  ", 1L, fireTime),
        Arguments.of("tenant-a", null, fireTime),
        Arguments.of("tenant-a", 0L, fireTime),
        Arguments.of("tenant-a", -1L, fireTime),
        Arguments.of("tenant-a", 1L, null));
  }
}
