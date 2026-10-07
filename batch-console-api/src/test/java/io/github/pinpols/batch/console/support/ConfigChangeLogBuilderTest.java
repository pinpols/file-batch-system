package io.github.pinpols.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.logging.AuditLogConstants;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置变更日志构造: 默认值补齐、显式覆盖与字段截断")
class ConfigChangeLogBuilderTest {

  @Test
  @DisplayName("仅提供必要字段时补齐版本号、变更结果与操作人类型的默认值")
  void shouldApplyDefaultsForMinimalBuild() {
    Map<String, Object> map = ConfigChangeLogBuilder.create("t1", "alice", "trace-1")
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
  @DisplayName("显式设置版本号、操作人类型与变更结果时覆盖默认值")
  void shouldOverrideDefaultsWhenExplicitlySet() {
    Map<String, Object> map = ConfigChangeLogBuilder.create("t1", "api-client", "trace-9")
        .forType("CONFIG_RELEASE")
        .withKey("release-1")
        .action("PUBLISH")
        .summary("{}")
        .versionNo(7)
        .operatorType(AuditLogConstants.OPERATOR_TYPE_API)
        .result("FAILED")
        .build();

    assertThat(map)
        .containsEntry("versionNo", 7)
        .containsEntry("operatorType", "API")
        .containsEntry("changeResult", "FAILED");
  }

  @Test
  @DisplayName("超长操作人标识与追踪标识分别被截断为 64 与 128 个字符")
  void shouldSanitizeOperatorIdAndTraceIdByLength() {
    String longOperator = "a".repeat(80);
    String longTrace = "b".repeat(200);

    Map<String, Object> map = ConfigChangeLogBuilder.create("t1", longOperator, longTrace)
        .forType("X")
        .withKey("k")
        .action("INSERT")
        .summary("{}")
        .build();

    assertThat((String) map.get("operatorId")).hasSize(64);
    assertThat((String) map.get("traceId")).hasSize(128);
  }

  @Test
  @DisplayName("追踪标识为空时不抛异常且原样保留空值")
  void shouldPassThroughNullTraceIdWithoutThrowing() {
    Map<String, Object> map = ConfigChangeLogBuilder.create("t1", "alice", null)
        .forType("X")
        .withKey("k")
        .action("INSERT")
        .summary("{}")
        .build();

    assertThat(map).containsEntry("traceId", null);
  }
}
