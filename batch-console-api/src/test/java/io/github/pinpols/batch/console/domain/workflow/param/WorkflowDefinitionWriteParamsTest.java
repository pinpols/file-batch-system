package io.github.pinpols.batch.console.domain.workflow.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Workflow 写入参数在构造边界拒绝缺失身份与预期版本,保留可选字段清空语义")
class WorkflowDefinitionWriteParamsTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  @DisplayName("两种 SQL 写入都拒绝缺失或空白租户")
  void shouldRejectMissingTenant_forEveryWrite(String tenant) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> versioned().tenantId(tenant).build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> schedule().tenantId(tenant).build());
  }

  @ParameterizedTest
  @MethodSource("missingRequiredFields")
  @DisplayName("Builder 不能绕过定义身份、版本与日程类型的必填约束")
  void shouldRejectMissingRequiredField(String field, Runnable construction) {
    assertThatIllegalArgumentException().as(field).isThrownBy(construction::run);
  }

  private static Stream<Arguments> missingRequiredFields() {
    return Stream.of(
        arguments("replacement.id", (Runnable) () -> versioned().id(null).build()),
        arguments("schedule.id", (Runnable) () -> schedule().id(null).build()),
        arguments("replacement.expectedVersion", (Runnable)
            () -> versioned().expectedVersion(null).build()),
        arguments("schedule.expectedVersion", (Runnable)
            () -> schedule().expectedVersion(null).build()),
        arguments("schedule.scheduleType", (Runnable)
            () -> schedule().scheduleType(null).build()),
        arguments("schedule.scheduleType.blank", (Runnable)
            () -> schedule().scheduleType(" ").build()));
  }

  @Test
  @DisplayName("可选日程表达式、日历和依赖可以显式清空,错误的原版本仍交由 SQL 零命中")
  void shouldPreserveNullableFieldsAndStaleVersion_forSqlComparison() {
    WorkflowDefinitionVersionUpdateParam replacement =
        versioned().expectedVersion(0).build();
    WorkflowScheduleUpdateParam schedule = schedule().expectedVersion(0).build();

    assertThat(replacement.expectedVersion()).isZero();
    assertThat(replacement.scheduleExpr()).isNull();
    assertThat(schedule.expectedVersion()).isZero();
    assertThat(schedule.scheduleExpr()).isNull();
    assertThat(schedule.calendarCode()).isNull();
    assertThat(schedule.dependsOnJobCode()).isNull();
  }

  private static WorkflowDefinitionVersionUpdateParam.WorkflowDefinitionVersionUpdateParamBuilder
      versioned() {
    return WorkflowDefinitionVersionUpdateParam.builder()
        .tenantId("tenant-a")
        .id(9L)
        .expectedVersion(1);
  }

  private static WorkflowScheduleUpdateParam.WorkflowScheduleUpdateParamBuilder schedule() {
    return WorkflowScheduleUpdateParam.builder()
        .tenantId("tenant-a")
        .id(9L)
        .expectedVersion(1)
        .scheduleType("MANUAL");
  }
}
