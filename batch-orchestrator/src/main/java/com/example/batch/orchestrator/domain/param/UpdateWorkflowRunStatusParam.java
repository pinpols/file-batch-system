package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import java.util.Collection;
import lombok.Builder;
import lombok.Getter;

/**
 * workflow_run 状态更新参数（含期望前态守护）。
 *
 * <p>{@code expectedStatuses} 为可选 SQL 守护列表：非空时 UPDATE 仅在当前 run_status 命中列表内才生效， 防止 cancel/terminate
 * 与 task outcome 间的覆写 race（例如运维 cancel 后正在 RUNNING 的回报落地把 TERMINATED 覆写成 SUCCESS/FAILED）。null
 * 或空集合表示不加守护，行为等同旧的无条件更新。
 */
@Getter
@Builder
public class UpdateWorkflowRunStatusParam {
  private final String tenantId;
  private final Long id;
  private final String runStatus;
  private final String currentNodeCode;
  private final Instant finishedAt;
  private final Collection<String> expectedStatuses;
}
