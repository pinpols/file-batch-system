package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.engine.JobTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.task.JobInstanceTerminalStatusApplicationService;
import io.github.pinpols.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.outbox.poll-interval-millis=600000",
      "batch.outbox.min-poll-interval-millis=600000"
    })
@DisplayName("Job 终态事务 Outbox:真实 PostgreSQL 回滚、并发与首次事实快照")
class JobTerminalOutboxIntegrationTest extends AbstractIntegrationTest {
  private final JdbcTemplate jdbc;

  private final JobInstanceTerminalStatusApplicationService terminals;

  private final JobTerminalOutboxService writer;

  private final JobInstanceMapper jobs;

  private final LaunchService launches;

  private final PlatformTransactionManager transactions;

  private String tenant;
  private long id;

  @Autowired
  JobTerminalOutboxIntegrationTest(
      JdbcTemplate jdbc,
      JobInstanceTerminalStatusApplicationService terminals,
      JobTerminalOutboxService writer,
      JobInstanceMapper jobs,
      @Qualifier("defaultLaunchService") LaunchService launches,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.terminals = terminals;
    this.writer = writer;
    this.jobs = jobs;
    this.launches = launches;
    this.transactions = transactions;
  }

  @BeforeEach
  void seed() {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id,job_code,job_name,job_type,schedule_type,timezone)
        values (?, 'JOB_TERMINAL', 'Terminal source', 'GENERAL', 'MANUAL', 'UTC') returning id
        """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (tenant_id,job_definition_id,job_code,instance_no,biz_date,dedup_key,
          instance_status,trigger_type,version,started_at,run_attempt)
        values (?, ?, 'JOB_TERMINAL', ?, ?, ?, 'RUNNING', 'MANUAL', 0, current_timestamp, 1) returning id
        """;

