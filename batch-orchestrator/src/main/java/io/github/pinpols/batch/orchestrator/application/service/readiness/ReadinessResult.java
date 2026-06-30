package io.github.pinpols.batch.orchestrator.application.service.readiness;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionSnapshot;
import java.time.LocalDate;

/**
 * 上游就绪查询结果(ADR-043 依赖感知 fire)。
 *
 * @param ready 上游是否就绪
 * @param reason 未就绪原因码(就绪时为 null),供调用方日志/决策
 * @param assetCode JOB asset 编码
 * @param bizDate 批次日
 * @param partitionKey asset partition key
 * @param businessKey result_version 业务主键
 * @param freshnessStatus 当前新鲜度状态
 * @param versionNo 当前 EFFECTIVE result_version.version_no
 * @param jobInstanceId 产出该 EFFECTIVE 版本的 job_instance_id
 * @param payloadStorage 结果载荷存储类型
 * @param payloadRef 外部载荷引用
 */
public record ReadinessResult(
    boolean ready,
    String reason,
    String assetCode,
    LocalDate bizDate,
    String partitionKey,
    String businessKey,
    String freshnessStatus,
    Integer versionNo,
    Long jobInstanceId,
    String payloadStorage,
    String payloadRef) {

  public static ReadinessResult ofReady() {
    return new ReadinessResult(true, null, null, null, null, null, null, null, null, null, null);
  }

  public static ReadinessResult ofReady(AssetPartitionSnapshot partition) {
    if (partition == null) {
      return ofReady();
    }
    return new ReadinessResult(
        true,
        null,
        partition.assetCode(),
        partition.bizDate(),
        partition.partitionKey(),
        partition.businessKey(),
        partition.freshnessStatus(),
        partition.versionNo(),
        partition.jobInstanceId(),
        partition.payloadStorage(),
        partition.payloadRef());
  }

  public static ReadinessResult ofNotReady(String reason) {
    return new ReadinessResult(false, reason, null, null, null, null, null, null, null, null, null);
  }

  public static ReadinessResult ofNotReady(String reason, String assetCode, LocalDate bizDate) {
    return new ReadinessResult(
        false,
        reason,
        assetCode,
        bizDate,
        bizDate == null ? null : bizDate.toString(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
