package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.github.pinpols.batch.worker.processes.query.ProcessStagingExpiredScanQuery;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("PROCESS staging generation 清理与 RLS Mapper 真实 PostgreSQL 回归")
class ProcessStagingMapperIntegrationTest {

  private static final String GENERATION_A = "10000000-0000-0000-0000-000000000001";
  private static final String GENERATION_A_OTHER = "10000000-0000-0000-0000-000000000002";
  private static final String GENERATION_B = "20000000-0000-0000-0000-000000000001";
  private static final String GENERATION_C = "30000000-0000-0000-0000-000000000001";

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private JdbcTemplate jdbcTemplate;
  private ProcessStagingMapper mapper;
  private SqlSessionFactory sqlSessionFactory;
  private DataSource dataSource;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    final String dropSql = """
        drop schema if exists batch cascade
    """;
    jdbcTemplate.execute(dropSql);
    final String createSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createSql);
    final String createBatchProcessStagingGenerationSql = """
        create table batch.process_staging_generation (
          generation_id uuid primary key,
          tenant_id text not null,
          job_task_id bigint,
          batch_key text not null,
          generation_status text not null,
          created_at timestamptz not null default now(),
          sealed_at timestamptz,
          unique (tenant_id, generation_id, batch_key)
        )
        """;
    jdbcTemplate.execute(createBatchProcessStagingGenerationSql);
    final String alterBatchProcessStagingGenerationSql = """
        ALTER TABLE batch.process_staging_generation ENABLE ROW LEVEL SECURITY
    """;
    jdbcTemplate.execute(alterBatchProcessStagingGenerationSql);
    final String alterBatchProcessStagingGenerationSqlValue1 = """
        ALTER TABLE batch.process_staging_generation FORCE ROW LEVEL SECURITY
    """;
    jdbcTemplate.execute(alterBatchProcessStagingGenerationSqlValue1);
    final String createSqlValue2 = """
        CREATE POLICY tenant_isolation_strict ON batch.process_staging_generation
          AS PERMISSIVE FOR ALL TO PUBLIC
          USING (tenant_id = current_setting('app.tenant_id', true))
          WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
        """;
    jdbcTemplate.execute(createSqlValue2);
    final String createBatchProcessStagingSql = """
        create table batch.process_staging (
          batch_key text not null,
          staging_generation_id uuid not null,
          row_seq bigint not null,
          tenant_id text not null,
          target_schema text not null,
          target_table text not null,
          payload jsonb not null,
          staged_at timestamptz not null
        ) partition by range (staged_at)
        """;
    jdbcTemplate.execute(createBatchProcessStagingSql);
    final String createBatchProcessStagingOldSql = """
        create table batch.process_staging_old partition of batch.process_staging
        for values from (minvalue) to ('2026-10-14 00:00:00+00')
        """;
    jdbcTemplate.execute(createBatchProcessStagingOldSql);
    final String createBatchProcessStagingCurrentSql = """
        create table batch.process_staging_current partition of batch.process_staging
        for values from ('2026-10-14 00:00:00+00') to (maxvalue)
        """;
    jdbcTemplate.execute(createBatchProcessStagingCurrentSql);
    final String alterBatchProcessStagingSql = """
        ALTER TABLE batch.process_staging ENABLE ROW LEVEL SECURITY
    """;
    jdbcTemplate.execute(alterBatchProcessStagingSql);
    final String alterBatchProcessStagingSqlValue3 = """
        ALTER TABLE batch.process_staging FORCE ROW LEVEL SECURITY
    """;
    jdbcTemplate.execute(alterBatchProcessStagingSqlValue3);
    final String createSqlValue4 = """
        CREATE POLICY tenant_isolation_strict ON batch.process_staging
          AS PERMISSIVE FOR ALL TO PUBLIC
          USING (tenant_id = current_setting('app.tenant_id', true)
            AND staging_generation_id::text = current_setting('app.process_staging_generation_id', true))
          WITH CHECK (tenant_id = current_setting('app.tenant_id', true)
            AND staging_generation_id::text = current_setting('app.process_staging_generation_id', true))
        """;
    jdbcTemplate.execute(createSqlValue4);
    final String sqlPgRolesSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'process_staging_probe') THEN
            CREATE ROLE process_staging_probe NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;
    jdbcTemplate.execute(sqlPgRolesSql);
    final String grantSql = """
        GRANT USAGE ON SCHEMA batch TO process_staging_probe
    """;
    jdbcTemplate.execute(grantSql);
    final String grantSqlValue5 = """
        GRANT SELECT, INSERT, UPDATE, DELETE ON batch.process_staging_generation TO process_staging_probe
    """;
    jdbcTemplate.execute(grantSqlValue5);
    final String insertBatchProcessStagingSql = """
        insert into batch.process_staging values
          ('old-a', cast(? as uuid), 1, 't1', 'biz', 'target', '{}', '2026-10-12 00:00:00+00'),
          ('old-a', cast(? as uuid), 4, 't1', 'biz', 'target', '{}', '2026-10-12 01:00:00+00'),
          ('old-b', cast(? as uuid), 2, 't1', 'biz', 'target', '{}', '2026-10-13 00:00:00+00'),
          ('fresh', cast(? as uuid), 2, 't1', 'biz', 'target', '{}', now()),
          ('foreign', cast(? as uuid), 3, 't2', 'biz', 'target', '{}', '2026-10-12 02:00:00+00')
        """;
    jdbcTemplate.update(
        insertBatchProcessStagingSql,
        GENERATION_A,
        GENERATION_A,
        GENERATION_A_OTHER,
        GENERATION_B,
        GENERATION_C);
    final String insertBatchProcessStagingGenerationSql = """
        insert into batch.process_staging_generation
          (generation_id, tenant_id, job_task_id, batch_key, generation_status, created_at)
        values
          (cast(? as uuid), 't1', 101, 'old-a', 'OPEN', now() - interval '3 days'),
          (cast(? as uuid), 't1', 102, 'old-b', 'OPEN', now() - interval '3 days'),
          (cast(? as uuid), 't1', 103, 'fresh', 'OPEN', now()),
          (cast(? as uuid), 't2', 201, 'foreign', 'OPEN', now() - interval '3 days')
        """;
    jdbcTemplate.update(
        insertBatchProcessStagingGenerationSql,
        GENERATION_A,
        GENERATION_A_OTHER,
        GENERATION_B,
        GENERATION_C);

    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    factoryBean.setMapperLocations(
        new ClassPathResource("mapper/business/ProcessStagingMapper.xml"));
    sqlSessionFactory = factoryBean.getObject();
    mapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(ProcessStagingMapper.class);
  }

  @DisplayName("自动删除默认关闭时保留超过保留期的 staging 行")
  @Test
  void shouldRetainOverdueRowsWhenAutomaticDeletionIsDisabled() {
    ProcessStagingOrphanCleaner cleaner = new ProcessStagingOrphanCleaner(
        mapper,
        new ProcessStagingCleanupProperties(),
        new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));

    assertThat(cleaner.cleanOnce()).isZero();
    final String selectBatchProcessStagingSql = """
        select count(*) from batch.process_staging where staged_at < now() - interval '24 hours'
    """;
    Long selectedProcessStagingCount =
        jdbcTemplate.queryForObject(selectBatchProcessStagingSql, Long.class);
    assertThat(selectedProcessStagingCount).isEqualTo(4L);
  }

  @DisplayName("封存和删除仅影响精确 generation，重复执行保持幂等")
  @Test
  void shouldSealAndDeleteExactGenerationAtomicallyAndIdempotently() {
    ProcessStagingGenerationCandidate candidate =
        new ProcessStagingGenerationCandidate("t1", 101L, GENERATION_A, "old-a");
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    int firstDelete = transaction.execute(status -> {
      final String selectSql = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSql, String.class);
      assertThat(mapper.selectGenerationStatusForUpdate(candidate)).isEqualTo("OPEN");
      assertThat(mapper.sealOpenGeneration(candidate)).isEqualTo(1);
      mapper.setGenerationRlsContext(GENERATION_A);
      int deleted = mapper.deleteGenerationRows(candidate, 1);
      assertThat(mapper.markGenerationReclaimedIfEmpty(candidate)).isZero();
      return deleted;
    });
    int secondChunk = transaction.execute(status -> {
      final String selectSqlValue6 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue6, String.class);
      assertThat(mapper.selectGenerationStatusForUpdate(candidate)).isEqualTo("SEALED");
      mapper.setGenerationRlsContext(GENERATION_A);
      int deleted = mapper.deleteGenerationRows(candidate, 1);
      assertThat(mapper.markGenerationReclaimedIfEmpty(candidate)).isEqualTo(1);
      return deleted;
    });
    int repeatDelete = transaction.execute(status -> {
      final String selectSqlValue7 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue7, String.class);
      assertThat(mapper.selectGenerationStatusForUpdate(candidate)).isEqualTo("RECLAIMED");
      return 0;
    });

    assertThat(firstDelete).isEqualTo(1);
    assertThat(secondChunk).isEqualTo(1);
    assertThat(repeatDelete).isZero();
    final String selectBatchProcessStagingGenerationSql = """
        select generation_status from batch.process_staging_generation where generation_id = cast(? as uuid)
    """;
    String selectedGenerationStatus = jdbcTemplate.queryForObject(
        selectBatchProcessStagingGenerationSql, String.class, GENERATION_A);
    assertThat(selectedGenerationStatus).isEqualTo("RECLAIMED");
    final String selectBatchProcessStagingSqlValue8 = """
        select count(*) from batch.process_staging where batch_key = 'old-b'
    """;
    Long selectedProcessStagingCount =
        jdbcTemplate.queryForObject(selectBatchProcessStagingSqlValue8, Long.class);
    assertThat(selectedProcessStagingCount).isEqualTo(1L);
  }

  @DisplayName("generation 清理候选按 tenant/保留期分页，行统计需绑定 generation GUC")
  @Test
  void shouldPageExpiredGenerationsAndCountRowsByExactGeneration() {
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    List<ProcessStagingGenerationCandidate> firstPage = transaction.execute(status -> {
      final String selectSqlValue9 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue9, String.class);
      return mapper.listExpiredGenerations("t1", 24, 1, 0);
    });
    List<ProcessStagingGenerationCandidate> secondPage = transaction.execute(status -> {
      final String selectSqlValue10 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue10, String.class);
      return mapper.listExpiredGenerations("t1", 24, 1, 1);
    });

    assertThat(mapper.countExpiredGenerations("t1", 24)).isEqualTo(2L);
    assertThat(mapper.countExpiredGenerations("t2", 24)).isEqualTo(1L);
    assertThat(firstPage).hasSize(1);
    assertThat(secondPage).hasSize(1);
    ProcessStagingGenerationCandidate candidate = firstPage.getFirst();
    assertThat(candidate.getTenantId()).isEqualTo("t1");
    assertThat(candidate.getTaskId()).isEqualTo(101L);
    assertThat(candidate.getGenerationId()).isEqualTo(GENERATION_A);
    assertThat(candidate.getBatchKey()).isEqualTo("old-a");
    long overdueRows = transaction.execute(status -> {
      final String selectSqlValue11 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue11, String.class);
      mapper.setGenerationRlsContext(GENERATION_A);
      return mapper.countGenerationRowsOlderThan(candidate, 24);
    });
    assertThat(overdueRows).isEqualTo(2L);
  }

  @Test
  @DisplayName("候选回收后按创建时间和代际续扫，不跳过同时间代际或其他租户")
  void shouldResumeExpiredGenerationScanAfterReclaimedCandidate() {
    String tenantSql = "select set_config('app.tenant_id', ?, true)";
    String reclaimSql = """
        update batch.process_staging_generation set generation_status = 'RECLAIMED', sealed_at = now()
        where tenant_id = ? and generation_id = cast(? as uuid)
        """;
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    List<ProcessStagingGenerationCandidate> firstPage = transaction.execute(status -> {
      jdbcTemplate.queryForObject(tenantSql, String.class, "t1");
      return mapper.listExpiredGenerationsAfter(
          ProcessStagingExpiredScanQuery.firstPage("t1", 24, 1));
    });
    assertThat(firstPage).hasSize(1);
    ProcessStagingGenerationCandidate first = firstPage.getFirst();
    assertThat(first.getGenerationId()).isEqualTo(GENERATION_A);
    assertThat(first.getCreatedAt()).isNotNull();
    transaction.executeWithoutResult(status -> {
      jdbcTemplate.queryForObject(tenantSql, String.class, "t1");
      assertThat(jdbcTemplate.update(reclaimSql, "t1", GENERATION_A)).isEqualTo(1);
    });
    List<ProcessStagingGenerationCandidate> secondPage = transaction.execute(status -> {
      jdbcTemplate.queryForObject(tenantSql, String.class, "t1");
      return mapper.listExpiredGenerationsAfter(new ProcessStagingExpiredScanQuery(
          "t1", 24, 1, first.getCreatedAt(), first.getGenerationId()));
    });
    assertThat(secondPage)
        .extracting(ProcessStagingGenerationCandidate::getGenerationId)
        .containsExactly(GENERATION_A_OTHER);
    assertThat(secondPage.getFirst().getCreatedAt()).isEqualTo(first.getCreatedAt());
    List<ProcessStagingGenerationCandidate> afterSecond = transaction.execute(status -> {
      jdbcTemplate.queryForObject(tenantSql, String.class, "t1");
      ProcessStagingGenerationCandidate second = secondPage.getFirst();
      return mapper.listExpiredGenerationsAfter(new ProcessStagingExpiredScanQuery(
          "t1", 24, 1, second.getCreatedAt(), second.getGenerationId()));
    });
    assertThat(afterSecond).isEmpty();
  }

  @DisplayName("最老行观测按租户和 generation GUC 扫描全部活动代次")
  @Test
  void shouldObserveOldestStagedAtAcrossActiveGenerationsUnderStrictRls() {
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    List<ProcessStagingGenerationCandidate> generations = transaction.execute(status -> {
      final String selectSqlValue12 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue12, String.class);
      return mapper.listActiveGenerationsAfter("t1", null, 2);
    });

    assertThat(generations).hasSize(2);
    Instant oldest = transaction.execute(status -> {
      final String selectSqlValue13 = """
          select set_config('app.tenant_id', 't1', true)
      """;
      jdbcTemplate.queryForObject(selectSqlValue13, String.class);
      Instant result = null;
      String cursor = null;
      while (true) {
        List<ProcessStagingGenerationCandidate> page =
            mapper.listActiveGenerationsAfter("t1", cursor, 2);
        if (page.isEmpty()) {
          return result;
        }
        for (ProcessStagingGenerationCandidate generation : page) {
          mapper.setGenerationRlsContext(generation.getGenerationId());
          Instant observed = mapper.selectOldestGenerationRow(generation);
          boolean observedTimestampIsEarlier =
              observed != null && (result == null || observed.isBefore(result));
          if (observedTimestampIsEarlier) {
            result = observed;
          }
          cursor = generation.getGenerationId();
        }
        if (page.size() < 2) {
          return result;
        }
      }
    });

    assertThat(oldest).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
  }

  @DisplayName("普通业务角色不能读取其他租户的 generation 账本")
  @Test
  void shouldHideOtherTenantGenerationLedgerRowsUnderForcedRls() {
    List<String> visibleTenants = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource))
        .execute(status -> {
          final String sqlSql = """
              SET LOCAL ROLE process_staging_probe
          """;
          jdbcTemplate.execute(sqlSql);
          final String sqlSqlValue1 = """
              SET LOCAL app.tenant_id = 't1'
          """;
          jdbcTemplate.execute(sqlSqlValue1);
          final String selectBatchProcessStagingGenerationSqlValue14 = """
              select distinct tenant_id from batch.process_staging_generation order by tenant_id
          """;
          return jdbcTemplate.queryForList(
              selectBatchProcessStagingGenerationSqlValue14, String.class);
        });

    assertThat(visibleTenants).containsExactly("t1");
  }

  @DisplayName("普通业务角色不能为其他租户创建 generation 账本")
  @Test
  void shouldRejectCrossTenantGenerationLedgerInsertUnderForcedRls() {
    assertThatThrownBy(() -> new TransactionTemplate(new DataSourceTransactionManager(dataSource))
            .executeWithoutResult(status -> {
              final String sqlSqlValue2 = """
                  SET LOCAL ROLE process_staging_probe
              """;
              jdbcTemplate.execute(sqlSqlValue2);
              final String sqlSqlValue3 = """
                  SET LOCAL app.tenant_id = 't1'
              """;
              jdbcTemplate.execute(sqlSqlValue3);
              final String insertBatchProcessStagingGenerationSqlValue15 = """
                  insert into batch.process_staging_generation
                    (generation_id, tenant_id, job_task_id, batch_key, generation_status)
                  values (cast(? as uuid), 't2', 202, 'cross-tenant', 'OPEN')
                  """;
              jdbcTemplate.update(
                  insertBatchProcessStagingGenerationSqlValue15,
                  "40000000-0000-0000-0000-000000000001");
            }))
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @DisplayName("删除事务失败时 SEALED 状态与分块删除一起回滚")
  @Test
  void shouldRollbackSealAndDeleteTogetherOnBusinessTransactionFailure() {
    ProcessStagingGenerationCandidate candidate =
        new ProcessStagingGenerationCandidate("t1", 101L, GENERATION_A, "old-a");
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    assertThatThrownBy(() -> transaction.execute(status -> {
          final String selectSqlValue16 = """
              select set_config('app.tenant_id', 't1', true)
          """;
          jdbcTemplate.queryForObject(selectSqlValue16, String.class);
          mapper.selectGenerationStatusForUpdate(candidate);
          mapper.sealOpenGeneration(candidate);
          mapper.setGenerationRlsContext(GENERATION_A);
          mapper.deleteGenerationRows(candidate, 1);
          throw new IllegalStateException("injected business commit failure");
        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected business commit failure");

    final String selectBatchProcessStagingGenerationSqlValue17 = """
        select generation_status from batch.process_staging_generation where generation_id = cast(? as uuid)
    """;
    String selectedGenerationStatus = jdbcTemplate.queryForObject(
        selectBatchProcessStagingGenerationSqlValue17, String.class, GENERATION_A);
    assertThat(selectedGenerationStatus).isEqualTo("OPEN");
    final String selectBatchProcessStagingSqlValue18 = """
        select count(*) from batch.process_staging where batch_key = 'old-a'
    """;
    Long selectedProcessStagingCount =
        jdbcTemplate.queryForObject(selectBatchProcessStagingSqlValue18, Long.class);
    assertThat(selectedProcessStagingCount).isEqualTo(2L);
  }

  @DisplayName("创建分区时在同一事务内启用 strict 策略，直接读取叶子仍按租户隔离")
  @Test
  void shouldApplyStrictRlsToDailyPartitionBeforeReturning() {
    mapper.createDailyPartition(
        "process_staging_current", "2026-10-14 00:00:00+00", "2026-10-15 00:00:00+00");

    final String insertBatchProcessStagingSqlValue19 = """
        insert into batch.process_staging values
          ('tenant-a-key', cast(? as uuid), 11, 'tenant-a', 'biz', 'target', '{}', now()),
          ('tenant-a-other-generation', cast(? as uuid), 13, 'tenant-a', 'biz', 'target', '{}', now()),
          ('tenant-b-key', cast(? as uuid), 12, 'tenant-b', 'biz', 'target', '{}', now())
        """;
    jdbcTemplate.update(
        insertBatchProcessStagingSqlValue19, GENERATION_A, GENERATION_A_OTHER, GENERATION_B);
    final String grantSqlValue20 = """
        GRANT SELECT ON batch.process_staging_current TO process_staging_probe
    """;
    jdbcTemplate.execute(grantSqlValue20);

    final String selectPgPoliciesSql = """
        select c.relrowsecurity as enabled,
               c.relforcerowsecurity as forced,
               exists (
                 select 1 from pg_policies p
                 where p.schemaname = 'batch'
                   and p.tablename = 'process_staging_current'
                   and p.policyname = 'tenant_isolation_strict'
                   and p.qual like '%app.tenant_id%'
                   and p.with_check like '%app.tenant_id%'
               ) as policy_present
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'batch' and c.relname = 'process_staging_current'
        """;
    Map<String, Object> protection = jdbcTemplate.queryForMap(selectPgPoliciesSql);
    assertThat(protection)
        .containsEntry("enabled", true)
        .containsEntry("forced", true)
        .containsEntry("policy_present", true);

    Long visible = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .execute(status -> {
          final String sqlSqlValue4 = """
              SET LOCAL ROLE process_staging_probe
          """;
          jdbcTemplate.execute(sqlSqlValue4);
          final String sqlSqlValue5 = """
              SET LOCAL app.tenant_id = 'tenant-a'
          """;
          jdbcTemplate.execute(sqlSqlValue5);
          final String sqlSqlValue6 = """
              SET LOCAL app.process_staging_generation_id = '%s'\
              """.formatted(GENERATION_A);
          jdbcTemplate.execute(sqlSqlValue6);
          final String selectBatchProcessStagingCurrentSql = """
              select count(*) from batch.process_staging_current
          """;
          return jdbcTemplate.queryForObject(selectBatchProcessStagingCurrentSql, Long.class);
        });
    assertThat(visible).isEqualTo(1L);
  }

  @Test
  @DisplayName("新叶子创建与 strict RLS 同事务回滚，提交后普通角色的读写仍按租户隔离")
  void shouldCreateNewPartitionAndPolicyAtomicallyAndIsolateDirectWrites() {
    final String alterBatchProcessStagingSqlValue21 = """
        ALTER TABLE batch.process_staging DETACH PARTITION batch.process_staging_current
    """;
    jdbcTemplate.execute(alterBatchProcessStagingSqlValue21);
    ProcessStagingMapper transactionalMapper =
        new SqlSessionTemplate(sqlSessionFactory).getMapper(ProcessStagingMapper.class);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.executeWithoutResult(status -> {
      transactionalMapper.createDailyPartition(
          "process_staging_p20261016", "2026-10-16 00:00:00+00", "2026-10-17 00:00:00+00");
      status.setRollbackOnly();
    });
    final String selectSqlValue22 = """
        select to_regclass('batch.process_staging_p20261016')::text
    """;
    String resolvedStagingPartitionName =
        jdbcTemplate.queryForObject(selectSqlValue22, String.class);
    assertThat(resolvedStagingPartitionName).isNull();

    transactionalMapper.createDailyPartition(
        "process_staging_p20261016", "2026-10-16 00:00:00+00", "2026-10-17 00:00:00+00");
    final String insertBatchProcessStagingSqlValue23 = """
        insert into batch.process_staging values
          ('new-a', cast(? as uuid), 1, 'tenant-a', 'biz', 'target', '{}', '2026-10-16 01:00:00+00'),
          ('new-a-other-generation', cast(? as uuid), 3, 'tenant-a', 'biz', 'target', '{}',\s\
          '2026-10-16 01:00:00+00'),
          ('new-b', cast(? as uuid), 2, 'tenant-b', 'biz', 'target', '{}', '2026-10-16 01:00:00+00')
        """;
    jdbcTemplate.update(
        insertBatchProcessStagingSqlValue23, GENERATION_A, GENERATION_A_OTHER, GENERATION_B);
    final String grantSqlValue24 = """
        GRANT SELECT, INSERT, UPDATE, DELETE ON batch.process_staging_p20261016
        TO process_staging_probe
        """;
    jdbcTemplate.execute(grantSqlValue24);
    transaction.executeWithoutResult(status -> {
      final String sqlSqlValue7 = """
          SET LOCAL ROLE process_staging_probe
      """;
      jdbcTemplate.execute(sqlSqlValue7);
      final String sqlSqlValue8 = """
          SET LOCAL app.tenant_id = 'tenant-a'
      """;
      jdbcTemplate.execute(sqlSqlValue8);
      final String sqlSqlValue9 = """
          SET LOCAL app.process_staging_generation_id = '%s'\
          """.formatted(GENERATION_A);
      jdbcTemplate.execute(sqlSqlValue9);
      final String selectBatchProcessStagingP20261016Sql = """
          select batch_key from batch.process_staging_p20261016
      """;
      List<String> selectedBatchKeyValues =
          jdbcTemplate.queryForList(selectBatchProcessStagingP20261016Sql, String.class);
      assertThat(selectedBatchKeyValues).containsExactly("new-a");
      final String updateBatchProcessStagingP20261016Sql = """
          update batch.process_staging_p20261016 set target_table = 'changed'
          where batch_key = 'new-b'
          """;
      int affectedBatchProcessStagingP20261016Rows =
          jdbcTemplate.update(updateBatchProcessStagingP20261016Sql);
      assertThat(affectedBatchProcessStagingP20261016Rows).isZero();
      final String deleteBatchProcessStagingP20261016Sql = """
          delete from batch.process_staging_p20261016 where batch_key = 'new-b'
      """;
      int affectedBatchProcessStagingP20261016RowsWrite2 =
          jdbcTemplate.update(deleteBatchProcessStagingP20261016Sql);
      assertThat(affectedBatchProcessStagingP20261016RowsWrite2).isZero();
      final String updateBatchProcessStagingP20261016SqlValue25 = """
          update batch.process_staging_p20261016 set target_table = 'own-update'
          where batch_key = 'new-a'
          """;
      int affectedBatchProcessStagingP20261016RowsWrite3 =
          jdbcTemplate.update(updateBatchProcessStagingP20261016SqlValue25);
      assertThat(affectedBatchProcessStagingP20261016RowsWrite3).isEqualTo(1);
    });
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
          final String sqlSqlValue10 = """
              SET LOCAL ROLE process_staging_probe
          """;
          jdbcTemplate.execute(sqlSqlValue10);
          final String sqlSqlValue11 = """
              SET LOCAL app.tenant_id = 'tenant-a'
          """;
          jdbcTemplate.execute(sqlSqlValue11);
          final String insertBatchProcessStagingP20261016Sql = """
              insert into batch.process_staging_p20261016 values
                ('bad', cast(? as uuid), 3, 'tenant-b', 'biz', 'target', '{}', '2026-10-16 02:00:00+00')
              """;
          jdbcTemplate.update(insertBatchProcessStagingP20261016Sql, GENERATION_A);
        }))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    transaction.executeWithoutResult(status -> {
      final String sqlSqlValue12 = """
          SET LOCAL ROLE process_staging_probe
      """;
      jdbcTemplate.execute(sqlSqlValue12);
      final String sqlSqlValue13 = """
          SET LOCAL app.tenant_id = ''
      """;
      jdbcTemplate.execute(sqlSqlValue13);
      final String sqlSqlValue14 = """
          SET LOCAL app.process_staging_generation_id = ''
      """;
      jdbcTemplate.execute(sqlSqlValue14);
      final String selectBatchProcessStagingP20261016SqlValue26 = """
          select count(*) from batch.process_staging_p20261016
      """;
      Long selectedProcessStagingP20261016Count =
          jdbcTemplate.queryForObject(selectBatchProcessStagingP20261016SqlValue26, Long.class);
      assertThat(selectedProcessStagingP20261016Count).isZero();
    });
    final String selectBatchProcessStagingP20261016SqlValue27 = """
        select target_table from batch.process_staging_p20261016 order by batch_key
    """;
    List<String> selectedTargetTableValues =
        jdbcTemplate.queryForList(selectBatchProcessStagingP20261016SqlValue27, String.class);
    assertThat(selectedTargetTableValues).containsExactly("own-update", "target", "target");
  }

  @AfterEach
  void closeSession() {}
}
