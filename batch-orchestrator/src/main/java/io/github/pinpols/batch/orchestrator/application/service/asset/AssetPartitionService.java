package io.github.pinpols.batch.orchestrator.application.service.asset;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * asset partition 最小查询入口。
 *
 * <p>P0-3 第一刀只把 BFS 已有的 {@code result_version} 生效链规范化为 asset partition，不复制状态、不新增缓存。 这样
 * readiness、跨日依赖和后续 Console 查询可以统一消费同一口径：只有当前 EFFECTIVE 版本才代表 partition ready。
 */
@Service
@RequiredArgsConstructor
public class AssetPartitionService {

  private static final String FRESHNESS_EFFECTIVE = "EFFECTIVE";

  private final ResultVersionQueryService resultVersionQueryService;

  public Optional<AssetPartitionSnapshot> findEffectiveJobPartition(
      String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return Optional.empty();
    }
    return resultVersionQueryService
        .findEffectiveByJob(tenantId, jobCode, bizDate)
        .map(row -> toJobPartition(tenantId, jobCode, bizDate, row));
  }

  public boolean isJobPartitionReady(String tenantId, String jobCode, LocalDate bizDate) {
    return findEffectiveJobPartition(tenantId, jobCode, bizDate).isPresent();
  }

  private AssetPartitionSnapshot toJobPartition(
      String tenantId, String jobCode, LocalDate bizDate, ResultVersionEntity row) {
    return new AssetPartitionSnapshot(
        tenantId,
        jobCode,
        bizDate,
        bizDate.toString(),
        row.businessKey(),
        FRESHNESS_EFFECTIVE,
        row.versionNo(),
        row.jobInstanceId(),
        row.payloadStorage(),
        row.payloadJson(),
        row.payloadRef());
  }
}
