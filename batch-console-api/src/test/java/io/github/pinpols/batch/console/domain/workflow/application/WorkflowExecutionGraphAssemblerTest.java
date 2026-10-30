package io.github.pinpols.batch.console.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionNodeView;
import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionSnapshotView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("运行DAG冻结图与实时节点状态投影")
class WorkflowExecutionGraphAssemblerTest {
  @Test
  @DisplayName("根运行时间来自持久化记录，未知时不以采样时间推断")
  void shouldExposeRootTimesWithoutInferringUnknownValues() {
    var root = run(SNAPSHOT);
    var unknown = WorkflowExecutionGraphAssembler.assemble(root, List.of());
    assertThat(unknown.startedAt()).isNull();
    assertThat(unknown.finishedAt()).isNull();
    Instant started = Instant.parse("2026-10-26T02:00:00Z");
    Instant finished = Instant.parse("2026-10-26T02:01:00Z");
    root.setStartedAt(started);
    root.setFinishedAt(finished);
    root.setRunStatus("SUCCESS");
    var known = WorkflowExecutionGraphAssembler.assemble(root, List.of());
    assertThat(known.startedAt()).isEqualTo(started);
    assertThat(known.finishedAt()).isEqualTo(finished);
  }

  private static final String SNAPSHOT = """
      {"schemaVersion":2,"workflowDefinitionId":10,"workflowVersion":7,
       "dispatchSecretBindings":{"A":{"password":"do-not-leak"}},
       "nodes":[{"nodeCode":"S","nodeType":"START"},
                {"nodeCode":"A","nodeName":"导入","nodeType":"JOB","relatedJobCode":"IMPORT_A","nodeParams":"do-not-leak"},
                {"nodeCode":"B","nodeType":"JOB","relatedJobCode":"EXPORT_B"},
                {"nodeCode":"E","nodeType":"END"}],
       "edges":[{"fromNodeCode":"S","toNodeCode":"A","edgeType":"SUCCESS"},
                {"fromNodeCode":"S","toNodeCode":"B","edgeType":"SUCCESS"},
                {"fromNodeCode":"A","toNodeCode":"E","edgeType":"SUCCESS"},
                {"fromNodeCode":"B","toNodeCode":"E","edgeType":"SUCCESS"}]}
      """;

  @Test
  @DisplayName("冻结图完整返回并保留未创建运行记录的待执行节点")
  void shouldKeepUnstartedNodes_whenRuntimeRowsAreIncomplete() {
    var actual = WorkflowExecutionGraphAssembler.assemble(run(SNAPSHOT), List.of());
    assertThat(actual.snapshotAvailable()).isTrue();
    assertThat(actual.workflowVersion()).isEqualTo(7);
    assertThat(actual.nodes())
        .hasSize(4)
        .allSatisfy(node -> assertThat(node.nodeStatus()).isEqualTo("PENDING"));
    assertThat(actual.edges()).hasSize(4);
    assertThat(actual.nodes().get(1).relatedJobCode()).isEqualTo("IMPORT_A");
  }

  @Test
  @DisplayName("并行节点同时展示最新尝试与实际工作量，不按最大ID误选旧重试")
  void shouldUseAttemptSequence_whenOldAttemptHasHigherId() {
    var old = WorkflowExecutionNodeView.builder()
        .id(999L)
        .nodeCode("A")
        .runSeq(1)
        .nodeStatus("FAILED")
        .build();
    var running = WorkflowExecutionNodeView.builder()
        .id(100L)
        .nodeCode("A")
        .runSeq(2)
        .nodeStatus("RUNNING")
        .jobInstanceId(42L)
        .createdTasks(3L)
        .finishedTasks(2L)
        .expectedPartitions(4)
        .successPartitions(1)
        .failedPartitions(1)
        .activeSteps("READ,LOAD")
        .build();
    var parallel = WorkflowExecutionNodeView.builder()
        .id(200L)
        .nodeCode("B")
        .runSeq(1)
        .nodeStatus("RUNNING")
        .build();
    var actual =
        WorkflowExecutionGraphAssembler.assemble(run(SNAPSHOT), List.of(old, running, parallel));
    assertThat(actual.nodes())
        .filteredOn(node -> "RUNNING".equals(node.nodeStatus()))
        .hasSize(2);
    var firstJob = actual.nodes().get(1);
    assertThat(firstJob.nodeRunId()).isEqualTo(100L);
    assertThat(firstJob.completedPartitions()).isEqualTo(2);
    assertThat(firstJob.finishedTasks()).isEqualTo(2);
    assertThat(firstJob.activeSteps()).containsExactly("READ", "LOAD");
  }

