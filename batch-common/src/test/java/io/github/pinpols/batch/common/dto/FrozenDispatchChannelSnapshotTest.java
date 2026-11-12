package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("冻结分发通道快照: 嵌套配置深拷贝为不可变结构, 只接受 JSON 标量")
class FrozenDispatchChannelSnapshotTest {

  @Test
  @DisplayName("嵌套对象与列表被深拷贝, 调用方后续修改不影响快照且快照不可修改")
  void shouldDeepCopyNestedConfiguration_whenCapturing() {
    List<Object> hosts = new ArrayList<>(List.of("h1", Map.of("port", 22), List.of(true)));
    Map<String, Object> configJson = new LinkedHashMap<>();
    configJson.put("hosts", hosts);
    configJson.put("retry", null);
    Map<String, Object> row = row(configJson);

    FrozenDispatchChannelSnapshot snapshot = FrozenDispatchChannelSnapshot.capture(row);
    hosts.add("h2");
    configJson.put("retry", 3);

    @SuppressWarnings("unchecked")
    Map<String, Object> frozenConfig =
        (Map<String, Object>) snapshot.configuration().get("config_json");
    assertThat(frozenConfig.get("hosts"))
        .isEqualTo(List.of("h1", Map.of("port", 22), List.of(true)));
    assertThat(frozenConfig).containsEntry("retry", null);
    List<?> frozenHosts = (List<?>) frozenConfig.get("hosts");
    assertThatThrownBy(frozenHosts::clear).isInstanceOf(UnsupportedOperationException.class);
    Map<?, ?> frozenPort = (Map<?, ?>) frozenHosts.get(1);
    assertThatThrownBy(frozenPort::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("非字符串键或非 JSON 标量值的配置被拒绝")
  void shouldRejectNonJsonConfiguration() {
    Map<Object, Object> nonStringKey = new LinkedHashMap<>();
    nonStringKey.put(1, "x");
    assertThatThrownBy(() -> FrozenDispatchChannelSnapshot.capture(row(nonStringKey)))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> unsupportedScalar = new LinkedHashMap<>();
    unsupportedScalar.put("deadline", List.of(Instant.EPOCH));
    assertThatThrownBy(() -> FrozenDispatchChannelSnapshot.capture(row(unsupportedScalar)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> row(Map<?, ?> configJson) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tenant_id", "tenant-a");
    row.put("channel_code", "CH_A");
    row.put("id", 7L);
    row.put("channel_type", "SFTP");
    row.put("config_json", configJson);
    return row;
  }
}
