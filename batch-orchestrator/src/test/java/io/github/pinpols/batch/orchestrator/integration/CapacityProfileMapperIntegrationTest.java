package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileRow;
import io.github.pinpols.batch.orchestrator.mapper.CapacityProfileMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code CapacityProfileMapper.xml} 的真实 PG 回归护栏（原来只有 mocked-mapper 单测 {@code
 * CapacityProfileServiceTest} 覆盖，多 CTE 的 tenant-scoped join 从未在真库上验证过）。
 *
 * <p>该查询是一条 ~208 行多 CTE 聚合：{@code instance_base}（job_instance）→ {@code file_by_instance}
 * （file_record，两支 union：related_file_id 直连 + pipeline_instance.file_id 间连）→ {@code
 * progress_by_instance}（pipeline_instance + pipeline_progress）→ {@code instance_enriched}， worker
 * 维度再叠 {@code task_base}（job_task）。<b>每一处 join 都带 {@code x.tenant_id = i.tenant_id} / {@code
 * x.tenant_id = #{tenantId}}</b>；任一条件漏写就会跨租户串数据——这正是本类要钉死的属性。
 *
 * <p>核心断言：同一时间窗内种入租户 A + 租户 B 两份差异化数据，只查 A，聚合值必须 <b>只反映 A</b> （B 的极端可辨识值 888888888 字节 / 第 3 个实例 /
 * worker-b 必须缺席）。把 CTE 里任一 tenant 谓词 revert 掉，本类至少一个断言转红。
 *
 * <p>种入的 CTE 子表：job_definition（FK 前置）/ job_instance / file_record / pipeline_definition /
 * pipeline_instance / pipeline_progress / job_task —— 覆盖全部 CTE 读到的表，无 stub / skip。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CapacityProfileMapperIntegrationTest extends AbstractIntegrationTest {

  // 时间窗口 bracket 住全部 seeded 时间戳（biz_date 落在 job_instance 月分区覆盖范围内）。
  private static final Instant WINDOW_FROM = Instant.parse("2026-05-01T00:00:00Z");
  private static final Instant WINDOW_TO = Instant.parse("2026-05-02T00:00:00Z");
  private static final String BIZ_DATE = "2026-05-01";
  private static final int LIMIT = 50;

  // 两租户唯一化：非 static 实例字段 —— JUnit5 默认 per-method 生命周期，每个测试方法一个新实例，
  // 故每个方法拿到不同的 tenant，避免同一 PG 容器跨方法累积 seed（基类无 per-test rollback/truncate）。
  private final String tenantA = "cap-a-" + System.nanoTime();
  private final String tenantB = "cap-b-" + System.nanoTime();

  private static final String JOB_CODE_A = "JOB_A";
  private static final String JOB_CODE_B = "JOB_B";
  private static final String WORKER_A = "worker-a";
  private static final String WORKER_B = "worker-b";
  // 租户 B 的可辨识文件字节：若跨租户泄漏，A 的 total_file_bytes 会被这个巨值污染。
  private static final long TENANT_B_FILE_BYTES = 888_888_888L;

  @Autowired private CapacityProfileMapper capacityProfileMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void selectTenantProfile_isTenantScoped_doesNotLeakOtherTenantRows() {
    // arrange
    seedTenantA();
    seedTenantB();

    // act
    List<CapacityProfileRow> rows =
        capacityProfileMapper.selectTenantProfile(tenantA, WINDOW_FROM, WINDOW_TO, LIMIT);

    // assert: 只有 A 一行，且每个聚合都只反映 A 的两个实例，B 的巨值 / 第 3 个实例不得渗入
    assertThat(rows).hasSize(1);
    CapacityProfileRow a = rows.get(0);
    assertThat(a.tenantId()).isEqualTo(tenantA);
    assertThat(a.jobCode()).isNull();
    assertThat(a.workerCode()).isNull();
    assertThat(a.workerGroup()).isNull();
    assertThat(a.instanceCount()).isEqualTo(2L); // 泄漏则会 == 3
    assertThat(a.successCount()).isEqualTo(1L);
    assertThat(a.failureCount()).isEqualTo(1L);
    assertThat(a.totalDurationMs()).isEqualTo(14_000L); // 10000 + 4000
    // A1: related_file(1000) ∪ pipeline_instance.file(500) = 1500;A2 无文件。B 的 888888888 必须缺席
    assertThat(a.totalFileBytes()).isEqualTo(1_500L);
    assertThat(a.totalFileBytes()).isNotEqualTo(TENANT_B_FILE_BYTES);
    assertThat(a.processedRecords()).isEqualTo(750L); // 仅 A1 的 pipeline_progress
  }

  @Test
  void selectJobProfile_returnsOnlyTenantAJobCode() {
    // arrange
    seedTenantA();
    seedTenantB();

    // act
    List<CapacityProfileRow> rows =
        capacityProfileMapper.selectJobProfile(tenantA, WINDOW_FROM, WINDOW_TO, LIMIT);

    // assert: 只有 A 的 job_code，B 的 JOB_B 必须缺席
    assertThat(rows).hasSize(1);
    CapacityProfileRow row = rows.get(0);
    assertThat(row.tenantId()).isEqualTo(tenantA);
    assertThat(row.jobCode()).isEqualTo(JOB_CODE_A);
    assertThat(row.instanceCount()).isEqualTo(2L);
    assertThat(rows).noneMatch(r -> JOB_CODE_B.equals(r.jobCode()));
  }

  @Test
  void selectWorkerProfile_returnsOnlyTenantAWorker() {
    // arrange
    seedTenantA();
    seedTenantB();

    // act
    List<CapacityProfileRow> rows =
        capacityProfileMapper.selectWorkerProfile(tenantA, WINDOW_FROM, WINDOW_TO, LIMIT);

    // assert: 只有 A 的 worker，B 的 worker-b 必须缺席
    assertThat(rows).hasSize(1);
    CapacityProfileRow row = rows.get(0);
    assertThat(row.tenantId()).isEqualTo(tenantA);
    assertThat(row.workerCode()).isEqualTo(WORKER_A);
    assertThat(row.taskCount()).isEqualTo(1L);
    assertThat(rows).noneMatch(r -> WORKER_B.equals(r.workerCode()));
  }

  // ---- seeding ----

  /**
   * 租户 A：2 个 job_instance（1 SUCCESS + 1 FAILED），A1 带 related_file(1000B) + 一条
   * pipeline_instance(file 500B) + pipeline_progress(750) + 1 条 job_task(worker-a)。
   */
  private void seedTenantA() {
    long jobDefId = insertJobDefinition(tenantA, JOB_CODE_A);
    long fileA1 = insertFileRecord(tenantA, "a1", 1_000L);
    long fileA2 = insertFileRecord(tenantA, "a2", 500L);

    long a1 =
        insertJobInstance(
            tenantA,
            jobDefId,
            JOB_CODE_A,
            "SUCCESS",
            fileA1,
            "2026-05-01T10:00:00Z",
            "2026-05-01T10:00:10Z"); // 10000ms
    insertJobInstance(
        tenantA,
        jobDefId,
        JOB_CODE_A,
        "FAILED",
        null,
        "2026-05-01T11:00:00Z",
        "2026-05-01T11:00:04Z"); // 4000ms

    long pipelineDefId = insertPipelineDefinition(tenantA, JOB_CODE_A);
    long piA = insertPipelineInstance(tenantA, pipelineDefId, JOB_CODE_A, fileA2, a1);
    insertPipelineProgress(tenantA, piA, 750L);

    insertJobTask(tenantA, a1, WORKER_A, "SUCCESS", "2026-05-01T10:00:00Z", "2026-05-01T10:00:10Z");
  }

  /** 租户 B：同窗口内 1 个 SUCCESS 实例 + 极端可辨识文件字节 + worker-b 任务，用作泄漏探针。 */
  private void seedTenantB() {
    long jobDefId = insertJobDefinition(tenantB, JOB_CODE_B);
    long fileB1 = insertFileRecord(tenantB, "b1", TENANT_B_FILE_BYTES);
    long b1 =
        insertJobInstance(
            tenantB,
            jobDefId,
            JOB_CODE_B,
            "SUCCESS",
            fileB1,
            "2026-05-01T12:00:00Z",
            "2026-05-01T12:00:33Z");
    insertJobTask(tenantB, b1, WORKER_B, "SUCCESS", "2026-05-01T12:00:00Z", "2026-05-01T12:00:33Z");
  }

  private long insertJobDefinition(String tenantId, String jobCode) {
    Long id =
        jdbcTemplate.queryForObject(
            "insert into batch.job_definition (tenant_id, job_code, job_name, job_type,"
                + " schedule_type, timezone) values (?, ?, ?, 'GENERAL', 'MANUAL',"
                + " 'Asia/Shanghai') returning id",
            Long.class,
            tenantId,
            jobCode,
            jobCode + "-name");
    assertThat(id).isNotNull();
    return id;
  }

  private long insertFileRecord(String tenantId, String tag, long sizeBytes) {
    Long id =
        jdbcTemplate.queryForObject(
            "insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,"
                + " file_size_bytes, storage_type, storage_path, source_type, file_status) values"
                + " (?, 'INPUT', ?, 'DELIMITED', ?, 'LOCAL', ?, 'UPLOAD', 'RECEIVED') returning id",
            Long.class,
            tenantId,
            "file-" + tag,
            sizeBytes,
            "/seed/" + tenantId + "/" + tag);
    assertThat(id).isNotNull();
    return id;
  }

  private long insertJobInstance(
      String tenantId,
      long jobDefId,
      String jobCode,
      String status,
      Long relatedFileId,
      String startedAt,
      String finishedAt) {
    // trigger_type=MANUAL 满足 ck_job_instance_trigger_source（trigger_request_id 允许 NULL）。
    // instance_no/dedup_key 唯一化;related_file_id 显式 ::bigint 以便可为 NULL。
    String unique = tenantId + ":" + jobCode + ":" + status + ":" + System.nanoTime();
    Long id =
        jdbcTemplate.queryForObject(
            "insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no,"
                + " biz_date, trigger_type, instance_status, worker_group, dedup_key,"
                + " related_file_id, started_at, finished_at) values (?, ?, ?, ?, ?::date,"
                + " 'MANUAL', ?, 'wg', ?, ?::bigint, ?::timestamptz, ?::timestamptz) returning id",
            Long.class,
            tenantId,
            jobDefId,
            jobCode,
            "ino-" + unique,
            BIZ_DATE,
            status,
            "dedup-" + unique,
            relatedFileId,
            startedAt,
            finishedAt);
    assertThat(id).isNotNull();
    return id;
  }

  private long insertPipelineDefinition(String tenantId, String jobCode) {
    Long id =
        jdbcTemplate.queryForObject(
            "insert into batch.pipeline_definition (tenant_id, job_code, pipeline_name,"
                + " pipeline_type) values (?, ?, ?, 'IMPORT') returning id",
            Long.class,
            tenantId,
            jobCode,
            jobCode + "-pipeline");
    assertThat(id).isNotNull();
    return id;
  }

  private long insertPipelineInstance(
      String tenantId, long pipelineDefId, String jobCode, long fileId, long relatedJobInstanceId) {
    Long id =
        jdbcTemplate.queryForObject(
            "insert into batch.pipeline_instance (tenant_id, pipeline_definition_id, job_code,"
                + " pipeline_type, file_id, related_job_instance_id, run_status) values (?, ?, ?,"
                + " 'IMPORT', ?, ?, 'SUCCESS') returning id",
            Long.class,
            tenantId,
            pipelineDefId,
            jobCode,
            fileId,
            relatedJobInstanceId);
    assertThat(id).isNotNull();
    return id;
  }

  private void insertPipelineProgress(String tenantId, long pipelineInstanceId, long processed) {
    jdbcTemplate.update(
        "insert into batch.pipeline_progress (tenant_id, pipeline_instance_id, stage,"
            + " processed_count) values (?, ?, 'LOAD', ?)",
        tenantId,
        pipelineInstanceId,
        processed);
  }

  private void insertJobTask(
      String tenantId,
      long jobInstanceId,
      String workerCode,
      String status,
      String startedAt,
      String finishedAt) {
    // job_partition_id 留 NULL（V173 已解 job_task→job_instance FK，无需真分区）。
    jdbcTemplate.update(
        "insert into batch.job_task (tenant_id, job_instance_id, task_status,"
            + " assigned_worker_code, started_at, finished_at) values (?, ?, ?, ?, ?::timestamptz,"
            + " ?::timestamptz)",
        tenantId,
        jobInstanceId,
        status,
        workerCode,
        startedAt,
        finishedAt);
  }
}
