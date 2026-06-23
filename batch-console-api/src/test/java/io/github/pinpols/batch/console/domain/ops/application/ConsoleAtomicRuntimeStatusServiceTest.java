package io.github.pinpols.batch.console.domain.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleAtomicRuntimeStatusResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Round-3 #8:{@link ConsoleAtomicRuntimeStatusService#toResponse} 纯映射测试,不需 Spring/HTTP。 */
class ConsoleAtomicRuntimeStatusServiceTest {

  @Test
  void shouldMapRawActuatorPayload_intoFlatResponse() {
    Map<String, Object> raw =
        Map.of(
            "workerCode", "atomic-node-1",
            "workerType", "ATOMIC",
            "shell", Map.of("enabled", false, "commandWhitelistSize", 0),
            "sql", Map.of("enabled", true, "dialect", "PostgreSQL"),
            "http",
                Map.of(
                    "enabled",
                    true,
                    "enforceAllowlist",
                    true,
                    "enforceAllowlistSource",
                    "prod-default",
                    "allowlistHostsSize",
                    5),
            "storedProc", Map.of("enabled", true, "allowedSchemasSize", 2));

    ConsoleAtomicRuntimeStatusResponse resp = ConsoleAtomicRuntimeStatusService.toResponse(raw);

    assertThat(resp.available()).isTrue();
    assertThat(resp.workerCode()).isEqualTo("atomic-node-1");
    assertThat(resp.http()).containsEntry("enforceAllowlistSource", "prod-default");
    assertThat(resp.sql()).containsEntry("dialect", "PostgreSQL");
  }

  @Test
  void shouldReturnUnavailable_whenRawEmpty() {
    ConsoleAtomicRuntimeStatusResponse resp =
        ConsoleAtomicRuntimeStatusService.toResponse(Map.of());
    assertThat(resp.available()).isFalse();
    assertThat(resp.unavailableReason()).contains("empty response");
  }
}
