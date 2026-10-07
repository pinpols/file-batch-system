package io.github.pinpols.batch.console.domain.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleAtomicRuntimeStatusResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.AtomicRuntimeStatusPayload;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Round-3 #8:{@link ConsoleAtomicRuntimeStatusService#toResponse} 纯映射测试,不需 Spring/HTTP。 */
@DisplayName("原子运行时状态响应映射:原始载荷完整时标记可用, 为空或缺失时标记不可用")
class ConsoleAtomicRuntimeStatusServiceTest {

  @Test
  @DisplayName("载荷映射:原始载荷完整时响应标记可用, 并原样保留各分组字段")
  void shouldMapRawActuatorPayload_intoFlatResponse() {
    AtomicRuntimeStatusPayload raw = new AtomicRuntimeStatusPayload(
        "atomic-node-1",
        "ATOMIC",
        Map.of("enabled", false, "commandWhitelistSize", 0),
        Map.of("enabled", true, "dialect", "PostgreSQL"),
        Map.of(
            "enabled",
            true,
            "enforceAllowlist",
            true,
            "enforceAllowlistSource",
            "prod-default",
            "allowlistHostsSize",
            5),
        Map.of("enabled", true, "allowedSchemasSize", 2));

    ConsoleAtomicRuntimeStatusResponse resp = ConsoleAtomicRuntimeStatusService.toResponse(raw);

    assertThat(resp.available()).isTrue();
    assertThat(resp.workerCode()).isEqualTo("atomic-node-1");
    assertThat(resp.http()).containsEntry("enforceAllowlistSource", "prod-default");
    assertThat(resp.sql()).containsEntry("dialect", "PostgreSQL");
  }

  @Test
  @DisplayName("空载荷兜底:原始载荷为空时响应不可用, 并给出不可用原因")
  void shouldReturnUnavailable_whenRawEmpty() {
    ConsoleAtomicRuntimeStatusResponse resp = ConsoleAtomicRuntimeStatusService.toResponse(null);
    assertThat(resp.available()).isFalse();
    assertThat(resp.unavailableReason()).contains("empty response");
  }
}
