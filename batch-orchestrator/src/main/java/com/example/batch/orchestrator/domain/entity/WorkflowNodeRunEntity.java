package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

// #8-1: 实现 Stateful 接口，消除 DefaultStateMachine 中的反射兜底路径
@Data
@EqualsAndHashCode(callSuper = false)
public class WorkflowNodeRunEntity extends AbstractLocalizedErrorEntity implements Stateful {

  private Long id;
  private Long workflowRunId;
  private String nodeCode;
  private String nodeType;

  /**
   * 同一工作流节点的执行序号。
   *
   * <p>这不是重试计数，而是用于区分同一个工作流实例下， 同一节点的多次执行记录。
   */
  private Integer runSeq;

  private String nodeStatus;

  /** 节点执行生命周期内的重试次数。 */
  private Integer retryCount;

  private String errorCode;

  private Instant startedAt;
  private Instant finishedAt;
  private Long durationMs;

  /**
   * ADR-009 Stage 1.2: 节点产出 JSON(由 worker 在 SUCCESS 时上报，供下游节点 $.nodes.&lt;X&gt;.output.&lt;key&gt;
   * 引用)。读取时反序列化为 Map。
   */
  private String output;

  @Override
  public String getStatus() {
    return nodeStatus;
  }
}
