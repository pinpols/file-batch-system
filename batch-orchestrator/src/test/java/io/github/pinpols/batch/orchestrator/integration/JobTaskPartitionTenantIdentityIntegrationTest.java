package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 顶层集成测试应通过 WorkflowRunLaunchService 启动此夹具。 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JobTask 分区引用租户与实例身份")
class JobTaskPartitionTenantIdentityIntegrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbc;
  private String tenant;
  private String otherTenant;
  private Long instanceId;
  private Long otherInstanceId;
  private Long partitionId;
  private Long otherTenantPartitionId;
  private int nextTaskSequence;
  private int nextPartitionNumber;

  @BeforeAll
  static void migrateFreshDatabase() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
  }

  @BeforeEach
  void seedInstancesAndPartitions() {
    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    tenant = "task-partition-fk-" + UUID.randomUUID();
    otherTenant = "task-partition-fk-" + UUID.randomUUID();
    instanceId = insertInstance(tenant);
    otherInstanceId = insertInstance(tenant);
    partitionId = insertPartition(tenant, instanceId);
    otherTenantPartitionId = insertPartition(otherTenant, insertInstance(otherTenant));
  }

  @AfterEach
  void removeOwnedFixture() {
    final String deleteJobExecutionLogSql = """
        delete from batch.job_execution_log where tenant_id in (?, ?)
    """;
    final String deleteJobTaskSql = """
        delete from batch.job_task where tenant_id in (?, ?)
    """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id in (?, ?)
    """;
    final String deleteFileRecordSql = """
        delete from batch.file_record where tenant_id in (?, ?)
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id in (?, ?)
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id in (?, ?)
    """;

    jdbc.update(
        deleteJobExecutionLogSql, tenant, otherTenant);
    jdbc.update(deleteJobTaskSql, tenant, otherTenant);
    jdbc.update(deleteJobPartitionSql, tenant, otherTenant);
    jdbc.update(deleteFileRecordSql, tenant, otherTenant);
    jdbc.update(deleteJobInstanceSql, tenant, otherTenant);
    jdbc.update(deleteJobDefinitionSql, tenant, otherTenant);
    dataSource.destroy();
  }

  @Test
  @DisplayName("运行日志分区引用必须匹配租户和实例，分区删除只解除分区引用")
  void shouldBindExecutionLogPartitionToTenantAndInstanceAndRetainLogOnPartitionDelete() {
    final String selectConvalidatedSql = """
        select convalidated, confdeltype::text as delete_action,
               pg_get_constraintdef(oid) as definition
          from pg_constraint
         where conrelid='batch.job_execution_log'::regclass
           and conname='fk_job_execution_log_partition_tenant_instance'
        """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=? and id=?
    """;
    final String selectTenantIdSql = """
        select tenant_id, job_instance_id, job_partition_id
          from batch.job_execution_log
         where id=?
        """;

    long logId = insertExecutionLog(tenant, instanceId, partitionId);

    assertForeignKeyViolation(() -> insertExecutionLog(otherTenant, otherInstanceId, partitionId));
    assertForeignKeyViolation(() -> insertExecutionLog(tenant, otherInstanceId, partitionId));
    assertCheckViolation(() -> insertExecutionLog(tenant, null, partitionId));

    Map<String, Object> constraint = jdbc.queryForMap(selectConvalidatedSql);
    assertThat(constraint).containsEntry("convalidated", true).containsEntry("delete_action", "n");
    assertThat(constraint.get("definition").toString())
        .contains(
            "FOREIGN KEY (tenant_id, job_partition_id, job_instance_id)",
            "REFERENCES batch.job_partition(tenant_id, id, job_instance_id)",
            "ON DELETE SET NULL (job_partition_id)");

    assertThat(jdbc.update(
            deleteJobPartitionSql, tenant, partitionId))
        .isEqualTo(1);
    Map<String, Object> retainedLog = jdbc.queryForMap(selectTenantIdSql, logId);
    assertThat(retainedLog)
        .containsEntry("tenant_id", tenant)
        .containsEntry("job_instance_id", instanceId)
        .containsEntry("job_partition_id", null);
  }

  @Test
  @DisplayName("同租户同实例分区和无分区任务可写入，异租户或异实例分区被拒绝")
  void shouldRequirePartitionToMatchTaskTenantAndInstance() {
    assertThat(insertTask(tenant, instanceId, partitionId)).isPositive();
    assertThat(insertTask(tenant, instanceId, null)).isPositive();

    assertForeignKeyViolation(() -> insertTask(otherTenant, otherInstanceId, partitionId));
    assertForeignKeyViolation(() -> insertTask(tenant, instanceId, otherTenantPartitionId));
    assertForeignKeyViolation(() -> insertTask(tenant, otherInstanceId, partitionId));
  }

  @Test
  @DisplayName("无分区任务也必须引用同租户下存在的 JobInstance")
  void shouldRequireTaskToMatchParentJobInstance() {
    assertForeignKeyViolation(
        () -> insertTask(tenant, Long.MAX_VALUE, null, LocalDate.of(2026, 3, 22)));
    assertForeignKeyViolation(() -> insertTask(otherTenant, instanceId, null));
    assertForeignKeyViolation(() -> insertTask(tenant, instanceId, null, LocalDate.of(2000, 1, 1)));
  }

  @Test
  @DisplayName("JobTask 复合外键已验证并保护无分区任务的父实例生命周期")
  void shouldInstallValidatedTaskParentForeignKeyAndProtectParentLifecycle() {
    final String selectConvalidatedSql = """
        select c.convalidated, c.confdeltype::text as delete_action,
               pg_get_constraintdef(c.oid) as definition
          from pg_constraint c
         where c.conrelid='batch.job_task'::regclass
           and c.conname='fk_job_task_instance_tenant_date'
        """;
    final String selectCountSql = """
        select count(*) from information_schema.columns
         where table_schema='batch' and table_name='job_task'
           and column_name='job_instance_biz_date' and is_nullable='NO'
        """;
    final String selectCountSql2 = """
        select count(*) from information_schema.columns
         where table_schema='archive' and table_name='job_task_archive'
           and column_name='job_instance_biz_date' and is_nullable='NO'
        """;
    final String selectCountSql3 = """
        select count(*) from pg_constraint c
         where c.conrelid='batch.job_task'::regclass and c.contype='f'
           and pg_get_constraintdef(c.oid)
               like 'FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)%'
           and not c.convalidated
        """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=? and id=?
    """;
    final String updateJobTaskSql = """
        update batch.job_task set job_instance_biz_date=? where id=?
    """;
    final String deleteJobTaskSql = """
        delete from batch.job_task where tenant_id=? and id=?
    """;

    Map<String, Object> constraint = jdbc.queryForMap(selectConvalidatedSql);
    assertThat(constraint).containsEntry("convalidated", true).containsEntry("delete_action", "a");
    assertThat(constraint.get("definition").toString())
        .contains(
            "FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)",
            "REFERENCES batch.job_instance(tenant_id, id, biz_date)");
    assertThat(jdbc.queryForObject(selectCountSql, Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject(selectCountSql2, Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject(selectCountSql3, Long.class)).isZero();

    long unpartitionedInstance = insertInstance(tenant);
    long taskId = insertTask(tenant, unpartitionedInstance, null);
    assertForeignKeyViolation(() -> jdbc.update(
        deleteJobInstanceSql,
        tenant,
        unpartitionedInstance));
    assertForeignKeyViolation(() -> jdbc.update(
        updateJobTaskSql,
        LocalDate.of(2000, 1, 1),
        taskId));
    assertThat(jdbc.update(deleteJobTaskSql, tenant, taskId))
        .isEqualTo(1);
    assertThat(jdbc.update(
            deleteJobInstanceSql,
            tenant,
            unpartitionedInstance))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("复合外键已验证并保留删除分区时级联任务的既有语义")
  void shouldInstallValidatedCompositeForeignKeyAndPreserveCascade() {
    final String selectConvalidatedSql = """
        select c.convalidated, c.confdeltype::text as delete_action,
               pg_get_constraintdef(c.oid) as definition
        from pg_constraint c
        where c.conrelid='batch.job_task'::regclass
          and c.conname='fk_job_task_partition_tenant_instance'
        """;
    final String selectConvalidatedSql2 = """
        select convalidated, pg_get_constraintdef(oid) as definition
          from pg_constraint
         where conrelid='batch.job_partition'::regclass
           and conname='ck_job_partition_instance_biz_date_not_null'
        """;
    final String selectCountSql = """
        select count(*)
          from pg_constraint
         where conrelid='batch.job_partition'::regclass
           and contype='f'
           and pg_get_constraintdef(oid)
               like 'FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)%'
           and not convalidated
        """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where id=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.job_task where id=?
    """;

    Long taskId = insertTask(tenant, instanceId, partitionId);
    Map<String, Object> constraint = jdbc.queryForMap(selectConvalidatedSql);

    assertThat(constraint).containsEntry("convalidated", true).containsEntry("delete_action", "c");
    assertThat(constraint.get("definition").toString())
        .contains(
            "FOREIGN KEY (tenant_id, job_partition_id, job_instance_id)",
            "REFERENCES batch.job_partition(tenant_id, id, job_instance_id)");

    Map<String, Object> requiredDateCheck = jdbc.queryForMap(selectConvalidatedSql2);
    assertThat(requiredDateCheck).containsEntry("convalidated", true);
    assertThat(requiredDateCheck.get("definition").toString())
        .contains("CHECK", "job_instance_biz_date IS NOT NULL");

    Long unvalidatedInstanceReferenceConstraints = jdbc.queryForObject(selectCountSql, Long.class);
    assertThat(unvalidatedInstanceReferenceConstraints).isZero();

    assertThat(jdbc.update(deleteJobPartitionSql, partitionId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            selectCountSql2, Long.class, taskId))
        .isZero();
  }

  @Test
  @DisplayName("直接 SQL 必须显式提供业务日，缺失父实例由复合外键拒绝")
  void shouldRequireBusinessDateForDirectSqlAndRejectMissingParent() {
    assertCheckViolation(() -> insertPartitionWithoutBusinessDate(tenant, instanceId));

    assertForeignKeyViolation(
        () -> insertPartitionWithBusinessDate(tenant, Long.MAX_VALUE, LocalDate.now()));
  }

  @Test
  @DisplayName("分区只能归属同租户实例，父实例改业务日或提前删除必须先处理分区")
  void shouldBindPartitionToTenantAndParentBusinessDate() {
    final String updateJobInstanceSql = """
        update batch.job_instance set biz_date=biz_date + 1 where tenant_id=? and id=?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=? and id=?
    """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=? and id=?
    """;

    assertForeignKeyViolation(() -> insertPartition(otherTenant, instanceId));
    assertForeignKeyViolation(() -> jdbc.update(
        updateJobInstanceSql,
        tenant,
        instanceId));
    assertForeignKeyViolation(() -> jdbc.update(
        deleteJobInstanceSql, tenant, instanceId));

    assertThat(jdbc.update(
            deleteJobPartitionSql, tenant, partitionId))
        .isEqualTo(1);
    assertThat(jdbc.update(
            deleteJobInstanceSql, tenant, instanceId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("热分区源文件必须同租户存在且保留到热分区删除")
  void shouldBindPartitionSourceFileToTenantAndProtectParentLifecycle() {
    final String deleteFileRecordSql = """
        delete from batch.file_record where tenant_id=? and id=?
    """;
    final String selectConvalidatedSql = """
        select convalidated, confdeltype::text as delete_action, pg_get_constraintdef(oid) as definition
          from pg_constraint
         where conrelid='batch.job_partition'::regclass
           and conname='fk_job_partition_source_file_tenant'
        """;
    final String selectCountSql = """
        select count(*) from pg_constraint
         where conrelid='archive.job_partition_archive'::regclass
           and contype='f'
           and pg_get_constraintdef(oid) like '%source_file_id%'
        """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=? and id=?
    """;

    long sourceFileId = insertFileRecord(tenant);
    long sourcePartitionId = insertPartition(tenant, instanceId, sourceFileId);

    assertForeignKeyViolation(() -> insertPartition(otherTenant, otherInstanceId, sourceFileId));
    assertForeignKeyViolation(() -> insertPartition(tenant, instanceId, Long.MAX_VALUE));
    assertForeignKeyViolation(() -> jdbc.update(
        deleteFileRecordSql, tenant, sourceFileId));

    Map<String, Object> constraint = jdbc.queryForMap(selectConvalidatedSql);
    assertThat(constraint).containsEntry("convalidated", true).containsEntry("delete_action", "a");
    assertThat(constraint.get("definition").toString())
        .contains(
            "FOREIGN KEY (tenant_id, source_file_id)",
            "REFERENCES batch.file_record(tenant_id, id)");
    assertThat(jdbc.queryForObject(selectCountSql, Long.class)).isZero();

    assertThat(jdbc.update(
            deleteJobPartitionSql,
            tenant,
            sourcePartitionId))
        .isEqualTo(1);
    assertThat(jdbc.update(
            deleteFileRecordSql, tenant, sourceFileId))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ})
  @DisplayName("源文件物理删除与热分区写入在两种隔离级别均由复合外键串行化")
  void shouldSerializePartitionInsertAndSourceFileDelete(int isolation) throws Exception {
    final String deleteFileRecordSql2 = """
        delete from batch.file_record where tenant_id=? and id=?
    """;
    final String insertJobPartitionSql = """
            insert into batch.job_partition(
              tenant_id, job_instance_id, job_instance_biz_date, source_file_id,
              partition_no, partition_status
            ) values (?, ?, (select biz_date from batch.job_instance where id=?), ?, ?, 'READY')
            """;

    final String selectCountSql = """
        select count(*) from batch.job_partition where source_file_id=?
    """;
    final String selectIdSql = """
          select id from batch.job_partition
           where tenant_id=? and job_instance_id=? and source_file_id=? and partition_no=?
          """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=? and id=?
    """;
    final String deleteFileRecordSql = """
        delete from batch.file_record where tenant_id=? and id=?
    """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      long deleteFirstFile = insertFileRecord(tenant);
      try (Connection parentDelete = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        parentDelete.setTransactionIsolation(isolation);
        parentDelete.setAutoCommit(false);
        try (PreparedStatement statement = parentDelete.prepareStatement(
            deleteFileRecordSql2)) {
          statement.setString(1, tenant);
          statement.setLong(2, deleteFirstFile);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        AtomicInteger insertPid = new AtomicInteger();
        CountDownLatch insertStarted = new CountDownLatch(1);
        Future<String> blockedInsert = executor.submit(() -> executeConcurrently(
            """
            insert into batch.job_partition(
              tenant_id, job_instance_id, job_instance_biz_date, source_file_id,
              partition_no, partition_status
            ) values (?, ?, (select biz_date from batch.job_instance where id=?), ?, ?, 'READY')
            """,
            isolation,
            insertPid,
            insertStarted,
            tenant,
            instanceId,
            instanceId,
            deleteFirstFile,
            ++nextPartitionNumber));
        assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(insertPid.get(), blockedInsert);
        parentDelete.commit();
        assertThat(blockedInsert.get(5, TimeUnit.SECONDS)).isIn("23503", "40001");
        assertThat(jdbc.queryForObject(
                selectCountSql,
                Long.class,
                deleteFirstFile))
            .isZero();
      }

      long insertFirstFile = insertFileRecord(tenant);
      long partitionNo = ++nextPartitionNumber;
      try (Connection childInsert = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        childInsert.setTransactionIsolation(isolation);
        childInsert.setAutoCommit(false);
        try (PreparedStatement statement = childInsert.prepareStatement(insertJobPartitionSql)) {
          statement.setString(1, tenant);
          statement.setLong(2, instanceId);
          statement.setLong(3, instanceId);
          statement.setLong(4, insertFirstFile);
          statement.setLong(5, partitionNo);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        AtomicInteger deletePid = new AtomicInteger();
        CountDownLatch deleteStarted = new CountDownLatch(1);
        Future<String> blockedDelete = executor.submit(() -> executeConcurrently(
            "delete from batch.file_record where tenant_id=? and id=?",
            isolation,
            deletePid,
            deleteStarted,
            tenant,
            insertFirstFile));
        assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(deletePid.get(), blockedDelete);
        childInsert.commit();
        assertThat(blockedDelete.get(5, TimeUnit.SECONDS)).isEqualTo("23503");
      }

      long sourcePartitionId =
          jdbc.queryForObject(selectIdSql, Long.class, tenant, instanceId, insertFirstFile, partitionNo);
      assertThat(jdbc.update(
              deleteJobPartitionSql,
              tenant,
              sourcePartitionId))
          .isEqualTo(1);
      assertThat(jdbc.update(
              deleteFileRecordSql, tenant, insertFirstFile))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("父删除与分区插入并发时由复合外键串行化两个方向")
  void shouldSerializePartitionInsertAndParentDelete() throws Exception {
    final String deleteJobInstanceSql2 = """
        delete from batch.job_instance where tenant_id=? and id=?
    """;
    final String insertJobPartitionSql = """
          insert into batch.job_partition(
            tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status
          ) values (?, ?, ?, ?, 'READY') returning id
          """;

    final String selectBizDateSql = """
        select biz_date from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_partition where tenant_id=? and id=?
    """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=? and id=?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=? and id=?
    """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      long deleteFirstInstance = insertInstance(tenant);
      Connection parentDelete = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      parentDelete.setAutoCommit(false);
      try (PreparedStatement statement = parentDelete.prepareStatement(
          deleteJobInstanceSql2)) {
        statement.setString(1, tenant);
        statement.setLong(2, deleteFirstInstance);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      AtomicInteger insertPid = new AtomicInteger();
      CountDownLatch insertStarted = new CountDownLatch(1);
      Future<String> blockedInsert = executor.submit(
          () -> insertPartitionConcurrently(tenant, deleteFirstInstance, insertPid, insertStarted));
      assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitLockWait(insertPid.get(), blockedInsert);
      parentDelete.commit();
      parentDelete.close();
      assertThat(blockedInsert.get(5, TimeUnit.SECONDS)).isEqualTo("23503");

      long insertFirstInstance = insertInstance(tenant);
      LocalDate bizDate = jdbc.queryForObject(
          selectBizDateSql,
          LocalDate.class,
          tenant,
          insertFirstInstance);
      Connection childInsert = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      childInsert.setAutoCommit(false);
      long newPartitionId;
      try (PreparedStatement statement = childInsert.prepareStatement(insertJobPartitionSql)) {
        statement.setString(1, tenant);
        statement.setLong(2, insertFirstInstance);
        statement.setObject(3, bizDate);
        statement.setInt(4, ++nextPartitionNumber);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          newPartitionId = result.getLong(1);
        }
      }
      AtomicInteger deletePid = new AtomicInteger();
      CountDownLatch deleteStarted = new CountDownLatch(1);
      Future<String> blockedDelete = executor.submit(
          () -> deleteInstanceConcurrently(tenant, insertFirstInstance, deletePid, deleteStarted));
      assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitLockWait(deletePid.get(), blockedDelete);
      childInsert.commit();
      childInsert.close();
      assertThat(blockedDelete.get(5, TimeUnit.SECONDS)).isEqualTo("23503");
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Long.class,
              tenant,
              newPartitionId))
          .isEqualTo(1L);
      assertThat(jdbc.update(
              deleteJobPartitionSql, tenant, newPartitionId))
          .isEqualTo(1);
      assertThat(jdbc.update(
              deleteJobInstanceSql,
              tenant,
              insertFirstInstance))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("步骤必须匹配任务的租户、实例和分区, 双方无分区才允许空值")
  void shouldMatchTaskIdentity_whenCreatingStepWithNullablePartition() {
    final String updateJobStepInstanceSql = """
        update batch.job_step_instance set job_partition_id=null where id=?
    """;

    long validTask = insertTask(tenant, instanceId, partitionId);
    long validStep = insertStep(tenant, instanceId, partitionId, validTask);
    assertThat(validStep).isPositive();
    assertThat(insertStep(tenant, instanceId, null, insertTask(tenant, instanceId, null)))
        .isPositive();

    long foreignTenantTask = insertTask(tenant, instanceId, null);
    assertForeignKeyViolation(() -> insertStep(otherTenant, instanceId, null, foreignTenantTask));
    long foreignInstanceTask = insertTask(tenant, instanceId, null);
    assertForeignKeyViolation(() -> insertStep(tenant, otherInstanceId, null, foreignInstanceTask));
    long otherPartition = insertPartition(tenant, instanceId);
    long mismatchedPartitionTask = insertTask(tenant, instanceId, partitionId);
    assertForeignKeyViolation(
        () -> insertStep(tenant, instanceId, otherPartition, mismatchedPartitionTask));
    long missingPartitionTask = insertTask(tenant, instanceId, partitionId);
    assertForeignKeyViolation(() -> insertStep(tenant, instanceId, null, missingPartitionTask));
    long partitionlessTask = insertTask(tenant, instanceId, null);
    assertForeignKeyViolation(() -> insertStep(tenant, instanceId, partitionId, partitionlessTask));
    assertForeignKeyViolation(() -> insertStep(tenant, instanceId, null, Long.MAX_VALUE));
    assertForeignKeyViolation(() -> jdbc.update(
        updateJobStepInstanceSql, validStep));
  }

  @Test
  @DisplayName("步骤归属原生外键已验证, 父改键失败且任务和分区删除仍级联")
  void shouldProtectOwnershipAndCascade_whenTaskHasStep() {
    final String updateJobTaskSql = """
        update batch.job_task set job_partition_id=null where id=?
    """;
    final String updateJobTaskSql2 = """
        update batch.job_task set tenant_id=? where id=?
    """;
    final String updateJobTaskSql3 = """
        update batch.job_task set job_instance_id=? where id=?
    """;
    final String selectConvalidatedSql = """
        select convalidated, confdeltype::text as delete_action, pg_get_constraintdef(oid) as definition
          from pg_constraint
         where conrelid='batch.job_step_instance'::regclass
           and conname='fk_job_step_task_ownership'
        """;
    final String selectCountSql = """
        select count(*) from information_schema.columns
         where column_name='job_partition_ref_key' and is_generated='ALWAYS'
           and ((table_schema='batch' and table_name in ('job_task','job_step_instance'))
             or (table_schema='archive' and table_name in ('job_task_archive','job_step_instance_archive')))
        """;
    final String deleteJobStepInstanceSql = """
        delete from batch.job_step_instance where id=?
    """;
    final String updateJobTaskSql4 = """
        update batch.job_task set job_partition_id=? where id=?
    """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where id=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.job_task where id in (?, ?)
    """;
    final String selectCountSql3 = """
        select count(*) from batch.job_step_instance where id=?
    """;
    final String deleteJobTaskSql = """
        delete from batch.job_task where id=?
    """;

    long task = insertTask(tenant, instanceId, partitionId);
    long step = insertStep(tenant, instanceId, partitionId, task);
    assertForeignKeyViolation(
        () -> jdbc.update(updateJobTaskSql, task));
    long partitionlessTask = insertTask(tenant, instanceId, null);
    long partitionlessStep = insertStep(tenant, instanceId, null, partitionlessTask);
    assertForeignKeyViolation(() -> jdbc.update(
        updateJobTaskSql2, otherTenant, partitionlessTask));
    assertForeignKeyViolation(() -> jdbc.update(
        updateJobTaskSql3,
        otherInstanceId,
        partitionlessTask));
    assertCheckViolation(() -> insertTask(tenant, instanceId, 0L));
    assertCheckViolation(() -> insertStep(tenant, instanceId, 0L, partitionlessTask));

    Map<String, Object> constraint = jdbc.queryForMap(selectConvalidatedSql);
    assertThat(constraint).containsEntry("convalidated", true).containsEntry("delete_action", "c");
    assertThat(constraint.get("definition").toString())
        .contains(
            "FOREIGN KEY (tenant_id, job_task_id, job_instance_id, job_partition_ref_key)",
            "REFERENCES batch.job_task(tenant_id, id, job_instance_id, job_partition_ref_key)");
    assertThat(jdbc.queryForObject(selectCountSql, Long.class)).isEqualTo(4);

    assertThat(jdbc.update(deleteJobStepInstanceSql, partitionlessStep))
        .isEqualTo(1);
    assertThat(jdbc.update(
            updateJobTaskSql4,
            partitionId,
            partitionlessTask))
        .isEqualTo(1);
    assertThat(jdbc.update(deleteJobPartitionSql, partitionId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            selectCountSql2,
            Long.class,
            task,
            partitionlessTask))
        .isZero();
    assertThat(jdbc.queryForObject(
            selectCountSql3, Long.class, step))
        .isZero();
    long deletedTask = insertTask(tenant, otherInstanceId, null);
    long deletedStep = insertStep(tenant, otherInstanceId, null, deletedTask);
    assertThat(jdbc.update(deleteJobTaskSql, deletedTask))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            selectCountSql3, Long.class, deletedStep))
        .isZero();
  }

  @Test
  @DisplayName("批量步骤中一项归属错误时整条写入回滚")
  void shouldRollbackAllSteps_whenBatchContainsWrongInstance() {
    final String insertJobStepInstanceSql = """
        insert into batch.job_step_instance(
          tenant_id,job_instance_id,job_task_id,step_code,step_type,step_status
        ) values (?, ?, ?, 'FIRST', 'TASK', 'READY'), (?, ?, ?, 'SECOND', 'TASK', 'READY')
        """;
    final String selectCountSql = """
        select count(*) from batch.job_step_instance where tenant_id=?
    """;

    long firstTask = insertTask(tenant, instanceId, null);
    long secondTask = insertTask(tenant, instanceId, null);
    assertForeignKeyViolation(
        () -> jdbc.update(insertJobStepInstanceSql, tenant, instanceId, firstTask, tenant, otherInstanceId, secondTask));
    assertThat(jdbc.queryForObject(
            selectCountSql, Long.class, tenant))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ})
  @DisplayName("两种事务隔离下步骤插入与任务分区改键按先后顺序串行化, 无空值旁路")
  void shouldSerializeStepAndTaskIdentity_whenPartitionChangesConcurrently(int isolation)
      throws Exception {
        final String updateJobTaskSql = """
            update batch.job_task set job_partition_id=null where id=?
        """;
    final String insertJobStepInstanceSql = """
            insert into batch.job_step_instance(
              tenant_id,job_instance_id,job_partition_id,job_task_id,step_code,step_type,step_status
            ) values (?, ?, ?, ?, 'STEP', 'TASK', 'READY')
            """;

    final String selectCountSql = """
        select count(*) from batch.job_step_instance where job_task_id=?
    """;
    final String selectJobPartitionIdSql = """
        select job_partition_id from batch.job_task where id=?
    """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      long updateFirstTask = insertTask(tenant, instanceId, partitionId);
      try (Connection updater = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        updater.setTransactionIsolation(isolation);
        updater.setAutoCommit(false);
        try (PreparedStatement statement = updater.prepareStatement(
            updateJobTaskSql)) {
          statement.setLong(1, updateFirstTask);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        AtomicInteger pid = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Future<String> insert = executor.submit(() -> executeConcurrently(
            """
            insert into batch.job_step_instance(
              tenant_id,job_instance_id,job_partition_id,job_task_id,step_code,step_type,step_status
            ) values (?, ?, ?, ?, 'STEP', 'TASK', 'READY')
            """, isolation, pid, started, tenant, instanceId, partitionId, updateFirstTask));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(pid.get(), insert);
        updater.commit();
        assertThat(insert.get(5, TimeUnit.SECONDS)).isIn("23503", "40001");
        assertThat(jdbc.queryForObject(
                selectCountSql,
                Long.class,
                updateFirstTask))
            .isZero();
      }

      long insertFirstTask = insertTask(tenant, instanceId, partitionId);
      try (Connection inserter = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        inserter.setTransactionIsolation(isolation);
        inserter.setAutoCommit(false);
        try (PreparedStatement statement = inserter.prepareStatement(insertJobStepInstanceSql)) {
          statement.setString(1, tenant);
          statement.setLong(2, instanceId);
          statement.setLong(3, partitionId);
          statement.setLong(4, insertFirstTask);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        AtomicInteger pid = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Future<String> update = executor.submit(() -> executeConcurrently(
            "update batch.job_task set job_partition_id=null where id=?",
            isolation,
            pid,
            started,
            insertFirstTask));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(pid.get(), update);
        inserter.commit();
        assertThat(update.get(5, TimeUnit.SECONDS)).isIn("23503", "40001");
        assertThat(jdbc.queryForObject(
                selectJobPartitionIdSql,
                Long.class,
                insertFirstTask))
            .isEqualTo(partitionId);
        assertThat(jdbc.queryForObject(
                selectCountSql,
                Long.class,
                insertFirstTask))
            .isEqualTo(1);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(
      ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ})
  @DisplayName("JobTask 插入与父实例删除两种先后顺序均由复合外键串行化")
  void shouldSerializeTaskInsertAndParentDelete(int isolation) throws Exception {
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=? and id=?
    """;
    final String insertJobTaskSql = """
            insert into batch.job_task(
              tenant_id,job_instance_id,job_instance_biz_date,task_type,task_seq,task_status
            ) values (?, ?, ?, 'EXECUTION', ?, 'READY')
            """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      long deleteFirstInstance = insertInstance(tenant);
      LocalDate deleteFirstDate = instanceBizDate(deleteFirstInstance);
      try (Connection parentDelete = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        parentDelete.setTransactionIsolation(isolation);
        parentDelete.setAutoCommit(false);
        try (PreparedStatement statement = parentDelete.prepareStatement(
            deleteJobInstanceSql)) {
          statement.setString(1, tenant);
          statement.setLong(2, deleteFirstInstance);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        AtomicInteger insertPid = new AtomicInteger();
        CountDownLatch insertStarted = new CountDownLatch(1);
        Future<String> blockedInsert = executor.submit(() -> executeConcurrently(
            """
            insert into batch.job_task(
              tenant_id,job_instance_id,job_instance_biz_date,task_type,task_seq,task_status
            ) values (?, ?, ?, 'EXECUTION', ?, 'READY')
            """,
            isolation,
            insertPid,
            insertStarted,
            tenant,
            deleteFirstInstance,
            deleteFirstDate,
            ++nextTaskSequence));
        assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(insertPid.get(), blockedInsert);
        parentDelete.commit();
        assertThat(blockedInsert.get(5, TimeUnit.SECONDS)).isIn("23503", "40001");
      }

      long insertFirstInstance = insertInstance(tenant);
      LocalDate insertFirstDate = instanceBizDate(insertFirstInstance);
      try (Connection childInsert = DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        childInsert.setTransactionIsolation(isolation);
        childInsert.setAutoCommit(false);
        try (PreparedStatement statement = childInsert.prepareStatement(insertJobTaskSql)) {
          statement.setString(1, tenant);
          statement.setLong(2, insertFirstInstance);
          statement.setObject(3, insertFirstDate);
          statement.setInt(4, ++nextTaskSequence);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        AtomicInteger deletePid = new AtomicInteger();
        CountDownLatch deleteStarted = new CountDownLatch(1);
        Future<String> blockedDelete = executor.submit(() -> executeConcurrently(
            "delete from batch.job_instance where tenant_id=? and id=?",
            isolation,
            deletePid,
            deleteStarted,
            tenant,
            insertFirstInstance));
        assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(deletePid.get(), blockedDelete);
        childInsert.commit();
        assertThat(blockedDelete.get(5, TimeUnit.SECONDS)).isIn("23503", "40001");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private String executeConcurrently(
      String sql, int isolation, AtomicInteger pid, CountDownLatch started, Object... arguments)
      throws SQLException {
    try (Connection connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setTransactionIsolation(isolation);
      connection.setAutoCommit(false);
      pid.set(readBackendPid(connection));
      started.countDown();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(10);
        for (int i = 0; i < arguments.length; i++) {
          statement.setObject(i + 1, arguments[i]);
        }
        statement.executeUpdate();
      }
      connection.commit();
      return "committed";
    } catch (SQLException failure) {
      return failure.getSQLState();
    }
  }

  private String insertPartitionConcurrently(
      String ownerTenant, long ownerInstanceId, AtomicInteger pid, CountDownLatch started)
      throws SQLException {
    final String insertJobPartitionSql = """
          insert into batch.job_partition(
            tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status
          ) values (?, ?, (select biz_date from batch.job_instance where id=?), ?, 'READY')
          """;

    try (Connection connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      pid.set(readBackendPid(connection));
      started.countDown();
      try (PreparedStatement statement = connection.prepareStatement(insertJobPartitionSql)) {
        statement.setString(1, ownerTenant);
        statement.setLong(2, ownerInstanceId);
        statement.setLong(3, ownerInstanceId);
        statement.setInt(4, ++nextPartitionNumber);
        statement.executeUpdate();
      }
      connection.commit();
      return null;
    } catch (SQLException failure) {
      if (failure.getSQLState() != null) {
        return failure.getSQLState();
      }
      throw failure;
    }
  }

  private String deleteInstanceConcurrently(
      String ownerTenant, long ownerInstanceId, AtomicInteger pid, CountDownLatch started)
      throws SQLException {
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=? and id=?
    """;

    try (Connection connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      pid.set(readBackendPid(connection));
      started.countDown();
      try (PreparedStatement statement = connection.prepareStatement(
          deleteJobInstanceSql)) {
        statement.setString(1, ownerTenant);
        statement.setLong(2, ownerInstanceId);
        statement.executeUpdate();
      }
      connection.commit();
      return null;
    } catch (SQLException failure) {
      if (failure.getSQLState() != null) {
        return failure.getSQLState();
      }
      throw failure;
    }
  }

  private int readBackendPid(Connection connection) throws SQLException {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (PreparedStatement statement = connection.prepareStatement(selectPgBackendPidSql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }

  private void awaitLockWait(int backendPid, Future<String> operation) throws Exception {
    final String selectCoalesceSql = """
          select coalesce(wait_event_type = 'Lock', false)
            from pg_stat_activity
           where pid=?
          """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && !operation.isDone()) {
      Boolean waiting = jdbc.queryForObject(selectCoalesceSql, Boolean.class, backendPid);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    assertThat(operation.isDone())
        .as("operation should block on the parent/child FK lock")
        .isFalse();
    throw new AssertionError("database backend did not enter a lock wait");
  }

  private long insertInstance(String ownerTenant) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, ?, 'Task partition FK', 'GENERAL', 'MANUAL', 'UTC', 'NONE') returning id
        """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count
        ) values (?, ?, 'TASK_PARTITION_FK', ?, current_date, 'MANUAL', 'RUNNING', 5, ?, 1)
        returning id
        """;

    long definitionId = jdbc.queryForObject(
        insertJobDefinitionSql, Long.class, ownerTenant, "JOB_" + UUID.randomUUID().toString().replace("-", ""));
    return jdbc.queryForObject(
        insertJobInstanceSql,
        Long.class,
        ownerTenant,
        definitionId,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString());
  }

  private long insertPartition(String ownerTenant, Long ownerInstanceId) {
    final String insertJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status
        ) values (?, ?, (select biz_date from batch.job_instance where id=?), ?, 'READY') returning id
        """;

    return jdbc.queryForObject(
        insertJobPartitionSql, Long.class, ownerTenant, ownerInstanceId, ownerInstanceId, ++nextPartitionNumber);
  }

  private long insertFileRecord(String ownerTenant) {
    final String insertFileRecordSql = """
        insert into batch.file_record(
          tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
          source_type, file_status
        ) values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED') returning id
        """;

    return jdbc.queryForObject(
        insertFileRecordSql,
        Long.class,
        ownerTenant,
        "SOURCE_FK_" + UUID.randomUUID(),
        "/source-fk/" + UUID.randomUUID());
  }

  private long insertPartition(String ownerTenant, Long ownerInstanceId, Long sourceFileId) {
    final String insertJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, job_instance_id, job_instance_biz_date, source_file_id,
          partition_no, partition_status
        ) values (?, ?, (select biz_date from batch.job_instance where id=?), ?, ?, 'READY')
        returning id
        """;

    return jdbc.queryForObject(
        insertJobPartitionSql,
        Long.class,
        ownerTenant,
        ownerInstanceId,
        ownerInstanceId,
        sourceFileId,
        ++nextPartitionNumber);
  }

  private long insertExecutionLog(String ownerTenant, Long ownerInstanceId, Long ownerPartitionId) {
    final String insertJobExecutionLogSql = """
        insert into batch.job_execution_log(
          tenant_id, job_instance_id, job_partition_id, log_level, log_type, message
        ) values (?, ?, ?, 'INFO', 'AUDIT', 'partition ownership test') returning id
        """;

    return jdbc.queryForObject(insertJobExecutionLogSql, Long.class, ownerTenant, ownerInstanceId, ownerPartitionId);
  }

  private long insertPartitionWithoutBusinessDate(String ownerTenant, Long ownerInstanceId) {
    final String insertJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, job_instance_id, partition_no, partition_status
        ) values (?, ?, ?, 'READY') returning id
        """;

    return jdbc.queryForObject(
        insertJobPartitionSql, Long.class, ownerTenant, ownerInstanceId, ++nextPartitionNumber);
  }

  private long insertPartitionWithBusinessDate(
      String ownerTenant, Long ownerInstanceId, LocalDate businessDate) {
    final String insertJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status
        ) values (?, ?, ?, ?, 'READY') returning id
        """;

    return jdbc.queryForObject(
        insertJobPartitionSql, Long.class, ownerTenant, ownerInstanceId, businessDate, ++nextPartitionNumber);
  }

  private long insertTask(String ownerTenant, Long ownerInstanceId, Long ownerPartitionId) {
    return insertTask(
        ownerTenant, ownerInstanceId, ownerPartitionId, instanceBizDate(ownerInstanceId));
  }

  private long insertTask(
      String ownerTenant, Long ownerInstanceId, Long ownerPartitionId, LocalDate ownerBizDate) {
    final String insertJobTaskSql = """
        insert into batch.job_task(
          tenant_id, job_instance_id, job_instance_biz_date,
          job_partition_id, task_type, task_seq, task_status
        ) values (?, ?, ?, ?, 'EXECUTION', ?, 'READY') returning id
        """;

    return jdbc.queryForObject(
        insertJobTaskSql,
        Long.class,
        ownerTenant,
        ownerInstanceId,
        ownerBizDate,
        ownerPartitionId,
        ++nextTaskSequence);
  }

  private LocalDate instanceBizDate(Long ownerInstanceId) {
    final String selectBizDateSql = """
        select biz_date from batch.job_instance where id=?
    """;

    return jdbc.queryForObject(
        selectBizDateSql, LocalDate.class, ownerInstanceId);
  }

  private long insertStep(
      String ownerTenant, Long ownerInstanceId, Long ownerPartitionId, Long ownerTaskId) {
    final String insertJobStepInstanceSql = """
        insert into batch.job_step_instance(
          tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type, step_status
        ) values (?, ?, ?, ?, 'STEP', 'TASK', 'READY') returning id
        """;

    return jdbc.queryForObject(
        insertJobStepInstanceSql, Long.class, ownerTenant, ownerInstanceId, ownerPartitionId, ownerTaskId);
  }

  private void assertForeignKeyViolation(Runnable insert) {
    assertThatThrownBy(insert::run)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
  }

  private void assertCheckViolation(Runnable insert) {
    assertThatThrownBy(insert::run)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
  }
}
