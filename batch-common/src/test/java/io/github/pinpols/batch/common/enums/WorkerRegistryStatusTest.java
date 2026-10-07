package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkerRegistryStatus 执行器注册状态: 编码 / 展示名称声明与排空下线顺序")
class WorkerRegistryStatusTest {

  @Test
  @DisplayName("各执行器注册状态的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(WorkerRegistryStatus.ONLINE.code()).isEqualTo("ONLINE");
    assertThat(WorkerRegistryStatus.OFFLINE.code()).isEqualTo("OFFLINE");
    assertThat(WorkerRegistryStatus.DRAINING.code()).isEqualTo("DRAINING");
    assertThat(WorkerRegistryStatus.DECOMMISSIONED.code()).isEqualTo("DECOMMISSIONED");
  }

  @Test
  @DisplayName("每个执行器注册状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个执行器注册状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllRegistryStatuses() {
    for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("在线 / 排空中 / 已下线 的声明顺序体现排空下线流程")
  void shouldKeepDrainLifecycleOrder_whenEnumeratingStatuses() {
    // 验证生命周期顺序：ONLINE → DRAINING → DECOMMISSIONED
    List<WorkerRegistryStatus> values = List.of(WorkerRegistryStatus.values());
    int onlineIdx = values.indexOf(WorkerRegistryStatus.ONLINE);
    int drainingIdx = values.indexOf(WorkerRegistryStatus.DRAINING);
    int decommissionedIdx = values.indexOf(WorkerRegistryStatus.DECOMMISSIONED);

    assertThat(onlineIdx).isLessThan(drainingIdx);
    assertThat(drainingIdx).isLessThan(decommissionedIdx);
  }
}
