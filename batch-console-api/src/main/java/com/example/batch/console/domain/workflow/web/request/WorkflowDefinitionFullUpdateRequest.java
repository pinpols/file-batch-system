package com.example.batch.console.domain.workflow.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Workflow DAG designer 全量替换请求(BE Spike,docs/design/workflow-dag-designer.md)。
 *
 * <p>语义:画布 Save 一次性提交完整的 (definition, nodes, edges) 三元组,服务端同事务删旧 node/edge + 插新 + version 自增, 不做增量
 * diff。
 *
 * <ul>
 *   <li>{@link #definition}:workflow 定义字段(workflowName / workflowType / enabled,workflowCode 不可改 —
 *       见 service 校验)
 *   <li>{@link #nodes} / {@link #edges}:嵌套 @Valid 沿用 {@link WorkflowDefinitionSaveRequest} 的内置
 *       NodeItem / EdgeItem
 *   <li>{@link #expectedVersion}:乐观锁,等于当前 version 才允许覆盖(防止两个 tab 互覆盖);客户端从最近 GET 拿
 *   <li>{@link #lockToken}:不传时由 service 用 SecurityContext 当前 username 校验锁归属;预留给跨 tab 场景
 * </ul>
 */
@Data
public class WorkflowDefinitionFullUpdateRequest {

  @NotNull @Valid private WorkflowDefinitionSaveRequest definition;

  /** 客户端最近一次 GET 拿到的 version,服务端用它做乐观锁检查;不传则跳过版本冲突校验(便于工具脚本)。 */
  private Integer expectedVersion;

  /** 预留 lockToken;Spike 阶段以 SecurityContext.username 为权威。 */
  private String lockToken;
}