  @Test
  @DisplayName("节点自身仍就绪时同时返回关联作业的实际运行状态")
  void shouldExposeLinkedJobStatus_whenWorkflowNodeWaitsForChildJob() {
    var childRunning = WorkflowExecutionNodeView.builder()
        .id(100L)
        .nodeCode("A")
        .runSeq(1)
        .nodeStatus("READY")
        .jobInstanceId(42L)
        .jobInstanceStatus("RUNNING")
        .build();

    var actual = WorkflowExecutionGraphAssembler.assemble(run(SNAPSHOT), List.of(childRunning));

    var jobNode = actual.nodes().get(1);
    assertThat(jobNode.nodeStatus()).isEqualTo("READY");
    assertThat(jobNode.jobInstanceId()).isEqualTo(42L);
    assertThat(jobNode.jobInstanceStatus()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("图响应不泄露快照参数或密钥绑定")
  void shouldExcludeProtectedPayload_whenSerializingProgress() {
    var actual = WorkflowExecutionGraphAssembler.assemble(run(SNAPSHOT), List.of());
    assertThat(JsonUtils.toJson(actual))
        .doesNotContain("do-not-leak", "dispatchSecretBindings", "nodeParams");
  }

  @Test
  @DisplayName("缺少运行快照时明确不可用，不回退当前定义伪造运行图")
  void shouldReportMissingSnapshot_withoutMutableDefinitionFallback() {
    var actual = WorkflowExecutionGraphAssembler.assemble(run(null), List.of());
    assertThat(actual.snapshotAvailable()).isFalse();
    assertThat(actual.nodes()).isEmpty();
    assertThat(actual.edges()).isEmpty();
  }

  @Test
  @DisplayName("跨定义或版本快照拒绝作为本次运行图")
  void shouldRejectMismatchedSnapshot_whenDefinitionOrVersionChanges() {
    assertThatThrownBy(() -> WorkflowExecutionGraphAssembler.assemble(
            run(SNAPSHOT.replace("\"workflowVersion\":7", "\"workflowVersion\":8")), List.of()))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("悬空边与图外运行节点不能被静默隐藏")
  void shouldRejectDanglingGraph_whenNodeIdentityIsUnknown() {
    var unknown = WorkflowExecutionNodeView.builder()
        .id(500L)
        .runSeq(1)
        .nodeCode("OTHER")
        .nodeStatus("RUNNING")
        .build();
    assertThatThrownBy(
            () -> WorkflowExecutionGraphAssembler.assemble(run(SNAPSHOT), List.of(unknown)))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> WorkflowExecutionGraphAssembler.assemble(
            run(SNAPSHOT.replace("\"toNodeCode\":\"E\"", "\"toNodeCode\":\"UNKNOWN\"")), List.of()))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("根运行终态且节点无执行记录时明确未执行，不伪装成功或仍待调度")
  void shouldMarkNoAttempt_whenRootIsTerminal() {
    for (String status :
        List.of("SUCCESS", "FAILED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN")) {
      var root = run(SNAPSHOT);
      root.setRunStatus(status);
      var started = WorkflowExecutionNodeView.builder()
          .id(1L)
          .nodeCode("S")
          .runSeq(1)
          .nodeStatus("SUCCESS")
          .build();
      var actual = WorkflowExecutionGraphAssembler.assemble(root, List.of(started));
      assertThat(actual.nodes().getFirst().nodeStatus()).isEqualTo("SUCCESS");
      assertThat(actual.nodes())
          .filteredOn(node -> node.nodeRunId() == null)
          .hasSize(3)
          .allSatisfy(node -> assertThat(node.nodeStatus()).isEqualTo("NOT_EXECUTED"));
    }
  }

  private static WorkflowExecutionSnapshotView run(String snapshot) {
    var run = new WorkflowExecutionSnapshotView();
    run.setId(50L);
    run.setTenantId("test-progress");
    run.setWorkflowDefinitionId(10L);
    run.setWorkflowVersionSnapshot(7);
    run.setBizDate(LocalDate.of(2026, 10, 25));
    run.setRunStatus("RUNNING");
    run.setExecutionSnapshot(snapshot);
    return run;
  }
}
