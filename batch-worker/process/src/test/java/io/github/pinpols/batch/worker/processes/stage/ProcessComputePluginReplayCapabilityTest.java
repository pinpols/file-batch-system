package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessComputePluginReplayCapabilityTest {

  @Test
  void undeclaredPluginCapabilityRemainsUnknownForFrozenConfig() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class, CALLS_REAL_METHODS);
    when(plugin.implCode()).thenReturn("custom_process");

    var declaration = plugin.replayCapability(Map.of("target", "ledger"));

    assertThat(declaration.pluginId()).isEqualTo("custom_process");
    assertThat(declaration.idempotencyCapability()).isEqualTo(IdempotencyCapability.UNKNOWN);
    assertThat(declaration.isFullyDeclared()).isFalse();
  }
}
