package io.github.pinpols.batch.orchestrator.application.service.asset;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.DataAssetUpsertParam;
import io.github.pinpols.batch.orchestrator.mapper.AssetPartitionMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private static final long DATA_ASSET_ID_CACHE_MAX_SIZE = 100_000L;
  private static final Duration DATA_ASSET_ID_CACHE_TTL = Duration.ofHours(1);

  private final AssetPartitionMapper assetPartitionMapper;
  private final ResultVersionQueryService resultVersionQueryService;

  /**
   * {@code data_asset.id} 由唯一键生成后不可变，仓库也没有删除该主数据的运行时路径。终态写入会反复解析同一
   * tenant/job 的 id；使用有界、带过期时间的进程内缓存，避免在 result_version 业务键锁内每次执行
   * upsert + select。缓存只发布已提交的身份；未提交或回滚的 ID 不能被其他 REPORT 事务引用。
   * 多 Orchestrator 各自首次加载，数据库唯一键仍是最终一致性守卫。
   */
  private final Cache<DataAssetKey, Long> dataAssetIdCache = Caffeine.newBuilder()
      .maximumSize(DATA_ASSET_ID_CACHE_MAX_SIZE)
      .expireAfterAccess(DATA_ASSET_ID_CACHE_TTL)
      .build();

  public Optional<AssetPartitionSnapshot> findEffectiveJobPartition(
      String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return Optional.empty();
    }
    Optional<ResultVersionEntity> latest =
        resultVersionQueryService.findLatestByJob(tenantId, jobCode, bizDate);
    boolean latestVersionIsNotEffective =
        latest.isPresent() && !FRESHNESS_EFFECTIVE.equals(latest.get().status());
    if (latestVersionIsNotEffective) {
      return Optional.empty();
    }
    AssetPartitionSnapshot materialized = assetPartitionMapper.selectEffectiveJobPartition(
        tenantId, jobCode, toPartitionKey(bizDate));
    if (materialized != null && latest.isEmpty()) {
      return Optional.of(materialized);
    }
    boolean materializedMatchesLatestVersion = EmptyChecks.isNotNull(materialized)
        && Objects.equals(latest.get().id(), materialized.resultVersionId());
    if (materializedMatchesLatestVersion) {
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
    Long assetId = resolveDataAssetId(new DataAssetKey(tenantId, jobCode, ASSET_TYPE_JOB));
    if (EmptyChecks.isNull(assetId)) {
      log.warn(
          "asset partition materialization skipped: data_asset missing after upsert,"
              + " tenantId={}, jobCode={}, jobInstanceId={}",
          tenantId,
          jobCode,
          instance.getId());
      return;
    }
    LocalDate partitionBizDate = instance.getBizDate();
    String partitionKey = toPartitionKey(partitionBizDate);
    LocalDate materializationBizDate = instance.getBizDate();
    AssetPartitionMaterializationCommand materializationCommand =
        AssetPartitionMaterializationCommand.builder()
            .tenantId(tenantId)
            .assetId(assetId)
            .assetCode(jobCode)
            .partitionKey(partitionKey)
            .bizDate(materializationBizDate)
            .resultVersionId(version.id())
            .businessKey(version.businessKey())
            .jobInstanceId(version.jobInstanceId())
            .effectiveAt(version.effectiveAt())
            .payloadStorage(version.payloadStorage())
            .payloadRef(version.payloadRef())
            .build();
    assetPartitionMapper.upsertEffectiveJobPartition(materializationCommand);
  }

  private Long resolveDataAssetId(DataAssetKey key) {
    Long committedId = dataAssetIdCache.getIfPresent(key);
    if (EmptyChecks.isNotNull(committedId)) {
      return committedId;
    }
    Long assetId = loadDataAssetId(key);
    if (EmptyChecks.isNotNull(assetId)) {
      publishCommittedDataAssetId(key, assetId);
    }
    return assetId;
  }

  private void publishCommittedDataAssetId(DataAssetKey key, Long assetId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      dataAssetIdCache.put(key, assetId);
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        dataAssetIdCache.put(key, assetId);
      }
    });
  }

  private Long loadDataAssetId(DataAssetKey key) {
    DataAssetUpsertParam dataAssetUpsertParam = DataAssetUpsertParam.builder()
        .tenantId(key.tenantId())
        .assetCode(key.assetCode())
        .assetType(key.assetType())
        .displayName(key.assetCode())
        .ownerJobCode(key.assetCode())
        .build();
    assetPartitionMapper.upsertDataAsset(dataAssetUpsertParam);
    return assetPartitionMapper.selectDataAssetId(key.tenantId(), key.assetCode(), key.assetType());
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
    String partitionKey = toPartitionKey(bizDate);
    return new AssetPartitionSnapshot(
        tenantId,
        jobCode,
        bizDate,
        partitionKey,
        row.businessKey(),
        FRESHNESS_EFFECTIVE,
        row.versionNo(),
        row.jobInstanceId(),
        row.effectiveAt(),
        row.payloadStorage(),
        row.payloadJson(),
        row.payloadRef(),
        row.id());
  }

  private String toPartitionKey(LocalDate bizDate) {
    return bizDate.toString();
  }

  private record DataAssetKey(String tenantId, String assetCode, String assetType) {}
}
