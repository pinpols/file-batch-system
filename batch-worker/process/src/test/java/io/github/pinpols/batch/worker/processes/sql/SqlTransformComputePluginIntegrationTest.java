package io.github.pinpols.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.metrics.ProcessMetrics;
import io.github.pinpols.batch.worker.processes.stage.ProcessRuntimeKeys;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * SqlTransformComputePlugin WAP+bookends 端到端集成测试。
 *
 * <p>每个测试都通过 prepare → compute → validate → commit → feedback 五个 lifecycle 方法,验证:
 *
 * <ul>
 *   <li>JSONB 模式:compute 写 batch.process_staging,validate 跑用户 checkSql,commit 用
 *       jsonb_populate_record 发布,feedback 清 staging
 *   <li>DIRECT 模式:compute/validate/feedback 不碰 staging,commit 用 INSERT...SELECT 直接发布
 *   <li>水位通过 attributes 传递回 worker(由 worker 上报 orchestrator)
 * </ul>
 *
 * <p><b>故意不继承 {@code AbstractIntegrationTest}</b>：每个 @BeforeEach 都 {@code DROP SCHEMA biz CASCADE}
 * + {@code DROP SCHEMA batch CASCADE} 重建结构。共享 {@code businessPostgres} 里 {@code
 * biz.customer_account} 等 是其它测试依赖的种子数据；共享 {@code platformPostgres} 里 {@code batch.shedlock /
 * outbox_event} 等是其它测试运行时状态。 在共享容器上跑会把这些数据全删干净，破坏并行 / 后续测试。
 */
