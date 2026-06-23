package io.github.pinpols.batch.console.domain.ops.entity;

import java.time.Instant;
import lombok.Data;

/**
 * console-api 只读投影:{@code batch.job_task} 的心跳进度子集(SDK Phase 4 / ORCH-P4-1,V161 列)。
 *
 * <p>{@code heartbeatDetails} 为 JSONB,mapper 端 {@code ::text} 读出为字符串,service 再解析成 JSON 透传 FE。读侧实体,
 * console-api 走读写分离只读路径,不回写 job_task(状态主机是 orchestrator)。
 */
@Data
public class JobTaskHeartbeatEntity {

  private Long id;
  private String tenantId;
  private String taskStatus;
  private String heartbeatDetails;
  private Instant heartbeatAt;
  private Boolean cancelRequested;
}
