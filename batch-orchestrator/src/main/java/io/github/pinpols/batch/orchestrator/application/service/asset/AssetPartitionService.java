package io.github.pinpols.batch.orchestrator.application.service.asset;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.AssetPartitionMapper;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * asset partition 最小查询入口。
 *
 * <p>P0-3 第二刀把 BFS 已有的 {@code result_version} 生效链物化为最小 asset partition 读模型。{@code result_version}
 * 仍是权威版本链；这里的物化表只服务 readiness / console 查询，不扩展成企业数据目录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetPartitionService {

  private static final String FRESHNESS_EFFECTIVE = "EFFECTIVE";
  private static final String ASSET_TYPE_JOB = "JOB";

  private final AssetPartitionMapper assetPartitionMapper;
  private final ResultVersionQueryService resultVersionQueryService;

  public Optional<AssetPartitionSnapshot> findEffectiveJobPartition(
      String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return Optional.empty();
    }
    Optional<ResultVersionEntity> latest =
        resultVersionQueryService.findLatestByJob(tenantId, jobCode, bizDate);
    if (latest.isPresent() && !FRESHNESS_EFFECTIVE.equals(latest.get().status())) {
      return Optional.empty();
    }
    AssetPartitionSnapshot materialized =
        assetPartitionMapper.selectEffectiveJobPartition(
            tenantId, jobCode, toPartitionKey(bizDate));
    if (materialized != null && latest.isEmpty()) {
      return Optional.of(materialized);
    }
    if (materialized != null
        && Objects.equals(latest.get().versionNo(), materialized.versionNo())) {
      return Optional.of(materialized);
    }
    return latest
        .filter(row -> FRESHNESS_EFFECTIVE.equals(row.status()))
        .map(row -> toJobPartition(tenantId, jobCode, bizDate, row));
  }

  public boolean isJobPartitionReady(String tenantId, String jobCode, LocalDate bizDate) {
    return findEffectiveJobPartition(tenantId, jobCode, bizDate).isPresent();
  }

  public void materializeEffectiveJobPartition(
      JobInstanceEntity instance, ResultVersionEntity version) {
    if (!isMaterializable(instance, version)) {
      return;
    }
    String tenantId = instance.getTenantId();
    String jobCode = instance.getJobCode();
    assetPartitionMapper.upsertDataAsset(tenantId, jobCode, ASSET_TYPE_JOB, jobCode, jobCode);
    Long assetId = assetPartitionMapper.selectDataAssetId(tenantId, jobCode, ASSET_TYPE_JOB);
    if (assetId == null) {
      log.warn(
          "asset partition materialization skipped: data_asset missing after upsert,"
              + " tenantId={}, jobCode={}, jobInstanceId={}",
          tenantId,
          jobCode,
          instance.getId());
      return;
    }
    assetPartitionMapper.upsertEffectiveJobPartition(
        new AssetPartitionMaterializationCommand(
            tenantId,
            assetId,
            jobCode,
            toPartitionKey(instance.getBizDate()),
            instance.getBizDate(),
            version.id(),
            version.businessKey(),
            version.jobInstanceId(),
            version.effectiveAt(),
            version.payloadStorage(),
            version.payloadRef()));
  }

  private boolean isMaterializable(JobInstanceEntity instance, ResultVersionEntity version) {
    return instance != null
        && version != null
        && Texts.hasText(instance.getTenantId())
        && Texts.hasText(instance.getJobCode())
        && instance.getBizDate() != null
        && instance.getId() != null
        && FRESHNESS_EFFECTIVE.equals(version.status())
        && instance.getTenantId().equals(version.tenantId())
        && instance.getId().equals(version.jobInstanceId())
        && Texts.hasText(version.businessKey());
  }

  private AssetPartitionSnapshot toJobPartition(
      String tenantId, String jobCode, LocalDate bizDate, ResultVersionEntity row) {
    return new AssetPartitionSnapshot(
        tenantId,
        jobCode,
        bizDate,
        toPartitionKey(bizDate),
        row.businessKey(),
        FRESHNESS_EFFECTIVE,
        row.versionNo(),
        row.jobInstanceId(),
        row.payloadStorage(),
        row.payloadJson(),
        row.payloadRef());
  }

  private String toPartitionKey(LocalDate bizDate) {
    return bizDate.toString();
  }
}
