package com.example.batch.orchestrator.application.service;

import com.example.batch.common.i18n.LocalizedErrorCarrier;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import lombok.Builder;

/**
 * 处理任务完成上报及工作流节点生命周期跟踪，从 {@link DefaultTaskExecutionService} 中拆分， 隔离高复杂度的结果处理逻辑（重试调度、分区/实例进度推进、DAG
 * 继续流转）。
 */
public interface TaskOutcomeService {

  record NodeRunKey(Long workflowRunId, String nodeCode, String nodeType) {}

  /**
   * @param outputJson ADR-009 Stage 1.2: worker SUCCESS 时上报的产出 JSON(已序列化字符串),写入
   *     workflow_node_run.output JSONB,供下游 workflow 节点 DSL 引用。null 表示无产出。
   */
  @Builder
  record NodeRunOutcome(
      boolean success,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Instant startedAt,
      Instant finishedAt,
      String outputJson)
      implements LocalizedErrorCarrier {

    // 桥接 record accessor → JavaBean carrier 契约
    @Override
    public String getErrorMessage() {
      return errorMessage;
    }

    @Override
    public String getErrorKey() {
      return errorKey;
    }

    @Override
    public String getErrorArgs() {
      return errorArgs;
    }
  }

  final class NodeRunFinishCommand implements LocalizedErrorCarrier {

    private final NodeRunKey key;
    private final NodeRunOutcome outcome;

    private NodeRunFinishCommand(NodeRunKey key, NodeRunOutcome outcome) {
      this.key = key;
      this.outcome = outcome;
    }

    public static NodeRunFinishCommand of(NodeRunKey key, NodeRunOutcome outcome) {
      return new NodeRunFinishCommand(key, outcome);
    }

    public static NodeRunFinishCommand success(
        NodeRunKey key, Instant startedAt, Instant finishedAt) {
      NodeRunOutcome outcome =
          NodeRunOutcome.builder()
              .success(true)
              .startedAt(startedAt)
              .finishedAt(finishedAt)
              .build();
      return of(key, outcome);
    }

    public String outputJson() {
      return outcome.outputJson();
    }

    public Long workflowRunId() {
      return key.workflowRunId();
    }

    public String nodeCode() {
      return key.nodeCode();
    }

    public String nodeType() {
      return key.nodeType();
    }

    public boolean success() {
      return outcome.success();
    }

    public String errorCode() {
      return outcome.errorCode();
    }

    public String errorMessage() {
      return outcome.errorMessage();
    }

    public String errorKey() {
      return outcome.errorKey();
    }

    public String errorArgs() {
      return outcome.errorArgs();
    }

    public Instant startedAt() {
      return outcome.startedAt();
    }

    public Instant finishedAt() {
      return outcome.finishedAt();
    }

    // ─── LocalizedErrorCarrier 桥接(已有 errorMessage()/errorKey()/errorArgs() accessor,
    //     补 JavaBean getErrorXxx() 让 LocalizedErrorRenderer.render(this) 正常工作) ──────────

    @Override
    public String getErrorMessage() {
      return outcome.errorMessage();
    }

    @Override
    public String getErrorKey() {
      return outcome.errorKey();
    }

    @Override
    public String getErrorArgs() {
      return outcome.errorArgs();
    }
  }

  WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType);

  WorkflowNodeRunEntity recordNodeRunStart(
      Long workflowRunId, String nodeCode, String nodeType, Instant startedAt);

  WorkflowNodeRunEntity recordNodeRunFinish(NodeRunFinishCommand command);

  JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command);
}
