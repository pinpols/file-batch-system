package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AlertLabels: alert_event → AM label 映射")
class AlertLabelsTest {

  @Test
  @DisplayName("severity 词形映射(WARN→warning 是词形差异,非简单 lowercase)")
  void amSeverity_mapsWordForms() {
    assertThat(AlertLabels.amSeverity("INFO")).isEqualTo("info");
    assertThat(AlertLabels.amSeverity("WARN")).isEqualTo("warning");
    assertThat(AlertLabels.amSeverity("ERROR")).isEqualTo("error");
    assertThat(AlertLabels.amSeverity("CRITICAL")).isEqualTo("critical");
  }

  @Test
  @DisplayName("severity 兜底:未知/空/warn 小写归一")
  void amSeverity_fallbacks() {
    assertThat(AlertLabels.amSeverity(null)).isEqualTo("warning");
    assertThat(AlertLabels.amSeverity("")).isEqualTo("warning");
    assertThat(AlertLabels.amSeverity("warn")).isEqualTo("warning");
    assertThat(AlertLabels.amSeverity("Notice")).isEqualTo("notice");
  }

  @Test
  @DisplayName("alert_group 由 alert_type 关键字推导,对齐 route matcher")
  void alertGroup_derivesFromKeyword() {
    assertThat(AlertLabels.alertGroup("DISPATCH_ACK_TIMEOUT")).isEqualTo("dispatch");
    assertThat(AlertLabels.alertGroup("JOB_SLA_BREACH")).isEqualTo("sla");
    assertThat(AlertLabels.alertGroup("ASSET_FRESHNESS_STALE")).isEqualTo("freshness");
    assertThat(AlertLabels.alertGroup("WORKER_DRAIN_BACKPRESSURE")).isEqualTo("capacity");
    assertThat(AlertLabels.alertGroup("WORKFLOW_VALIDATION_FAILED")).isEqualTo("ops");
    assertThat(AlertLabels.alertGroup(null)).isEqualTo("ops");
  }

  @Test
  @DisplayName("team 由 alert_group 一一派生")
  void team_derivesFromGroup() {
    assertThat(AlertLabels.team("DISPATCH_X")).isEqualTo("batch-dispatch");
    assertThat(AlertLabels.team("JOB_SLA_X")).isEqualTo("batch-sla");
    assertThat(AlertLabels.team("ASSET_FRESHNESS_X")).isEqualTo("batch-data");
    assertThat(AlertLabels.team("CAPACITY_X")).isEqualTo("batch-sre");
    assertThat(AlertLabels.team("OTHER")).isEqualTo("batch-ops");
  }
}
