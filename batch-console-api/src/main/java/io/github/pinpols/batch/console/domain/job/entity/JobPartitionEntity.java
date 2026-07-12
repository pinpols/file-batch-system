package io.github.pinpols.batch.console.domain.job.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class JobPartitionEntity {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Integer partitionNo;
  private String partitionKey;
  private String partitionStatus;
  private String workerGroup;
  private String workerCode;
  private Integer retryCount;
  private String businessKey;
  private Instant leaseExpireAt;
  private Instant startedAt;
  private Instant finishedAt;

  /** 缺口3:FAILED 分区最近一次失败 task 的错误码(JOIN job_task 透出,不落分区表列)。 */
  private String errorCode;

  /** 缺口3:FAILED 分区最近一次失败 task 的错误消息(JOIN job_task 透出)。 */
  private String errorMessage;

  /** ADR-026 dry-run 演练标记。 */
  private Boolean dryRun;

  /** ADR-046 per-file 绑定:源文件 id（查询端可选用）。 */
  private Long sourceFileId;

  /** ADR-046 per-file 绑定:文件模板 code。 */
  private String templateCode;

  /** ADR-046 per-file 绑定:目标引用。 */
  private String targetRef;
}
