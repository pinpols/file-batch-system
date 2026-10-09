package io.github.pinpols.batch.console.domain.ops.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkerRegistryEntity {

  private Long id;
  private String tenantId;
  private String workerCode;
  private String workerGroup;
  /** Worker 实际监听 HTTP 端口（V221）；NULL=未上报（老 worker / 老 SDK / 非 web 上下文）。 */
  private Integer port;

  private String capabilityTagsJson;
  private String resourceTag;
  private String taskCapabilitiesJson;

  private String status;
  private Instant heartbeatAt;
  private Integer currentLoad;
  private Integer maxConcurrent;
  private Instant drainStartedAt;
  private Instant drainDeadlineAt;
}
