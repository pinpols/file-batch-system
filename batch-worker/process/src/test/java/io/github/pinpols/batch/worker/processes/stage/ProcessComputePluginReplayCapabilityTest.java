package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Process 计算插件的冻结配置与 Replay 能力")
class ProcessComputePluginReplayCapabilityTest {

  @DisplayName("冻结配置中的插件未声明能力时保持未知")
  @Test
  void shouldKeepCapabilityUnknown_whenFrozenPluginUndeclared() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class, CALLS_REAL_METHODS);
    when(plugin.implCode()).thenReturn("custom_process");

    var declaration = plugin.replayCapability(Map.of("target", "ledger"));

    assertThat(declaration.pluginId()).isEqualTo("custom_process");
    assertThat(declaration.idempotencyCapability()).isEqualTo(IdempotencyCapability.UNKNOWN);
    assertThat(declaration.isFullyDeclared()).isFalse();
  }
}