@DisplayName("SQL 转换计算插件:五阶段端到端链路、校验中止、租户与目标过滤、水位推进及直接模式")
class SqlTransformComputePluginIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private JdbcTemplate jdbcTemplate;
  private DriverManagerDataSource dataSource;
  private SqlTransformComputePlugin plugin;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void setUp() {
    RlsTenantContextHolder.set("t1");
    dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    plugin = new SqlTransformComputePlugin(
        dataSource, new ObjectMapper(), security, ProcessMetrics.noop());

    String dropBizSchemaSql = """
        drop schema if exists biz cascade
        """;
    jdbcTemplate.execute(dropBizSchemaSql);
    String dropBatchSchemaSql = """
        drop schema if exists batch cascade
        """;
    jdbcTemplate.execute(dropBatchSchemaSql);
    String createBizSchemaSql = """
        create schema biz
        """;
    jdbcTemplate.execute(createBizSchemaSql);
    String createBatchSchemaSql = """
        create schema batch
        """;
    jdbcTemplate.execute(createBatchSchemaSql);
    String createStagingGenerationTableSql = """
        create table batch.process_staging_generation (
          generation_id uuid primary key,
          tenant_id text not null,
          job_task_id bigint,
          batch_key text not null,
          generation_status text not null,
          created_at timestamptz not null default now(),
          sealed_at timestamptz,
          check (generation_status in ('OPEN', 'SEALED')),
          check ((generation_status = 'SEALED') = (sealed_at is not null)),
          unique (tenant_id, generation_id, batch_key)
        )
        """;
    jdbcTemplate.execute(createStagingGenerationTableSql);
    String createProcessStagingTableSql = """
        create table batch.process_staging (
          batch_key text not null,
          staging_generation_id uuid not null,
          row_seq bigserial not null,
          tenant_id text not null,
          target_schema text not null,
          target_table text not null,
          payload jsonb not null,
          staged_at timestamptz not null default now(),
          primary key (batch_key, staging_generation_id, row_seq),
          constraint fk_process_staging_generation_identity
            foreign key (tenant_id, staging_generation_id, batch_key)
            references batch.process_staging_generation (tenant_id, generation_id, batch_key)
            on delete no action
        )
        """;
    jdbcTemplate.execute(createProcessStagingTableSql);
    String createGenerationGuardFunctionSql = """
        create function batch.guard_process_staging_generation()
        returns trigger language plpgsql as $$
        begin
          perform 1 from batch.process_staging_generation generation
          where generation.generation_id = new.staging_generation_id
            and generation.tenant_id = new.tenant_id
            and generation.batch_key = new.batch_key
            and generation.generation_status = 'OPEN'
          for share;
          if not found then
            raise exception 'PROCESS staging generation is missing, mismatched, or sealed'
              using errcode = '23503', constraint = 'fk_process_staging_generation_open';
          end if;
          return new;
        end
        $$
        """;
    jdbcTemplate.execute(createGenerationGuardFunctionSql);
    String createGenerationGuardTriggerSql = """
        create trigger trg_process_staging_generation_guard
        before insert or update of batch_key, staging_generation_id, tenant_id
        on batch.process_staging for each row
        execute function batch.guard_process_staging_generation()
        """;
    jdbcTemplate.execute(createGenerationGuardTriggerSql);
    String createOrderEventTableSql = """
        create table biz.order_event (
          tenant_id varchar(32) not null,
          account_id varchar(32) not null,
          event_id bigint not null,
          amount numeric(18, 2) not null
        )
        """;
    jdbcTemplate.execute(createOrderEventTableSql);
    String createAccountSummaryTableSql = """
        create table biz.account_summary (
          tenant_id varchar(32) not null,
          account_id varchar(32) not null,
          total_amount numeric(18, 2) not null,
          high_water_mark bigint not null,
          primary key (tenant_id, account_id)
        )
        """;
    jdbcTemplate.execute(createAccountSummaryTableSql);
    String insertOrderEventSql = """
        insert into biz.order_event values (?, ?, ?, ?)
        """;
    jdbcTemplate.update(insertOrderEventSql, "t1", "A", 1L, new BigDecimal("10.00"));
    jdbcTemplate.update(insertOrderEventSql, "t1", "A", 2L, new BigDecimal("20.00"));
    jdbcTemplate.update(insertOrderEventSql, "t1", "B", 3L, new BigDecimal("7.00"));
    jdbcTemplate.update(insertOrderEventSql, "t2", "X", 9L, new BigDecimal("99.00"));
  }

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("五阶段全链路成功:目标表落两行聚合结果,暂存清空,行数、处理数与水位都写回上下文")
  void wapLifecycle_executesEndToEnd_andAdvancesWatermark() {
    ProcessJobContext context = newContextWithSpec();

    plugin.prepare(context);
    ProcessStageResult compute = plugin.compute(context);
    ProcessStageResult validate = plugin.validate(context);
    ProcessStageResult commit = plugin.commit(context);
    ProcessStageResult feedback = plugin.feedback(context);

    assertThat(compute.success()).isTrue();
    assertThat(validate.success()).isTrue();
    assertThat(commit.success()).isTrue();
    assertThat(feedback.success()).isTrue();

    // 业务结果落到目标表
    String selectAccountAAmountSql = """
        select total_amount from biz.account_summary where account_id='A'
        """;
    BigDecimal accountAAmount =
        jdbcTemplate.queryForObject(selectAccountAAmountSql, BigDecimal.class);
    assertThat(accountAAmount).isEqualByComparingTo("20.00");
    String selectAccountBAmountSql = """
        select total_amount from biz.account_summary where account_id='B'
        """;
    BigDecimal accountBAmount =
        jdbcTemplate.queryForObject(selectAccountBAmountSql, BigDecimal.class);
    assertThat(accountBAmount).isEqualByComparingTo("7.00");
    String accountSummaryCountSql = """
            select count(*) from biz.account_summary
            """;
    Integer accountSummaryCount =
        jdbcTemplate.queryForObject(accountSummaryCountSql, Integer.class);
    assertThat(accountSummaryCount).isEqualTo(2);

    // staging 已被 feedback 清空
    String stagingCountSql = """
            select count(*) from batch.process_staging
            """;
    Integer remainingStagingCount = jdbcTemplate.queryForObject(stagingCountSql, Integer.class);
    assertThat(remainingStagingCount).isZero();

    // 水位、行数都正确暴露在 context attributes
    assertThat(context.getAttributes())
        .containsEntry(ProcessRuntimeKeys.PROCESS_STAGED_COUNT, 2)
        .containsEntry(ProcessRuntimeKeys.PROCESS_PUBLISHED_COUNT, 2)
        .containsEntry("processedCount", 2)
        .containsEntry(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, "3");
  }

  @Test
  @DisplayName("用户校验规则不通过时校验失败并中止提交:目标表为空,暂存两行保留待取证")
  void validate_failsAndAbortsBeforeCommit_whenUserRuleReturnsFalse() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> stepParams = currentStepParams(context);
    Map<String, Object> spec = nestedSpec(stepParams);
    String validationSql = """
        select bool_and((payload->>'total_amount')::numeric > 100) AS pass,
               'total_amount must exceed 100' AS message
        from batch.process_staging
        where batch_key = :batchKey
          and staging_generation_id = :stagingGenerationId
        """;
    spec.put(
        "validations", List.of(Map.of("name", "non_negative_total", "checkSql", validationSql)));

    plugin.prepare(context);
    plugin.compute(context);
    ProcessStageResult validate = plugin.validate(context);

    assertThat(validate.success()).isFalse();
    assertThat(validate.code()).isEqualTo("PROCESS_VALIDATION_FAILED");
    assertThat(validate.message()).contains("non_negative_total").contains("must exceed 100");

    // commit 没跑,target 应为空
    String accountSummaryCountSql = """
            select count(*) from biz.account_summary
            """;
    Integer accountSummaryCount =
        jdbcTemplate.queryForObject(accountSummaryCountSql, Integer.class);
    assertThat(accountSummaryCount).isZero();
    // staging 仍有 2 行(留作 forensics,直到下次 prepare/feedback 才清)
    String stagingCountSql = """
            select count(*) from batch.process_staging
            """;
    Integer remainingStagingCount = jdbcTemplate.queryForObject(stagingCountSql, Integer.class);
    assertThat(remainingStagingCount).isEqualTo(2);
  }

  @Test
  @DisplayName("校验后暂存行被删除时提交失败并回滚,不能把零行发布报告为成功")
  void commit_failsAndRollsBack_whenValidatedStagingRowsDisappear() {
    ProcessJobContext context = newContextWithSpec();
    plugin.prepare(context);
    plugin.compute(context);
    assertThat(plugin.validate(context).success()).isTrue();
    String batchKey = context.getBatchKey();
    String deleteStagingSql = """
        delete from batch.process_staging where batch_key = ?
        """;
    jdbcTemplate.update(deleteStagingSql, batchKey);

    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    assertThatThrownBy(() -> transaction.execute(status -> plugin.commit(context)))
        .isInstanceOf(BizException.class)
        .satisfies(error -> assertThat(((BizException) error).getMessageKey())
            .isEqualTo("error.process.staging.changed"));

    String accountSummaryCountSql = """
            select count(*) from biz.account_summary
            """;
    Integer accountSummaryCount =
        jdbcTemplate.queryForObject(accountSummaryCountSql, Integer.class);
    assertThat(accountSummaryCount).isZero();
  }

  @Test
  @DisplayName("提交与反馈按租户与目标过滤:其它租户的暂存行既不发布也不被清理")
  void commitAndFeedback_filterStagingByTenantAndTarget() {
    ProcessJobContext context = newContextWithSpec();

    plugin.prepare(context);
    plugin.compute(context);
    String otherGeneration = UUID.randomUUID().toString();
    String insertOtherGenerationSql = """
        insert into batch.process_staging_generation (
          generation_id, tenant_id, batch_key, generation_status)
        values (cast(? as uuid), 't2', ?, 'OPEN')
        """;
    jdbcTemplate.update(insertOtherGenerationSql, otherGeneration, context.getBatchKey());
    String insertOtherTenantStagingSql = """
        insert into batch.process_staging (
          batch_key, staging_generation_id, tenant_id, target_schema, target_table, payload)
        values (?, cast(? as uuid), 't2', 'biz', 'account_summary',
                '{"tenant_id":"t2","account_id":"X","total_amount":99.00,"high_water_mark":9}'::jsonb)
        """;
    jdbcTemplate.update(insertOtherTenantStagingSql, context.getBatchKey(), otherGeneration);
    ProcessStageResult validate = plugin.validate(context);
    ProcessStageResult commit = plugin.commit(context);
    ProcessStageResult feedback = plugin.feedback(context);

    assertThat(validate.success()).isTrue();
    assertThat(commit.success()).isTrue();
    assertThat(feedback.success()).isTrue();
    String otherTenantPublishedCountSql = """
        select count(*) from biz.account_summary where tenant_id='t2'
        """;
    Integer otherTenantPublishedCount =
        jdbcTemplate.queryForObject(otherTenantPublishedCountSql, Integer.class);
    assertThat(otherTenantPublishedCount).isZero();
    String otherTenantStagingCountSql = """
        select count(*) from batch.process_staging where tenant_id='t2'
        """;
    Integer otherTenantStagingCount =
        jdbcTemplate.queryForObject(otherTenantStagingCountSql, Integer.class);
    assertThat(otherTenantStagingCount).isEqualTo(1);
  }

  @Test
  @DisplayName("相同 batchKey 的不同 generation 互相隔离,提交与清理只影响当前代次")
  void stagingOperations_areScopedToGeneration_evenWhenBatchKeyIsReused() {
    ProcessJobContext first = newContextWithSpec();
    ProcessJobContext second = newContextWithSpec();
    second.setBatchKey(first.getBatchKey());

    plugin.prepare(first);
    plugin.compute(first);
    plugin.prepare(second);
    plugin.compute(second);
    assertThat(second.getStagingGenerationId()).isNotEqualTo(first.getStagingGenerationId());
    String batchKey = first.getBatchKey();
    String stagingCountForBatchSql = """
        select count(*) from batch.process_staging where batch_key = ?
        """;
    Integer stagingCountForBatch =
        jdbcTemplate.queryForObject(stagingCountForBatchSql, Integer.class, batchKey);
    assertThat(stagingCountForBatch).isEqualTo(4);

    assertThat(plugin.validate(first).success()).isTrue();
    assertThat(plugin.commit(first).success()).isTrue();
    String firstGenerationId = first.getStagingGenerationId();
    String generationStagingCountSql = """
        select count(*) from batch.process_staging
        where staging_generation_id = cast(? as uuid)
        """;
    Integer firstGenerationStagingCount =
        jdbcTemplate.queryForObject(generationStagingCountSql, Integer.class, firstGenerationId);
    assertThat(firstGenerationStagingCount).isZero();
    String secondGenerationId = second.getStagingGenerationId();
    Integer secondGenerationStagingCount =
        jdbcTemplate.queryForObject(generationStagingCountSql, Integer.class, secondGenerationId);
    assertThat(secondGenerationStagingCount).isEqualTo(2);
    assertThat(plugin.feedback(first).success()).isTrue();
    Integer remainingSecondGenerationStagingCount =
        jdbcTemplate.queryForObject(generationStagingCountSql, Integer.class, secondGenerationId);
    assertThat(remainingSecondGenerationStagingCount).isEqualTo(2);
  }

  @Test
  @DisplayName("已封口 generation 拒绝迟到的 COMPUTE 写入")
  void compute_rejectsSealedGenerationBeforeWritingStaging() {
    ProcessJobContext context = newContextWithSpec();
    String insertSealedGenerationSql = """
        insert into batch.process_staging_generation (
          generation_id, tenant_id, batch_key, generation_status, sealed_at)
        values (cast(? as uuid), ?, ?, 'SEALED', now())
        """;
    jdbcTemplate.update(
        insertSealedGenerationSql,
        context.getStagingGenerationId(),
        context.getTenantId(),
        context.getBatchKey());
    plugin.prepare(context);

    assertThatThrownBy(() -> plugin.compute(context))
        .isInstanceOf(BizException.class)
        .satisfies(error -> assertThat(((BizException) error).getMessageKey())
            .isEqualTo("error.process.staging_generation_missing"));
    String stagingGenerationId = context.getStagingGenerationId();
    String generationStagingCountSql = """
        select count(*) from batch.process_staging
        where staging_generation_id = cast(? as uuid)
        """;
    Integer generationStagingCount =
        jdbcTemplate.queryForObject(generationStagingCountSql, Integer.class, stagingGenerationId);
    assertThat(generationStagingCount).isZero();
  }

  @Test
  @DisplayName("声明式复合外键保护暂存代次归属,清理行前禁止删除generation账本")
  void stagingGenerationForeignKey_rejectsOrphansAndProtectsReferencedGeneration() {
    ProcessJobContext context = newContextWithSpec();
    plugin.prepare(context);
    plugin.compute(context);

    String generationConstraintCountSql = """
            select count(*) from pg_constraint
            where conname = 'fk_process_staging_generation_identity'
            """;
    Integer generationConstraintCount =
        jdbcTemplate.queryForObject(generationConstraintCountSql, Integer.class);
    assertThat(generationConstraintCount).isEqualTo(1);
    String deleteReferencedGenerationSql = """
        delete from batch.process_staging_generation
        where generation_id = cast(? as uuid)
        """;
    assertThatThrownBy(() ->
            jdbcTemplate.update(deleteReferencedGenerationSql, context.getStagingGenerationId()))
        .isInstanceOf(DataIntegrityViolationException.class);

    String deleteGenerationStagingSql = """
        delete from batch.process_staging
        where staging_generation_id = cast(? as uuid)
        """;
    jdbcTemplate.update(deleteGenerationStagingSql, context.getStagingGenerationId());
    String deleteGenerationSql = """
        delete from batch.process_staging_generation
        where generation_id = cast(? as uuid)
        """;
    Integer deletedGenerationCount =
        jdbcTemplate.update(deleteGenerationSql, context.getStagingGenerationId());
    assertThat(deletedGenerationCount).isEqualTo(1);
  }

  @Test
  @DisplayName("水位取自暂存行而不是再查一次源表,本批水位推进到二")
  void compute_advancesWatermarkFromStagingRows_notSecondSourceQuery() {
    String dropWatermarkSequenceSql = """
        drop sequence if exists biz.watermark_seq
        """;
    jdbcTemplate.execute(dropWatermarkSequenceSql);
    String createWatermarkSequenceSql = """
        create sequence biz.watermark_seq start 1 increment 1
        """;
    jdbcTemplate.execute(createWatermarkSequenceSql);
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    String sourceSql = """
        select tenant_id,
               account_id,
               sum(amount) as total_amount,
               nextval('biz.watermark_seq') as high_water_mark
        from biz.order_event
        where tenant_id = :tenantId
          and event_id > cast(:highWaterMarkIn as bigint)
        group by tenant_id, account_id
        order by account_id
        """;
    spec.put("sourceSql", sourceSql);

    plugin.prepare(context);
    ProcessStageResult compute = plugin.compute(context);

    assertThat(compute.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, "2");
  }

  @Test
  @DisplayName("空结果策略为成功且无新数据时校验通过:暂存与处理数均为零,也不产生新水位")
  void validate_allowsEmptyResultWhenPolicyIsSuccess() {
    ProcessJobContext context = newContextWithSpec();
    context.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_IN, 999L);
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("emptyResultPolicy", "SUCCESS");

    plugin.prepare(context);
    ProcessStageResult compute = plugin.compute(context);
    ProcessStageResult validate = plugin.validate(context);

    assertThat(compute.success()).isTrue();
    assertThat(validate.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(ProcessRuntimeKeys.PROCESS_STAGED_COUNT, 0)
        .containsEntry("processedCount", 0);
    assertThat(context.getAttributes()).doesNotContainKey(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT);
  }

  @Test
  @DisplayName("目标表不存在时准备阶段快速失败:给出目标表未找到的错误键与表名参数,暂存保持为空")
  void prepare_failsFast_whenTargetTableDoesNotExist() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("targetTable", "nonexistent_target");

    assertThatThrownBy(() -> plugin.prepare(context))
        .isInstanceOf(BizException.class)
        .satisfies(t -> {
          BizException ex = (BizException) t;
          assertThat(ex.getMessageKey()).isEqualTo("error.process.target_table_not_found");
          assertThat(ex.getMessageArgs()).contains("nonexistent_target");
        });
    // staging 完全空(prepare 早停)
    String stagingCountSql = """
            select count(*) from batch.process_staging
            """;
    Integer remainingStagingCount = jdbcTemplate.queryForObject(stagingCountSql, Integer.class);
    assertThat(remainingStagingCount).isZero();
  }

  @Test
  @DisplayName("目标库不在允许清单内时准备阶段快速失败,并说明该库未被允许")
  void prepare_failsFast_whenSchemaNotInAllowlist() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("targetSchema", "forbidden");

    assertThatThrownBy(() -> plugin.prepare(context))
        .hasMessageContaining("schema not allowlisted")
        .hasMessageContaining("forbidden");
  }

  @Test
  @DisplayName("暂存行数超过上限时计算失败,并立即清理本批暂存以免污染后续阶段,目标表仍为空")
  void compute_failsAndCleansStaging_whenStagedRowsExceedMaxStagedRows() {
    // P1-6:把 maxStagedRows 设为 1,源数据 t1 有 A/B 两个 account → 聚合后 2 行 staging > 1 → overflow
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("maxStagedRows", 1);

    plugin.prepare(context);
    ProcessStageResult compute = plugin.compute(context);

    assertThat(compute.success()).isFalse();
    assertThat(compute.code()).isEqualTo("PROCESS_STAGED_OVERFLOW");
    assertThat(compute.message()).contains("exceeded maxStagedRows 1");
    // 立即清本批 staging,不会污染后续 stage
    String currentBatchStagingCountSql = """
        select count(*) from batch.process_staging where batch_key = 'batch-test-1'
        """;
    Integer currentBatchStagingCount =
        jdbcTemplate.queryForObject(currentBatchStagingCountSql, Integer.class);
    assertThat(currentBatchStagingCount).isZero();
    // target 表仍然为空
    String accountSummaryCountSql = """
            select count(*) from biz.account_summary
            """;
    Integer accountSummaryCount =
        jdbcTemplate.queryForObject(accountSummaryCountSql, Integer.class);
    assertThat(accountSummaryCount).isZero();
  }

  @Test
  @DisplayName("直接写入模式下不落暂存也能发布:目标表两行结果正确,行数、处理数与水位照常回写")
  void directMode_publishesWithoutJsonbStaging() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("stagingMode", "DIRECT");

    plugin.prepare(context);
    ProcessStageResult compute = plugin.compute(context);
    ProcessStageResult validate = plugin.validate(context);
    ProcessStageResult commit = plugin.commit(context);
    ProcessStageResult feedback = plugin.feedback(context);

    assertThat(compute.success()).isTrue();
    assertThat(validate.success()).isTrue();
    assertThat(commit.success()).isTrue();
    assertThat(feedback.success()).isTrue();
    String stagingCountSql = """
            select count(*) from batch.process_staging
            """;
    Integer remainingStagingCount = jdbcTemplate.queryForObject(stagingCountSql, Integer.class);
    assertThat(remainingStagingCount).isZero();
    String accountSummaryCountSql = """
            select count(*) from biz.account_summary
            """;
    Integer accountSummaryCount =
        jdbcTemplate.queryForObject(accountSummaryCountSql, Integer.class);
    assertThat(accountSummaryCount).isEqualTo(2);
    String selectAccountAAmountSql = """
        select total_amount from biz.account_summary where account_id='A'
        """;
    BigDecimal accountAAmount =
        jdbcTemplate.queryForObject(selectAccountAAmountSql, BigDecimal.class);
    assertThat(accountAAmount).isEqualByComparingTo("20.00");
    assertThat(context.getAttributes())
        .containsEntry(ProcessRuntimeKeys.PROCESS_STAGED_COUNT, 2)
        .containsEntry(ProcessRuntimeKeys.PROCESS_PUBLISHED_COUNT, 2)
        .containsEntry("processedCount", 2)
        .containsEntry(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, "3");
  }

  @Test
  @DisplayName("发布语句在冲突更新之前按冲突列排序暂存记录,保证同键多行顺序稳定")
  void buildPublishSql_ordersStagedRowsByConflictColumnsBeforeUpsert() {
    ProcessJobContext context = newContextWithSpec();
    SqlTransformComputeSpec spec =
        SqlTransformComputeSpec.parse(currentStepParams(context), new ObjectMapper());

    String sql = SqlTransformComputeSqlBuilder.buildPublishSql(spec);

    assertThat(sql)
        .contains("ORDER BY (rec).\"tenant_id\", (rec).\"account_id\"")
        .contains("ON CONFLICT (\"tenant_id\", \"account_id\") DO UPDATE SET");
  }

  @Test
  @DisplayName("直接发布语句在冲突更新之前按冲突列排序源记录,保证同键多行顺序稳定")
  void buildDirectPublishSql_ordersSourceRowsByConflictColumnsBeforeUpsert() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> specMap = nestedSpec(currentStepParams(context));
    specMap.put("stagingMode", "DIRECT");
    SqlTransformComputeSpec spec =
        SqlTransformComputeSpec.parse(currentStepParams(context), new ObjectMapper());

    String sql = SqlTransformComputeSqlBuilder.buildDirectPublishSql(spec);

    assertThat(sql)
        .contains("ORDER BY base.\"tenant_id\", base.\"account_id\"")
        .contains("ON CONFLICT (\"tenant_id\", \"account_id\") DO UPDATE SET");
  }

  // ─── 辅助 ────────────────────────────────────────────────────────────────────

  private ProcessJobContext newContextWithSpec() {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId("t1");
    context.setJobCode("JOB_PROCESS");
    context.setWorkerId("process-test");
    context.setBatchKey("batch-test-1");
    context.setStagingGenerationId(UUID.randomUUID().toString());
    context.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_IN, 1L);
    Map<String, Object> sqlTransformSpec = new LinkedHashMap<>();
    String sourceSql = """
        select tenant_id,
               account_id,
               sum(amount) as total_amount,
               max(event_id) as high_water_mark
        from biz.order_event
        where tenant_id = :tenantId
          and event_id > cast(:highWaterMarkIn as bigint)
        group by tenant_id, account_id
        """;
    sqlTransformSpec.put("sourceSql", sourceSql);
    sqlTransformSpec.put("targetSchema", "biz");
    sqlTransformSpec.put("targetTable", "account_summary");
    sqlTransformSpec.put("writeMode", "UPSERT");
    sqlTransformSpec.put(
        "columns",
        List.of(
            Map.of("source", "tenant_id", "target", "tenant_id"),
            Map.of("source", "account_id", "target", "account_id"),
            Map.of("source", "total_amount", "target", "total_amount"),
            Map.of("source", "high_water_mark", "target", "high_water_mark")));
    sqlTransformSpec.put("conflictColumns", List.of("tenant_id", "account_id"));
    sqlTransformSpec.put("watermarkColumn", "high_water_mark");
    Map<String, Object> stepParams = new LinkedHashMap<>();
    stepParams.put("sqlTransformCompute", sqlTransformSpec);
    context.getAttributes().put(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS, stepParams);
    return context;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> currentStepParams(ProcessJobContext context) {
    return (Map<String, Object>)
        context.getAttributes().get(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> nestedSpec(Map<String, Object> stepParams) {
    return (Map<String, Object>) stepParams.get("sqlTransformCompute");
  }
}
