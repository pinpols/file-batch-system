package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

// #8-1: 实现 Stateful 接口，消除 DefaultStateMachine 中的反射兜底路径
@Data
public class WorkflowNodeRunEntity implements Stateful {

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
  private String errorMessage;

  /** i18n message key,V77+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组,与 errorKey 一起支持历史日志按 Locale 重渲染。 */
  private String errorArgs;

  private Instant startedAt;
  private Instant finishedAt;
  private Long durationMs;

  @Override
  public String getStatus() {
    return nodeStatus;
  }
}
