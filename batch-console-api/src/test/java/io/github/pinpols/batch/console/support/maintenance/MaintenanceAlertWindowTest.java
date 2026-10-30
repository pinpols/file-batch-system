package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("维护通知窗口：有限时间与明确服务范围")
class MaintenanceAlertWindowTest {
  private static final Instant START = Instant.parse("2026-10-26T10:00:00Z");

  @Test
  @DisplayName("缺少 ETA 时从持久化开始时间计算，不随重试续期")
  void shouldUseFixedDefaultDeadline() {
    MaintenanceState state = state(null, null, List.of());
    MaintenanceAlertWindow window = MaintenanceAlertWindow.from(state, 120);
    assertThat(window.endsAt()).isEqualTo(START.plusSeconds(7200));
    assertThat(window.activeAt(START)).isTrue();
    assertThat(window.activeAt(START.plusSeconds(7200))).isFalse();
    assertThat(window.jobs()).containsExactly("batch-console-api");
  }

  @Test
  @DisplayName("计划开始之前不抑制，ETA 到点即退出窗口")
  void shouldRespectScheduledStartAndDeadline() {
    Instant scheduled = START.plusSeconds(3600);
    Instant end = scheduled.plusSeconds(600);
    MaintenanceState state = state(scheduled, end, List.of("trigger"));
    MaintenanceAlertWindow window = MaintenanceAlertWindow.from(state, 120);
    assertThat(window.activeAt(START)).isFalse();
    assertThat(window.activeAt(scheduled)).isTrue();
    assertThat(window.activeAt(end)).isFalse();
  }

  @Test
  @DisplayName("只识别固定服务编码，未知标记和正则字符不会扩大范围")
  void shouldNotExpandUnknownServiceMarkers() {
    MaintenanceState state =
        state(null, null, List.of("trigger", "batch-worker-import", ".*", "database"));
    MaintenanceAlertWindow window = MaintenanceAlertWindow.from(state, 120);
    assertThat(window.jobs()).containsExactlyInAnyOrder("batch-trigger", "batch-worker-import");
    MaintenanceState unknown = state(null, null, List.of(".*", "database"));
    MaintenanceAlertWindow unknownWindow = MaintenanceAlertWindow.from(unknown, 120);
    assertThat(unknownWindow.jobs()).isEmpty();
  }

  @Test
  @DisplayName("过长 ETA 最多允许一天，缺少持久化起点时不抑制")
  void shouldBoundWindowAndRejectMissingStart() {
    MaintenanceState longState = state(null, START.plusSeconds(172800), List.of());
    MaintenanceAlertWindow bounded = MaintenanceAlertWindow.from(longState, 120);
    assertThat(bounded.endsAt()).isEqualTo(START.plusSeconds(86400));
    MaintenanceState missing =
        new MaintenanceState(true, false, "维护", null, null, List.of(), 1L, null, true);
    MaintenanceAlertWindow invalid = MaintenanceAlertWindow.from(missing, 120);
    assertThat(invalid.activeAt(START)).isFalse();
  }

  private MaintenanceState state(Instant startsAt, Instant etaAt, List<String> services) {
    return new MaintenanceState(true, false, "维护", etaAt, startsAt, services, 7L, START, true);
  }
}
