package io.github.pinpols.batch.orchestrator.application.service.readiness;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 上游就绪查询服务(ADR-043 依赖感知 fire)。
 *
 * <p>orchestrator 是唯一状态主机,就绪判定基于其权威 result_version EFFECTIVE 链。
 *
 * <p>trigger fire 前经 /internal/readiness 只读调本服务,不直连状态表。
 *
 * <p>v1 只支持「上游 JOB 该批次日产出的 asset partition 已 EFFECTIVE」,FILE_GROUP 就绪留作后续扩展。
 */
@Service
@RequiredArgsConstructor
public class ReadinessService {

  private final AssetPartitionService assetPartitionService;

  /**
   * 上游 job 在指定批次日是否已就绪(**当前 asset partition 有 EFFECTIVE result_version**)。
   *
   * <p>口径不再直接读 job_instance：只有 result_version EFFECTIVE 代表可被下游消费。DQ BLOCKED 的 PENDING、
   * dry-run、失败/部分失败且未生效的 attempt 都不会放行。
   *
   * @param tenantId 租户
   * @param jobCode 上游 job code
   * @param bizDate 对齐后的批次日
   */
  public ReadinessResult checkJobReady(String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return ReadinessResult.ofNotReady("invalid-readiness-query");
    }
    return assetPartitionService.isJobPartitionReady(tenantId, jobCode, bizDate)
        ? ReadinessResult.ofReady()
        : ReadinessResult.ofNotReady("asset-partition-not-effective");
  }
}
