package io.github.pinpols.batch.console.domain.workflow.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Workflow 版本快照命令的必填身份与可选审计边界")
class WorkflowVersionSnapshotCommandTest {

  @ParameterizedTest
  @ValueSource(strings = {"tenantId", "definitionId", "workflowCode", "version", "body"})
  @DisplayName("构造时拒绝缺失快照身份或定义内容")
  void shouldRejectMissingRequiredSnapshotField_whenRequiredIdentityIsNull(String missingField) {
    WorkflowVersionSnapshotCommand.WorkflowVersionSnapshotCommandBuilder builder = completeBuilder();
    switch (missingField) {
      case "tenantId" -> builder.tenantId(null);
      case "definitionId" -> builder.definitionId(null);
      case "workflowCode" -> builder.workflowCode(null);
      case "version" -> builder.version(null);
      case "body" -> builder.body(null);
      default -> throw new IllegalArgumentException(missingField);
    }

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessage(missingField);
  }

  @Test
  @DisplayName("保留调用方未指定调度开关或审计操作者的原语义")
  void shouldAllowUnspecifiedScheduleAndOperator_whenCallerOmitsOptionalFields() {
    WorkflowVersionSnapshotCommand.WorkflowVersionSnapshotCommandBuilder builder = completeBuilder();

    WorkflowVersionSnapshotCommand command = builder.build();

    assertThat(command.tenantId()).isEqualTo("t1");
    assertThat(command.definitionId()).isEqualTo(7L);
    assertThat(command.workflowCode()).isEqualTo("DAILY");
    assertThat(command.version()).isEqualTo(2);
    assertThat(command.scheduleEnabled()).isNull();
    assertThat(command.savedBy()).isNull();
  }

  private WorkflowVersionSnapshotCommand.WorkflowVersionSnapshotCommandBuilder completeBuilder() {
    WorkflowDefinitionSaveRequest body = new WorkflowDefinitionSaveRequest();
    WorkflowVersionSnapshotCommand.WorkflowVersionSnapshotCommandBuilder builder =
        WorkflowVersionSnapshotCommand.builder()
            .tenantId("t1")
            .definitionId(7L)
            .workflowCode("DAILY")
            .version(2)
            .body(body);
    return builder;
  }
}
