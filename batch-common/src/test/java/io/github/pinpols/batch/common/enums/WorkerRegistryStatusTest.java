package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerRegistryStatusTest {

  @Test
  void shouldHaveCorrectCodeValues() {
    assertThat(WorkerRegistryStatus.ONLINE.code()).isEqualTo("ONLINE");
    assertThat(WorkerRegistryStatus.OFFLINE.code()).isEqualTo("OFFLINE");
    assertThat(WorkerRegistryStatus.DRAINING.code()).isEqualTo("DRAINING");
    assertThat(WorkerRegistryStatus.DECOMMISSIONED.code()).isEqualTo("DECOMMISSIONED");
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  void codeShouldMatchEnumName() {
    for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  void drainLifecycleOrderShouldBeLogical() {
    // 验证生命周期顺序：ONLINE → DRAINING → DECOMMISSIONED
    List<WorkerRegistryStatus> values = List.of(WorkerRegistryStatus.values());
    int onlineIdx = values.indexOf(WorkerRegistryStatus.ONLINE);
    int drainingIdx = values.indexOf(WorkerRegistryStatus.DRAINING);
    int decommissionedIdx = values.indexOf(WorkerRegistryStatus.DECOMMISSIONED);

    assertThat(onlineIdx).isLessThan(drainingIdx);
    assertThat(drainingIdx).isLessThan(decommissionedIdx);
  }
}
