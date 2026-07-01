package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.lineage.LineageEvidenceService;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #744/#745 lineage 证据链的 ARCHIVE 回退真实 PG 护栏（原来只有 mocked-mapper 单测覆盖）。
 *
 * <p>覆盖 {@link LineageEvidenceService#evidenceForResultVersion} 里「热表缺失 → selectArchived*」的 分支拼接，以及
 * {@code LineageEvidenceMapper.xml} 里 {@code selectArchived*} 四条查询对 {@code archive.*_archive} 冷表（含
 * V188 新增 {@code file_record_archive}）的 join + 租户过滤。
 *
 * <ul>
 *   <li>{@code evidenceFallsBackToArchive_whenLiveRowsAbsent} —— result_version 活在热表，但它引用的
 *       job_instance / pipeline_instance / file_record / file_dispatch_record 只存在于 archive.* 冷表 （热表
 *       batch.* 全无）。驱动 SERVICE 方法，断言证据链完整重建且 coverage 的每一段 source 都标 ARCHIVE、
 *       scope=BFS_HOT_AND_ARCHIVE、file/dispatch 非空。
 *   <li>{@code archiveEvidenceIsTenantScoped} —— 冷表里为租户 A、B 各种一条链；断言四条 selectArchived* 只返回本租户行：跨租户
 *       id / 跨租户 payloadFileId / 跨租户 fileId 都不泄漏。
 * </ul>
 *
 * <p>archive.*_archive 经 {@code LIKE ... INCLUDING CONSTRAINTS} 从热表镜像（V71 / V188），不带 FK，
 * 故可直接种孤立冷表行；PK 为单列 id（V71/V188 手工加），因此租户隔离测试用序列生成的不同 id + 跨租户查询证明 outer {@code tenant_id =
 * #{tenantId}} 过滤，而非依赖同 id 冲突。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LineageEvidenceArchiveFallbackIntegrationTest extends AbstractIntegrationTest {

  @Autowired private LineageEvidenceService lineageEvidenceService;
  @Autowired private LineageEvidenceMapper lineageEvidenceMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @SuppressWarnings("unchecked")
  void evidenceFallsBackToArchive_whenLiveRowsAbsent() {
    // arrange: 整条链只种在 archive.* 冷表；热表 batch.* 除 result_version 外一无所有
    String tenant = unique("tenant");
    long fileId = insertArchivedFileRecord(tenant);
    long instanceId = insertArchivedJobInstance(tenant, fileId);
    long pipelineId = insertArchivedPipelineInstance(tenant, instanceId, fileId);
    insertArchivedDispatchRecord(tenant, fileId, pipelineId);
    // result_version 活在热表，payload_ref 指向 file_record:<fileId>
    long resultVersionId = insertLiveResultVersion(tenant, instanceId, "file_record:" + fileId);

    // act: 驱动 SERVICE 方法，走热表 miss → selectArchived* 回退分支
    Map<String, Object> evidence =
        lineageEvidenceService.evidenceForResultVersion(tenant, resultVersionId);

    // assert: 证据链从 archive 完整重建
    Map<String, Object> jobInstance = (Map<String, Object>) evidence.get("jobInstance");
    assertThat(jobInstance).isNotNull();
    assertThat(longValue(jobInstance.get("id"))).isEqualTo(instanceId);

    List<Map<String, Object>> pipelineInstances =
        (List<Map<String, Object>>) evidence.get("pipelineInstances");
    assertThat(pipelineInstances).hasSize(1);
    assertThat(longValue(pipelineInstances.get(0).get("id"))).isEqualTo(pipelineId);

    List<Map<String, Object>> fileRecords = (List<Map<String, Object>>) evidence.get("fileRecords");
    assertThat(fileRecords).isNotEmpty();
    assertThat(fileRecords).anyMatch(r -> fileId == longValue(r.get("id")));

    List<Map<String, Object>> dispatchRecords =
        (List<Map<String, Object>>) evidence.get("dispatchRecords");
    assertThat(dispatchRecords).isNotEmpty();

    // coverage 标记：每一段都来自 ARCHIVE，scope 反映混合冷表来源
    Map<String, Object> coverage = (Map<String, Object>) evidence.get("coverage");
    assertThat(coverage.get("scope")).isEqualTo("BFS_HOT_AND_ARCHIVE");
    assertThat(coverage.get("jobInstanceFound")).isEqualTo(Boolean.TRUE);
    assertThat(coverage.get("payloadFileResolved")).isEqualTo(Boolean.TRUE);
    Map<String, Object> sources = (Map<String, Object>) coverage.get("sources");
    assertThat(sources.get("resultVersion")).isEqualTo("HOT");
    assertThat(sources.get("jobInstance")).isEqualTo("ARCHIVE");
    assertThat(sources.get("pipelineInstances")).isEqualTo("ARCHIVE");
    assertThat(sources.get("fileRecords")).isEqualTo("ARCHIVE");
    assertThat(sources.get("dispatchRecords")).isEqualTo("ARCHIVE");
  }

  @Test
  void archiveEvidenceIsTenantScoped() {
    // arrange: 租户 A、B 各种一条完整冷表链（id 各自由序列生成，天然不同）
    String tenantA = unique("tenant-a");
    String tenantB = unique("tenant-b");

    long fileA = insertArchivedFileRecord(tenantA);
    long instanceA = insertArchivedJobInstance(tenantA, fileA);
    insertArchivedPipelineInstance(tenantA, instanceA, fileA);
    insertArchivedDispatchRecord(tenantA, fileA, null);

    long fileB = insertArchivedFileRecord(tenantB);
    long instanceB = insertArchivedJobInstance(tenantB, fileB);
    insertArchivedPipelineInstance(tenantB, instanceB, fileB);
    insertArchivedDispatchRecord(tenantB, fileB, null);

    // job_instance: A 看不到 B 的实例；B 自己能看到
    assertThat(lineageEvidenceMapper.selectArchivedJobInstance(tenantA, instanceB)).isNull();
    Map<String, Object> bInstance =
        lineageEvidenceMapper.selectArchivedJobInstance(tenantB, instanceB);
    assertThat(bInstance).isNotNull();
    assertThat(bInstance.get("tenant_id")).isEqualTo(tenantB);

    // pipeline_instance: 用 B 的 jobInstanceId 查 A → 空（无 tenant 过滤则会漏 B 的 pipeline）
    assertThat(lineageEvidenceMapper.selectArchivedPipelineInstances(tenantA, instanceB)).isEmpty();
    assertThat(lineageEvidenceMapper.selectArchivedPipelineInstances(tenantB, instanceB))
        .hasSize(1);

    // file_record: 即便把 B 的 fileId 当作 A 的 payloadFileId 传入，outer tenant 过滤也不放行 B 的文件
    List<Map<String, Object>> filesForA =
        lineageEvidenceMapper.selectArchivedFileRecords(tenantA, instanceA, fileB);
    assertThat(filesForA).isNotEmpty();
    assertThat(filesForA).allMatch(r -> tenantA.equals(r.get("tenant_id")));
    assertThat(filesForA).noneMatch(r -> fileB == longValue(r.get("id")));

    // dispatch_record: 跨租户 jobInstanceId + 跨租户 fileId 双路径都不泄漏
    assertThat(
            lineageEvidenceMapper.selectArchivedDispatchRecords(tenantA, instanceB, List.of(fileB)))
        .isEmpty();
    List<Map<String, Object>> dispatchForB =
        lineageEvidenceMapper.selectArchivedDispatchRecords(tenantB, instanceB, List.of(fileB));
    assertThat(dispatchForB).hasSize(1);
    assertThat(dispatchForB.get(0).get("tenant_id")).isEqualTo(tenantB);
  }

  // ---- archive.* seed helpers (冷表无 FK，可种孤立行；id 走热表序列默认) ----

  private long insertArchivedFileRecord(String tenant) {
    return jdbcTemplate.queryForObject(
        """
        insert into archive.file_record_archive(
          tenant_id, file_code, biz_type, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'GL', 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SYSTEM', 'LOADED',
                  current_date, ?)
        returning id
        """,
        Long.class,
        tenant,
        unique("fc"),
        unique("file") + ".csv",
        "bucket/" + unique("path"),
        unique("trace"));
  }

  private long insertArchivedJobInstance(String tenant, long relatedFileId) {
    return jdbcTemplate.queryForObject(
        """
        insert into archive.job_instance_archive(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, related_file_id, finished_at
        ) values (?, 1, 'LINEAGE_JOB', ?, current_date, 'MANUAL', 'SUCCESS', 5, ?, 1, 1, 0, ?, ?,
                  now())
        returning id
        """,
        Long.class,
        tenant,
        unique("inst"),
        unique("dedup"),
        unique("trace"),
        relatedFileId);
  }

  private long insertArchivedPipelineInstance(String tenant, long jobInstanceId, long fileId) {
    return jdbcTemplate.queryForObject(
        """
        insert into archive.pipeline_instance_archive(
          tenant_id, pipeline_definition_id, job_code, pipeline_type, file_id,
          related_job_instance_id, run_status, trace_id, finished_at
        ) values (?, 1, 'LINEAGE_JOB', 'IMPORT', ?, ?, 'SUCCESS', ?, now())
        returning id
        """,
        Long.class,
        tenant,
        fileId,
        jobInstanceId,
        unique("trace"));
  }

  private long insertArchivedDispatchRecord(String tenant, long fileId, Long pipelineInstanceId) {
    return jdbcTemplate.queryForObject(
        """
        insert into archive.file_dispatch_record_archive(
          tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target,
          dispatch_status, dispatch_attempt, receipt_status, dispatched_at
        ) values (?, ?, ?, 'CH_MAIN', 'sftp://target', 'ACKED', 1, 'SUCCESS', now())
        returning id
        """,
        Long.class,
        tenant,
        fileId,
        pipelineInstanceId);
  }

  private long insertLiveResultVersion(String tenant, long jobInstanceId, String payloadRef) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.result_version(
          tenant_id, business_key, version_no, job_instance_id, status, effective_at,
          payload_storage, payload_ref, generated_at, generated_by, promotion_policy
        ) values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp, 'FILE_RECORD', ?,
                  current_timestamp, 'it', 'AUTO_LATEST')
        returning id
        """,
        Long.class,
        tenant,
        unique("bizkey"),
        jobInstanceId,
        payloadRef);
  }

  private static long longValue(Object value) {
    return ((Number) value).longValue();
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
