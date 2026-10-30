package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.DispatchRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.FileRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.JobInstance;
import io.github.pinpols.batch.orchestrator.application.service.lineage.LineageEvidenceService;
import io.github.pinpols.batch.orchestrator.controller.LineageEvidenceController;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
 *   <li>{@code shouldScopeArchivedEvidenceByTenant} —— 冷表里为租户 A、B 各种一条链；断言四条 selectArchived* 只返回本租户行：跨租户
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
@DisplayName("血缘证据链在热表缺失时的归档回退查询,以及归档证据的租户隔离")
class LineageEvidenceArchiveFallbackIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private LineageEvidenceService lineageEvidenceService;

  @Autowired
  private LineageEvidenceMapper lineageEvidenceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("热表没有对应行时候从归档冷表重建完整证据链,且每段来源都标记为归档")
  void evidenceFallsBackToArchive_whenLiveRowsAbsent() {
    // arrange: 整条链只种在 archive.* 冷表；热表 batch.* 除 result_version 外一无所有
    String tenant = unique("tenant");
    long fileId = insertArchivedFileRecord(tenant);
    String updateArchivedFileMetadataSql = """
        update archive.file_record_archive set metadata_json = ?::jsonb where id = ?
        """;
    jdbcTemplate.update(
        updateArchivedFileMetadataSql, "{\"recordCount\":42,\"source\":\"contract-test\"}", fileId);
    long instanceId = insertArchivedJobInstance(tenant, fileId);
    long pipelineId = insertArchivedPipelineInstance(tenant, instanceId, fileId);
    insertArchivedDispatchRecord(tenant, fileId, pipelineId);
    // result_version 活在热表，payload_ref 指向 file_record:<fileId>
    long resultVersionId = insertLiveResultVersion(tenant, instanceId, "file_record:" + fileId);

    // act: 驱动 SERVICE 方法，走热表 miss → selectArchived* 回退分支
    LineageEvidenceResponse evidence =
        lineageEvidenceService.evidenceForResultVersion(tenant, resultVersionId);

    // assert: 证据链从 archive 完整重建，并保留固定来源字段。
    assertThat(evidence.jobInstance()).isNotNull();
    assertThat(evidence.jobInstance().id()).isEqualTo(instanceId);
    assertThat(evidence.pipelineInstances()).hasSize(1);
    assertThat(evidence.pipelineInstances().get(0).id()).isEqualTo(pipelineId);
    assertThat(evidence.fileRecords()).isNotEmpty().anyMatch(row -> fileId == row.id());
    assertThat(evidence.fileRecords().get(0).metadataJson())
        .containsEntry("recordCount", 42)
        .containsEntry("source", "contract-test");
    assertThat(evidence.dispatchRecords()).isNotEmpty();
    assertThat(evidence.coverage().scope()).isEqualTo("BFS_HOT_AND_ARCHIVE");
    assertThat(evidence.coverage().jobInstanceFound()).isTrue();
    assertThat(evidence.coverage().payloadFileResolved()).isTrue();
    assertThat(evidence.coverage().sources())
        .isEqualTo(new LineageEvidenceResponse.LineageSources(
            "HOT", "ARCHIVE", "ARCHIVE", "ARCHIVE", "ARCHIVE"));
  }

  @Test
  @DisplayName("载荷文件在热表时仍应收集同一作业关联的归档文件")
  void shouldCombineHotPayloadAndArchivedRelatedFiles() {
    final String insertFileRecordSql1 = """
        insert into batch.file_record (
          tenant_id, file_code, biz_type, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'GL', 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SYSTEM', 'LOADED',
                  current_date, ?) returning id
        """;
    String tenant = unique("mixed-tenant");
    long archivedFileId = insertArchivedFileRecord(tenant);
    long instanceId = insertArchivedJobInstance(tenant, archivedFileId);
    insertArchivedPipelineInstance(tenant, instanceId, archivedFileId);
    long hotFileId = jdbcTemplate.queryForObject(
        insertFileRecordSql1,
        Long.class,
        tenant,
        unique("fc"),
        unique("file"),
        unique("path"),
        unique("trace"));
    long versionId = insertLiveResultVersion(tenant, instanceId, "file_record:" + hotFileId);

    LineageEvidenceResponse evidence =
        lineageEvidenceService.evidenceForResultVersion(tenant, versionId);

    assertThat(evidence.fileRecords())
        .extracting(FileRecord::id)
        .containsExactlyInAnyOrder(hotFileId, archivedFileId);
    assertThat(evidence.coverage().payloadFileResolved()).isTrue();
    assertThat(evidence.coverage().sources().fileRecords()).isEqualTo("HOT_AND_ARCHIVE");
  }

  @Test
  @DisplayName("归档作业和流水线引用的文件仍在热表时也可回查")
  void shouldFindHotFilesReferencedByArchivedParents() {
    final String insertFileRecordSql2 = """
        insert into batch.file_record (
          tenant_id, file_code, biz_type, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'GL', 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SYSTEM', 'LOADED',
                  current_date, ?) returning id
        """;
    String tenant = unique("archived-parent");
    long payloadFileId = insertArchivedFileRecord(tenant);
    long hotFileId = jdbcTemplate.queryForObject(
        insertFileRecordSql2,
        Long.class,
        tenant,
        unique("fc"),
        unique("file"),
        unique("path"),
        unique("trace"));
    long instanceId = insertArchivedJobInstance(tenant, hotFileId);
    insertArchivedPipelineInstance(tenant, instanceId, hotFileId);
    long versionId = insertLiveResultVersion(tenant, instanceId, "file_record:" + payloadFileId);

    LineageEvidenceResponse evidence =
        lineageEvidenceService.evidenceForResultVersion(tenant, versionId);

    assertThat(evidence.fileRecords())
        .extracting(FileRecord::id)
        .containsExactlyInAnyOrder(hotFileId, payloadFileId);
    assertThat(evidence.coverage().sources().fileRecords()).isEqualTo("HOT_AND_ARCHIVE");
  }

  @Test
  @DisplayName("血缘冷热文件及投递证据不回显凭据")
  void shouldMaskSensitiveLineageEvidenceFromHotAndArchive() throws Exception {
    final String insertHotFileSql = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status, metadata_json)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', ?::jsonb) returning id
        """;
    final String updateArchivedFileSql =
        "update archive.file_record_archive set storage_path = ?, metadata_json = ?::jsonb where tenant_id = ? and id = ?";
    final String insertHotDispatchSql = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, channel_code, dispatch_status, dispatch_target, error_message)
        values (?, ?, 'CH_HOT', 'FAILED', ?, ?) returning id
        """;
    final String updateArchivedDispatchSql =
        "update archive.file_dispatch_record_archive set dispatch_target = ?, error_message = ? where tenant_id = ? and id = ?";
    final String selectArchivedTargetSql =
        "select dispatch_target from archive.file_dispatch_record_archive where tenant_id = ? and id = ?";
    String tenant = unique("lineage-mask");
    String target =
        "https://probe-user:probe-password@invalid.example/path?token=probe-token&view=ok";
    String error = "delivery failed token=probe-error";
    String metadata = JsonUtils.toJson(Map.of(
        "token",
        "probe-meta",
        "nested",
        Map.of("password", "probe-nested", "endpoint", target),
        "urls",
        List.of(target),
        "notes",
        "first line\nsecond line",
        "count",
        7));
    long hotFileId = jdbcTemplate.queryForObject(
        insertHotFileSql, Long.class, tenant, unique("file"), target, metadata);
    long archivedFileId = insertArchivedFileRecord(tenant);
    jdbcTemplate.update(updateArchivedFileSql, target, metadata, tenant, archivedFileId);
    long instanceId = insertArchivedJobInstance(tenant, archivedFileId);
    long archivedDispatchId = insertArchivedDispatchRecord(tenant, hotFileId, null);
    jdbcTemplate.update(updateArchivedDispatchSql, target, error, tenant, archivedDispatchId);
    jdbcTemplate.queryForObject(insertHotDispatchSql, Long.class, tenant, hotFileId, target, error);
    long versionId = insertLiveResultVersion(tenant, instanceId, "file_record:" + hotFileId);

    LineageEvidenceResponse evidence =
        lineageEvidenceService.evidenceForResultVersion(tenant, versionId);

    assertThat(evidence.fileRecords()).hasSize(2);
    assertThat(evidence.dispatchRecords()).hasSize(2);
    assertThat(evidence.fileRecords()).allSatisfy(file -> {
      assertThat(file.metadataJson()).containsEntry("count", 7);
      assertThat(file.metadataJson()).containsEntry("notes", "first line\nsecond line");
    });
    assertThat(JsonUtils.toJson(evidence))
        .doesNotContain(
            "probe-user",
            "probe-password",
            "probe-token",
            "probe-error",
            "probe-meta",
            "probe-nested")
        .contains("view=ok", "count");
    var httpResponse = MockMvcBuilders.standaloneSetup(
            new LineageEvidenceController(lineageEvidenceService))
        .build()
        .perform(get("/internal/orchestrator/lineage/result-versions/{id}", versionId)
            .param("tenantId", tenant))
        .andReturn()
        .getResponse();
    assertThat(httpResponse.getStatus()).isEqualTo(200);
    assertThat(httpResponse.getContentAsString())
        .doesNotContain(
            "probe-user",
            "probe-password",
            "probe-token",
            "probe-error",
            "probe-meta",
            "probe-nested")
        .contains("view=ok", "count");
    assertThat(jdbcTemplate.queryForObject(
            selectArchivedTargetSql, String.class, tenant, archivedDispatchId))
        .isEqualTo(target);
  }

  @Test
  @DisplayName("热表和归档同时有投递记录时返回完整回执")
  void shouldCombineHotAndArchivedDispatchRecords() {
    final String insertFileRecordSql3 = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String insertFileDispatchRecordSql4 = """
        insert into batch.file_dispatch_record (tenant_id, file_id, channel_code, dispatch_status)
        values (?, ?, 'CH_HOT', 'SENT') returning id
        """;
    String tenant = unique("dispatch-mixed");
    long hotFileId = jdbcTemplate.queryForObject(
        insertFileRecordSql3, Long.class, tenant, unique("file"), unique("path"));
    long instanceId = insertArchivedJobInstance(tenant, hotFileId);
    long archivedId = insertArchivedDispatchRecord(tenant, hotFileId, null);
    long hotId =
        jdbcTemplate.queryForObject(insertFileDispatchRecordSql4, Long.class, tenant, hotFileId);
    long versionId = insertLiveResultVersion(tenant, instanceId, "file_record:" + hotFileId);

    LineageEvidenceResponse evidence =
        lineageEvidenceService.evidenceForResultVersion(tenant, versionId);

    assertThat(evidence.dispatchRecords())
        .extracting(DispatchRecord::id)
        .containsExactly(hotId, archivedId);
    assertThat(evidence.coverage().sources().dispatchRecords()).isEqualTo("HOT_AND_ARCHIVE");
  }

  @Test
  @DisplayName("归档投递引用热 Pipeline 时按作业和租户匹配")
  void shouldFindArchivedDispatchByHotPipelineAndRespectTenant() {
    final String insertJobDefinitionSql5 = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy)
        values (?, ?, 'test job', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE') returning id
        """;
    final String insertJobInstanceSql6 = """
        insert into batch.job_instance
          (tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
           instance_status, priority, dedup_key, expected_partition_count,
           success_partition_count, failed_partition_count, trace_id)
        values (?, ?, 'JOB', ?, current_date, 'MANUAL', 'RUNNING', 5, ?, 1, 0, 0, ?)
        returning id
        """;
    final String insertPipelineInstanceSql7 = """
        insert into batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
           related_job_instance_id, run_status)
        values (?, 'DISPATCH', 1, 'JOB', 'DISPATCH', ?, 'RUNNING') returning id
        """;
    String tenant = unique("hot-pipeline");
    long definitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql5, Long.class, tenant, unique("job"));
    long instanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql6,
        Long.class,
        tenant,
        definitionId,
        unique("instance"),
        unique("dedup"),
        unique("trace"));
    long pipelineId =
        jdbcTemplate.queryForObject(insertPipelineInstanceSql7, Long.class, tenant, instanceId);
    long fileId = insertArchivedFileRecord(tenant);
    long dispatchId = insertArchivedDispatchRecord(tenant, fileId, pipelineId);

    assertThat(lineageEvidenceMapper.selectArchivedDispatchRecords(tenant, instanceId, List.of()))
        .extracting(DispatchRecord::id)
        .containsExactly(dispatchId);
    assertThat(lineageEvidenceMapper.selectArchivedDispatchRecords(
            unique("other"), instanceId, List.of()))
        .isEmpty();
  }

  @Test
  @DisplayName("用跨租户标识查询归档证据时候只返回本租户数据,不泄漏其它租户的链路")
  void shouldScopeArchivedEvidenceByTenant() {
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

    long archivedResultVersionId =
        insertArchivedResultVersion(tenantB, instanceB, "file_record:" + fileB);
    assertThatThrownBy(
            () -> lineageEvidenceService.evidenceForResultVersion(tenantA, archivedResultVersionId))
        .isInstanceOf(BizException.class);
    LineageEvidenceResponse evidenceForB =
        lineageEvidenceService.evidenceForResultVersion(tenantB, archivedResultVersionId);
    assertThat(evidenceForB.resultVersion().tenantId()).isEqualTo(tenantB);
    assertThat(evidenceForB.resultVersion().id()).isEqualTo(archivedResultVersionId);

    // job_instance: A 看不到 B 的实例；B 自己能看到
    assertThat(lineageEvidenceMapper.selectArchivedJobInstance(tenantA, instanceB))
        .isNull();
    JobInstance bInstance = lineageEvidenceMapper.selectArchivedJobInstance(tenantB, instanceB);
    assertThat(bInstance).isNotNull();
    assertThat(bInstance.tenantId()).isEqualTo(tenantB);

    // pipeline_instance: 用 B 的 jobInstanceId 查 A → 空（无 tenant 过滤则会漏 B 的 pipeline）
    assertThat(lineageEvidenceMapper.selectArchivedPipelineInstances(tenantA, instanceB))
        .isEmpty();
    assertThat(lineageEvidenceMapper.selectArchivedPipelineInstances(tenantB, instanceB))
        .hasSize(1);

    // file_record: 即便把 B 的 fileId 当作 A 的 payloadFileId 传入，outer tenant 过滤也不放行 B 的文件
    List<FileRecord> filesForA =
        lineageEvidenceMapper.selectArchivedFileRecords(tenantA, instanceA, fileB);
    assertThat(filesForA)
        .isNotEmpty()
        .allMatch(r -> tenantA.equals(r.tenantId()))
        .noneMatch(r -> fileB == r.id());

    // dispatch_record: 跨租户 jobInstanceId + 跨租户 fileId 双路径都不泄漏
    assertThat(
            lineageEvidenceMapper.selectArchivedDispatchRecords(tenantA, instanceB, List.of(fileB)))
        .isEmpty();
    List<DispatchRecord> dispatchForB =
        lineageEvidenceMapper.selectArchivedDispatchRecords(tenantB, instanceB, List.of(fileB));
    assertThat(dispatchForB).hasSize(1);
    assertThat(dispatchForB.get(0).tenantId()).isEqualTo(tenantB);
  }

  // ---- archive.* seed helpers (冷表无 FK，可种孤立行；id 走热表序列默认) ----

  private long insertArchivedFileRecord(String tenant) {
    String insertArchivedFileRecordSql = """
        insert into archive.file_record_archive (
          tenant_id, file_code, biz_type, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'GL', 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SYSTEM', 'LOADED',
                  current_date, ?)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertArchivedFileRecordSql,
        Long.class,
        tenant,
        unique("fc"),
        unique("file") + ".csv",
        "bucket/" + unique("path"),
        unique("trace"));
  }

  private long insertArchivedJobInstance(String tenant, long relatedFileId) {
    String insertArchivedJobInstanceSql = """
        insert into archive.job_instance_archive (
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, related_file_id, finished_at
        ) values (?, 1, 'LINEAGE_JOB', ?, current_date, 'MANUAL', 'SUCCESS', 5, ?, 1, 1, 0, ?, ?, now())
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertArchivedJobInstanceSql,
        Long.class,
        tenant,
        unique("inst"),
        unique("dedup"),
        unique("trace"),
        relatedFileId);
  }

  private long insertArchivedPipelineInstance(String tenant, long jobInstanceId, long fileId) {
    String insertArchivedPipelineInstanceSql = """
        insert into archive.pipeline_instance_archive (
          tenant_id, job_code, pipeline_type,
          system_pipeline_code, system_pipeline_version, file_id,
          related_job_instance_id, run_status, trace_id, finished_at
        ) values (?, 'LINEAGE_JOB', 'IMPORT', 'IMPORT', 1, ?, ?, 'SUCCESS', ?, now())
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertArchivedPipelineInstanceSql,
        Long.class,
        tenant,
        fileId,
        jobInstanceId,
        unique("trace"));
  }

  private long insertArchivedDispatchRecord(String tenant, long fileId, Long pipelineInstanceId) {
    String insertArchivedDispatchRecordSql = """
        insert into archive.file_dispatch_record_archive(
          tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target,
          dispatch_status, dispatch_attempt, receipt_status, dispatched_at
        ) values (?, ?, ?, 'CH_MAIN', 'sftp://target', 'ACKED', 1, 'SUCCESS', now())
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertArchivedDispatchRecordSql, Long.class, tenant, fileId, pipelineInstanceId);
  }

  private long insertLiveResultVersion(String tenant, long jobInstanceId, String payloadRef) {
    String insertLiveResultVersionSql = """
        insert into batch.result_version (
          tenant_id, business_key, version_no, job_instance_id, status, effective_at,
          payload_storage, payload_ref, generated_at, generated_by, promotion_policy
        ) values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp, 'FILE_RECORD', ?,
                  current_timestamp, 'it', 'AUTO_LATEST')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertLiveResultVersionSql,
        Long.class,
        tenant,
        unique("bizkey"),
        jobInstanceId,
        payloadRef);
  }

  private long insertArchivedResultVersion(String tenant, long jobInstanceId, String payloadRef) {
    String insertArchivedResultVersionSql = """
        insert into archive.result_version_archive (
          tenant_id, business_key, version_no, job_instance_id, status, effective_at,
          payload_storage, payload_ref, generated_at, generated_by, promotion_policy
        ) values (?, ?, 1, ?, 'ARCHIVED', now(), 'FILE_RECORD', ?, now(), 'it', 'AUTO_LATEST')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertArchivedResultVersionSql,
        Long.class,
        tenant,
        unique("archive-bizkey"),
        jobInstanceId,
        payloadRef);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
