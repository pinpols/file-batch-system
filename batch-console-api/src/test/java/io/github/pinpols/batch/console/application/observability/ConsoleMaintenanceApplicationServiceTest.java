package io.github.pinpols.batch.console.application.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("维护状态响应保留时间与生效状态")
class ConsoleMaintenanceApplicationServiceTest {
  private final MaintenanceStateHolder holder = mock(MaintenanceStateHolder.class);
  private final ConsoleMaintenanceApplicationService service =
      new ConsoleMaintenanceApplicationService(holder, mock(ConsoleRequestMetadataResolver.class));

  @Test
  @DisplayName("时间字段和版本原样映射，已开始的维护显示生效")
  void shouldMapMaintenanceState() {
    Instant eta = Instant.parse("2030-01-01T00:00:00Z");
    Instant start = Instant.parse("2020-01-01T00:00:00Z");
    Instant updated = Instant.parse("2020-01-01T00:00:01Z");
    when(holder.current())
        .thenReturn(new MaintenanceState(
            true, true, "planned", eta, start, List.of("console"), 4L, updated, true));

    var response = service.status();

    assertThat(response.etaAt()).isEqualTo("2030-01-01T00:00:00Z");
    assertThat(response.startsAt()).isEqualTo("2020-01-01T00:00:00Z");
    assertThat(response.updatedAt()).isEqualTo("2020-01-01T00:00:01Z");
    assertThat(response.active()).isTrue();
    assertThat(response.affectedServices()).containsExactly("console");
    assertThat(response.version()).isEqualTo(4L);
  }

  @Test
  @DisplayName("未配置的时间保持空，关闭维护时不生效")
  void shouldKeepMissingTimesAndDisabledState() {
    when(holder.current()).thenReturn(MaintenanceState.disabled());

    var response = service.status();

    assertThat(response.etaAt()).isNull();
    assertThat(response.startsAt()).isNull();
    assertThat(response.updatedAt()).isNull();
    assertThat(response.active()).isFalse();
  }
}
