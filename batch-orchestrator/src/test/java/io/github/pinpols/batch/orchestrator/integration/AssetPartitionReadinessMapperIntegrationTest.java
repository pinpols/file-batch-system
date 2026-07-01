package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionMaterializationCommand;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionSnapshot;
import io.github.pinpols.batch.orchestrator.mapper.AssetPartitionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V189 readiness "latest-version" 守卫的真实 SQL 回归护栏（原来只有 mocked-mapper 单测覆盖）。
 *
 * <p>护栏两处硬化，都在 {@code AssetPartitionMapper.xml}：
 *
 * <ul>
 *   <li>{@code selectEffectiveJobPartition} —— 除了 {@code rv.status='EFFECTIVE'} 还要求 <b>不存在同
 *       business_key 的更高 version_no</b>（{@code not exists(... newer.version_no > rv.version_no)}）。
 *       缺这个子查询，已物化的旧 EFFECTIVE 会在更新版本 PENDING 时仍被下游消费——结算级事故。
 *   <li>{@code upsertEffectiveJobPartition} —— {@code on conflict do update} 带 {@code where
 *       exists(... next_rv.version_no >= current_rv.version_no ...)} 版本单调守卫， 阻止一次引用更旧版本的迟到 upsert
 *       把指针回退。
 * </ul>
 *
 * <p>把这两处 SQL 硬化 revert 掉，本类三个方法中至少一个会转红。
 *
 * <p>不覆盖：物化刷新 service 编排 / freshness policy（V187）/ 归档（留给上层套件）。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AssetPartitionReadinessMapperIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "ta";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 5, 4);
  private static final String PARTITION_KEY = "2026-05-04";
  // result_version.job_instance_id 是 NOT NULL 但 V108..V189 均无 FK；用固定桩值即可。
  private static final long STUB_JOB_INSTANCE_ID = 1L;

  @Autowired private AssetPartitionMapper assetPartitionMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void
      selectEffectiveJobPartition_returnsEmpty_whenNewerVersionPendingSupersedesMaterializedEffective() {
    // arrange
    String assetCode = "READINESS_EMPTY";
    String businessKey = "job:" + assetCode + ":" + BIZ_DATE;
    long assetId = ensureAsset(assetCode);
    long v1 = insertResultVersion(businessKey, 1, "EFFECTIVE");
    // 物化指针指向 v1（首次插入无冲突，无条件写入）
    materialize(assetId, assetCode, v1, businessKey);
    // 出现更新的 v2（PENDING）——同 business_key，version_no 2 > 1
    insertResultVersion(businessKey, 2, "PENDING");

    // act
    AssetPartitionSnapshot snapshot =
        assetPartitionMapper.selectEffectiveJobPartition(TENANT, assetCode, PARTITION_KEY);

    // assert：latest-version 子查询把旧 EFFECTIVE 排除，readiness 返回空
    // revert 掉 not-exists 子查询 → 会返回 v1 → 此断言转红
    assertThat(snapshot).isNull();
  }

  @Test
  void selectEffectiveJobPartition_returnsPartition_whenMaterializedVersionIsLatestEffective() {
    // arrange：只有 v1 EFFECTIVE，没有更新版本 —— 正向对照，证明本测不是恒空
    String assetCode = "READINESS_POSITIVE";
    String businessKey = "job:" + assetCode + ":" + BIZ_DATE;
    long assetId = ensureAsset(assetCode);
    long v1 = insertResultVersion(businessKey, 1, "EFFECTIVE");
    materialize(assetId, assetCode, v1, businessKey);

    // act
    AssetPartitionSnapshot snapshot =
        assetPartitionMapper.selectEffectiveJobPartition(TENANT, assetCode, PARTITION_KEY);

    // assert
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.tenantId()).isEqualTo(TENANT);
    assertThat(snapshot.assetCode()).isEqualTo(assetCode);
    assertThat(snapshot.partitionKey()).isEqualTo(PARTITION_KEY);
    assertThat(snapshot.businessKey()).isEqualTo(businessKey);
    assertThat(snapshot.freshnessStatus()).isEqualTo("EFFECTIVE");
    assertThat(snapshot.versionNo()).isEqualTo(1);
  }

  @Test
  void upsertEffectiveJobPartition_doesNotRegressPointer_whenNewerUpsertReferencesOlderVersion() {
    // arrange
    String assetCode = "READINESS_MONOTONIC";
    String businessKey = "job:" + assetCode + ":" + BIZ_DATE;
    long assetId = ensureAsset(assetCode);
    // 指针当前指向 version_no 2。注意:因 uk_result_version_effective (partial unique,
    // 同 business_key 至多 1 EFFECTIVE) 无法让 v2 与更旧的 v1 同时 EFFECTIVE，故当前指针目标 v2
    // 置 SUPERSEDED —— 守卫只读 current_rv 的 business_key + version_no，不读其 status，
    // 因此这精确隔离并触发 version_no 单调分支。
    long v2 = insertResultVersion(businessKey, 2, "SUPERSEDED");
    materialize(assetId, assetCode, v2, businessKey);
    assertThat(pointerVersionId(assetCode)).isEqualTo(v2);

    // act：一次引用更旧版本 v1（EFFECTIVE，version_no 1）的迟到 upsert
    long v1 = insertResultVersion(businessKey, 1, "EFFECTIVE");
    assetPartitionMapper.upsertEffectiveJobPartition(command(assetId, assetCode, v1, businessKey));

    // assert：单调守卫（1 >= 2 为假）拦下 do-update，指针仍指向 v2
    // revert 掉 where-exists 守卫 → do-update 无条件执行 → 指针回退到 v1 → 此断言转红
    assertThat(pointerVersionId(assetCode)).isEqualTo(v2);
  }

  // ---- helpers ----

  private long ensureAsset(String assetCode) {
    assetPartitionMapper.upsertDataAsset(TENANT, assetCode, "JOB", assetCode, assetCode);
    Long id = assetPartitionMapper.selectDataAssetId(TENANT, assetCode, "JOB");
    assertThat(id).isNotNull();
    return id;
  }

  private long insertResultVersion(String businessKey, int versionNo, String status) {
    Long id =
        jdbcTemplate.queryForObject(
            "insert into batch.result_version (tenant_id, business_key, version_no,"
                + " job_instance_id, status, effective_at, payload_storage, payload_json,"
                + " generated_at, generated_by, promotion_policy) values (?, ?, ?, ?, ?,"
                + " case when ? = 'EFFECTIVE' then current_timestamp else null end,"
                + " 'INLINE_JSON', '{\"recordCount\":100}'::jsonb, current_timestamp, 'it',"
                + " 'AUTO_LATEST') returning id",
            Long.class,
            TENANT,
            businessKey,
            versionNo,
            STUB_JOB_INSTANCE_ID,
            status,
            status);
    assertThat(id).isNotNull();
    return id;
  }

  private void materialize(
      long assetId, String assetCode, long resultVersionId, String businessKey) {
    assetPartitionMapper.upsertEffectiveJobPartition(
        command(assetId, assetCode, resultVersionId, businessKey));
  }

  private AssetPartitionMaterializationCommand command(
      long assetId, String assetCode, long resultVersionId, String businessKey) {
    return new AssetPartitionMaterializationCommand(
        TENANT,
        assetId,
        assetCode,
        PARTITION_KEY,
        BIZ_DATE,
        resultVersionId,
        businessKey,
        STUB_JOB_INSTANCE_ID,
        Instant.parse("2026-05-04T10:00:00Z"),
        "INLINE_JSON",
        null);
  }

  private Long pointerVersionId(String assetCode) {
    return jdbcTemplate.queryForObject(
        "select result_version_id from batch.asset_partition where tenant_id = ? and asset_code = ?"
            + " and partition_key = ?",
        Long.class,
        TENANT,
        assetCode,
        PARTITION_KEY);
  }
}