    tenant = "jterm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    Long definition = jdbc.queryForObject(insertJobDefinitionSql, Long.class, tenant);
    id = jdbc.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        definition,
        tenant,
        LocalDate.of(2026, 10, 19),
        tenant);
  }

  @AfterEach
  void clean() {
    for (String table : List.of(
        "event_delivery_log",
        "outbox_event",
        "outbox_event_dedup_key",
        "job_partition",
        "job_execution_log",
        "job_instance",
        "job_definition")) {
      final String deleteStatementSql = """
              delete from batch.%s where tenant_id=?\
              """.formatted(table);
      jdbc.update(deleteStatementSql, tenant);
    }
  }

  @Test
  @DisplayName("终态 Outbox 故障整体回滚,相同期望版本恢复后只提交一次")
  void shouldRollbackJobWhenTerminalEventCannotBePersisted_andRetryOriginalCommand() {
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectVersionSql = """
        select version from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectPayloadJsonSql = """
        select payload_json->>'instanceStatus' from batch.outbox_event where tenant_id=?
    """;

    String trigger = "fail_" + tenant;
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$ begin if NEW.tenant_id='%s'\s\
        and NEW.event_type='JOB_TERMINAL' then raise exception 'injected Job terminal failure'; end if; return\s\
        NEW; end $$\
        """.formatted(trigger, tenant);
    jdbc.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.outbox_event for each row execute function batch.%s()\
        """.formatted(trigger, trigger);
    jdbc.execute(createTriggerSql);
    var command = command("FAILED", 0L);
    try {
      assertThatThrownBy(() -> terminals.updateTerminalStatusAndReconcileChildren(command))
          .isInstanceOf(DataAccessException.class);
      String selectedInstanceStatus =
          jdbc.queryForObject(selectInstanceStatusSql, String.class, tenant, id);
      assertThat(selectedInstanceStatus).isEqualTo("RUNNING");
      Long selectedVersion = jdbc.queryForObject(selectVersionSql, Long.class, tenant, id);
      assertThat(selectedVersion).isZero();
      assertThat(events()).isZero();
    } finally {
      final String dropTriggerSql = """
          drop trigger %s on batch.outbox_event\
          """.formatted(trigger);
      jdbc.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function batch.%s()\
          """.formatted(trigger);
      jdbc.execute(dropFunctionSql);
    }
    assertThat(terminals.updateTerminalStatusAndReconcileChildren(command)).isEqualTo(1);
    assertThat(terminals.updateTerminalStatusAndReconcileChildren(command)).isZero();
    assertThat(events()).isEqualTo(1);
    String selectedPayloadJson = jdbc.queryForObject(selectPayloadJsonSql, String.class, tenant);
    assertThat(selectedPayloadJson).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("相同终态重复收口保留首次快照,失败修正为成功记录另一 Job 事实")
  void shouldFreezeFirstFactPerState_andKeepFailureCorrectionDistinct() {
    final String selectPayloadJsonSql = """
        select payload_json::text from batch.outbox_event where tenant_id=?
    """;
    final String insertJobPartitionSql = """
        insert into batch.job_partition\s\
        (tenant_id,job_instance_id,job_instance_biz_date,partition_no,partition_status)
        values (?, ?, '2026-10-19', 0, 'SUCCESS')
        """;
    final String selectEventKeySql = """
        select event_key from batch.outbox_event where tenant_id=?
    """;

    assertThat(terminals.updateTerminalStatusAndReconcileChildren(command("FAILED", 0L)))
        .isEqualTo(1);
    String original = jdbc.queryForObject(selectPayloadJsonSql, String.class, tenant);
    assertThat(terminals.updateTerminalStatusAndReconcileChildren(command("FAILED", 1L)))
        .isEqualTo(1);
    assertThat(events()).isEqualTo(1);
    String selectedPayloadJson = jdbc.queryForObject(selectPayloadJsonSql, String.class, tenant);
    assertThat(selectedPayloadJson).isEqualTo(original);
    // 普通终态服务不可把 FAILED 改成 SUCCESS;修正必须走带分区结果守卫的进度写入口。
    assertThat(terminals.updateTerminalStatusAndReconcileChildren(command("SUCCESS", 2L)))
        .isZero();
    jdbc.update(insertJobPartitionSql, tenant, id);
    // 这里只验证 REPORT 使用的 Mapper/Outbox 事务切片;完整重试/补偿晋升仍由状态机测试证明。
    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      assertThat(jobs.updateProgress(UpdateInstanceProgressParam.builder()
              .tenantId(tenant)
              .id(id)
              .instanceStatus("SUCCESS")
              .successPartitionCount(1)
              .failedPartitionCount(0)
              .cancelledPartitionCount(0)
              .terminatedPartitionCount(0)
              .expectedVersion(2L)
              .finishedAt(Instant.now())
              .build()))
          .isEqualTo(1);
      writer.writeTerminalEvent(tenant, id);
    });
    assertThat(events()).isEqualTo(2);
    List<String> selectedEventKeyValues =
        jdbc.queryForList(selectEventKeySql, String.class, tenant);
    assertThat(selectedEventKeyValues)
        .containsExactlyInAnyOrder(
            tenant + ":job:" + id + ":terminal:FAILED",
            tenant + ":job:" + id + ":terminal:SUCCESS");
  }

  @Test
  @DisplayName("并发同版本终态服务只允许一个 CAS,事务外调用不能写 Outbox")
  void shouldSerializeConcurrentCommandsAndRequireCallerTransaction() throws Exception {
    assertThatThrownBy(() -> writer.writeTerminalEvent(tenant, id))
        .isInstanceOf(IllegalTransactionStateException.class);
    var command = command("TERMINATED", 0L);
    var first = CompletableFuture.supplyAsync(
        () -> terminals.updateTerminalStatusAndReconcileChildren(command));
    var second = CompletableFuture.supplyAsync(
        () -> terminals.updateTerminalStatusAndReconcileChildren(command));
    assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder(1, 0);
    assertThat(events()).isEqualTo(1);
  }

  @Test
  @DisplayName("派发业务拒绝的补偿事务也必须提交 Job 失败事实,计数满足当前数据库契约")
  void shouldProduceTerminalFactForDispatchBusinessRejection() {
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status='CREATED',started_at=null where tenant_id=? and id=?
    """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectCancelledPartitionCountSql = """
        select cancelled_partition_count,terminated_partition_count from batch.job_instance where tenant_id=?\s\
        and id=?
    """;

    jdbc.update(updateJobInstanceSql, tenant, id);
    var request = LaunchRequest.builder()
        .tenantId(tenant)
        .jobCode("JOB_TERMINAL")
        .bizDate(LocalDate.of(2026, 10, 19))
        .triggerType(TriggerType.MANUAL)
        .requestId("rejected-" + tenant)
        .traceId("dispatch-reject")
        .params(Map.of())
        .build();
    launches.finalizeDispatchFailure(
        request,
        jobs.selectById(tenant, id),
        BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.partition.dispatch_business_error",
            "POLICY_REJECTED",
            "policy rejected"));
    String selectedInstanceStatus =
        jdbc.queryForObject(selectInstanceStatusSql, String.class, tenant, id);
    assertThat(selectedInstanceStatus).isEqualTo("FAILED");
    assertThat(events()).isEqualTo(1);
    Map<String, Object> selectedCancelledPartitionCountRow =
        jdbc.queryForMap(selectCancelledPartitionCountSql, tenant, id);
    assertThat(selectedCancelledPartitionCountRow)
        .containsEntry("cancelled_partition_count", 0)
        .containsEntry("terminated_partition_count", 0);
  }

  @Test
  @DisplayName("演练派发被业务规则拒绝时，实例与终态事件均标记演练失败")
  void shouldProduceDryRunTerminalFactForDispatchBusinessRejection() {
    final String prepareSql = """
        update batch.job_instance set instance_status='CREATED', started_at=null, dry_run=true
        where tenant_id=? and id=?
        """;
    final String statusSql =
        "select instance_status from batch.job_instance where tenant_id=? and id=?";
    final String payloadSql =
        "select payload_json->>'instanceStatus' from batch.outbox_event where tenant_id=? and event_type='JOB_TERMINAL'";
    jdbc.update(prepareSql, tenant, id);
    var request = LaunchRequest.builder()
        .tenantId(tenant)
        .jobCode("JOB_TERMINAL")
        .bizDate(LocalDate.of(2026, 10, 19))
        .triggerType(TriggerType.MANUAL)
        .requestId("dry-rejected-" + tenant)
        .traceId("dry-reject")
        .dryRun(true)
        .params(Map.of())
        .build();

    launches.finalizeDispatchFailure(
        request,
        jobs.selectById(tenant, id),
        BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.partition.dispatch_business_error",
            "POLICY_REJECTED",
            "policy rejected"));

    assertThat(jdbc.queryForObject(statusSql, String.class, tenant, id))
        .isEqualTo("FAILED_DRY_RUN");
    assertThat(jdbc.queryForObject(payloadSql, String.class, tenant)).isEqualTo("FAILED_DRY_RUN");
  }

  private JobInstanceTerminalStatusCommand command(String status, long version) {
    return new JobInstanceTerminalStatusCommand(tenant, id, status, Instant.now(), version);
  }

  private int events() {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id=? and event_type='JOB_TERMINAL'
    """;

    return jdbc.queryForObject(selectCountSql, Integer.class, tenant);
  }
}
