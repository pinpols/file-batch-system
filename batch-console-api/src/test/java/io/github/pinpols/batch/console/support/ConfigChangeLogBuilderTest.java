package io.github.pinpols.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigChangeLogBuilderTest {

  @Test
  void shouldApplyDefaultsForMinimalBuild() {
    Map<String, Object> map =
        ConfigChangeLogBuilder.create("t1", "alice", "trace-1")
            .forType("BATCH_WINDOW")
            .withKey("W1")
            .action("INSERT")
            .summary("{\"reason\":\"bulk\"}")
            .build();

    assertThat(map)
        .containsEntry("tenantId", "t1")
        .containsEntry("configType", "BATCH_WINDOW")
        .containsEntry("configKey", "W1")
        .containsEntry("versionNo", 1)
        .containsEntry("changeAction", "INSERT")
        .containsEntry("changeResult", "SUCCESS")
        .containsEntry("operatorType", "USER")
        .containsEntry("operatorId", "alice")
        .containsEntry("traceId", "trace-1")
        .containsEntry("changeSummaryJson", "{\"reason\":\"bulk\"}");
  }

  @Test
  void shouldOverrideDefaultsWhenExplicitlySet() {
    Map<String, Object> map =
        ConfigChangeLogBuilder.create("t1", "api-client", "trace-9")
            .forType("CONFIG_RELEASE")
            .withKey("release-1")
            .action("PUBLISH")
            .summary("{}")
            .versionNo(7)
            .operatorType("API")
            .result("FAILED")
            .build();

    assertThat(map)
        .containsEntry("versionNo", 7)
        .containsEntry("operatorType", "API")
        .containsEntry("changeResult", "FAILED");
  }

  @Test
  void shouldSanitizeOperatorIdAndTraceIdByLength() {
    String longOperator = "a".repeat(80);
    String longTrace = "b".repeat(200);

    Map<String, Object> map =
        ConfigChangeLogBuilder.create("t1", longOperator, longTrace)
            .forType("X")
            .withKey("k")
            .action("INSERT")
            .summary("{}")
            .build();

    assertThat((String) map.get("operatorId")).hasSize(64);
    assertThat((String) map.get("traceId")).hasSize(128);
  }

  @Test
  void shouldPassThroughNullTraceIdWithoutThrowing() {
    Map<String, Object> map =
        ConfigChangeLogBuilder.create("t1", "alice", null)
            .forType("X")
            .withKey("k")
            .action("INSERT")
            .summary("{}")
            .build();

    assertThat(map).containsEntry("traceId", null);
  }
}
