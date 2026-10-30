package io.github.pinpols.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.console.domain.param.ConfigChangeLogInsertParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置变更日志构造: 默认值补齐、显式覆盖与字段截断")
class ConfigChangeLogBuilderTest {

  @Test
  @DisplayName("仅提供必要字段时补齐版本号、变更结果与操作人类型的默认值")
  void shouldApplyDefaultsForMinimalBuild() {
    ConfigChangeLogInsertParam param = ConfigChangeLogBuilder.create("t1", "alice", "trace-1")
        .forType("BATCH_WINDOW")
        .withKey("W1")
        .action("INSERT")
        .summary("{\"reason\":\"bulk\"}")
        .build();

    assertThat(param.tenantId()).isEqualTo("t1");
    assertThat(param.configType()).isEqualTo("BATCH_WINDOW");
    assertThat(param.configKey()).isEqualTo("W1");
    assertThat(param.versionNo()).isEqualTo(1);
    assertThat(param.changeAction()).isEqualTo("INSERT");
    assertThat(param.changeResult()).isEqualTo("SUCCESS");
    assertThat(param.operatorType()).isEqualTo("USER");
    assertThat(param.operatorId()).isEqualTo("alice");
    assertThat(param.traceId()).isEqualTo("trace-1");
    assertThat(param.changeSummaryJson()).isEqualTo("{\"reason\":\"bulk\"}");
  }

  @Test
  @DisplayName("显式设置版本号、操作人类型与变更结果时覆盖默认值")
  void shouldOverrideDefaultsWhenExplicitlySet() {
    ConfigChangeLogInsertParam param = ConfigChangeLogBuilder.create("t1", "api-client", "trace-9")
        .forType("CONFIG_RELEASE")
        .withKey("release-1")
        .action("PUBLISH")
        .summary("{}")
        .versionNo(7)
        .operatorType(AuditLogConstants.OPERATOR_TYPE_API)
        .result("FAILED")
        .build();

    assertThat(param.versionNo()).isEqualTo(7);
    assertThat(param.operatorType()).isEqualTo("API");
    assertThat(param.changeResult()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("超长操作人标识与追踪标识分别被截断为 64 与 128 个字符")
  void shouldSanitizeOperatorIdAndTraceIdByLength() {
    String longOperator = "a".repeat(80);
    String longTrace = "b".repeat(200);

    ConfigChangeLogInsertParam param = ConfigChangeLogBuilder.create("t1", longOperator, longTrace)
        .forType("X")
        .withKey("k")
        .action("INSERT")
        .summary("{}")
        .build();

    assertThat(param.operatorId()).hasSize(64);
    assertThat(param.traceId()).hasSize(128);
  }

  @Test
  @DisplayName("追踪标识为空时不抛异常且原样保留空值")
  void shouldPassThroughNullTraceIdWithoutThrowing() {
    ConfigChangeLogInsertParam param = ConfigChangeLogBuilder.create("t1", "alice", null)
        .forType("X")
        .withKey("k")
        .action("INSERT")
        .summary("{}")
        .build();

    assertThat(param.traceId()).isNull();
  }
}
