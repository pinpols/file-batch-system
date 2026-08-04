package io.github.pinpols.batch.console.domain.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.infrastructure.AtomicRuntimeStatusPayload;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleAtomicRuntimeStatusResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Round-3 #8:{@link ConsoleAtomicRuntimeStatusService#toResponse} 纯映射测试,不需 Spring/HTTP。 */
class ConsoleAtomicRuntimeStatusServiceTest {

  @Test
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
  void shouldReturnUnavailable_whenRawEmpty() {
    ConsoleAtomicRuntimeStatusResponse resp = ConsoleAtomicRuntimeStatusService.toResponse(null);
    assertThat(resp.available()).isFalse();
    assertThat(resp.unavailableReason()).contains("empty response");
  }
}
