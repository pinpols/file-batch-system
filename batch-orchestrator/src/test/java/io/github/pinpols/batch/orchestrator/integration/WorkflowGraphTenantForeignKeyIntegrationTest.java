package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 用真实 SQL 与双事务证明 Workflow 强从属关系的租户身份，不依赖应用提前校验。 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Workflow 图与等待记录的父定义租户外键")
class WorkflowGraphTenantForeignKeyIntegrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbc;
  private String tenant;
  private String otherTenant;
  private Long definitionId;
  private Long otherDefinitionId;

  enum Relation {
    NODE("workflow_node"),
    EDGE("workflow_edge"),
    WAIT("workflow_launch_wait");

    private final String table;

    Relation(String table) {
      this.table = table;
    }
  }

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
  void seedOwnedDefinitions() {
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
          values (?,'CAL_FK','FK calendar','UTC',true)
          """;

    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    tenant = "graph-fk-" + UUID.randomUUID();
    otherTenant = "graph-fk-" + UUID.randomUUID();
    for (String calendarTenant : List.of(tenant, otherTenant, tenant + "-moved")) {
      jdbc.update(insertBusinessCalendarSql, calendarTenant);
    }
    definitionId = insertDefinition(tenant);
    otherDefinitionId = insertDefinition(otherTenant);
  }

  @AfterEach
  void removeOwnedFixture() {
    final String deleteExternalEventExpectationDefinitionSql = """
        delete from batch.external_event_expectation_definition where tenant_id in (?, ?)
    """;
    final String deleteFileGroupExpectationDefinitionSql = """
        delete from batch.file_group_expectation_definition where tenant_id in (?, ?)
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id in (?, ?)
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where id in (?, ?)
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id in (?, ?, ?)
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id in (?, ?, ?)
    """;

    jdbc.update(deleteExternalEventExpectationDefinitionSql, tenant, otherTenant);
    jdbc.update(deleteFileGroupExpectationDefinitionSql, tenant, otherTenant);
    for (Relation relation : Relation.values()) {
      final String deleteStatementSql = """
          delete from batch.%s where workflow_definition_id in (?, ?)\
          """.formatted(relation.table);
      jdbc.update(deleteStatementSql, definitionId, otherDefinitionId);
    }
    jdbc.update(deleteTriggerRequestSql, tenant, otherTenant);
    jdbc.update(deleteWorkflowDefinitionSql, definitionId, otherDefinitionId);
    jdbc.update(deleteBatchDayCatchupControlSql, tenant, otherTenant, tenant + "-moved");
    jdbc.update(deleteBusinessCalendarSql, tenant, otherTenant, tenant + "-moved");
    dataSource.destroy();
  }

  @ParameterizedTest
  @EnumSource(Relation.class)
  @DisplayName("同租户插入合法，错租户插入和更换父定义均由数据库拒绝")
  void shouldRejectCrossTenantInsertAndReparent(Relation relation) {
    Long childId = insertChild(jdbc, relation, tenant, definitionId, "valid");

    assertThatThrownBy(() -> insertChild(jdbc, relation, otherTenant, definitionId, "foreign"))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
    String reparentChildSql = """
        update batch.%s set workflow_definition_id=? where id=?\
        """.formatted(relation.table);
    assertThatThrownBy(() -> jdbc.update(reparentChildSql, otherDefinitionId, childId))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

    final String selectWorkflowDefinitionIdSql = """
        select workflow_definition_id from batch.%s where id=?\
        """.formatted(relation.table);
    Long selectedWorkflowDefinitionId =
        jdbc.queryForObject(selectWorkflowDefinitionIdSql, Long.class, childId);
    assertThat(selectedWorkflowDefinitionId).isEqualTo(definitionId);
  }

  @ParameterizedTest
  @EnumSource(Relation.class)
  @DisplayName("父删除保持原有 NO ACTION，显式清理子记录和请求后才可删除父定义")
  void shouldPreserveExplicitChildCleanupBeforeParentDelete(Relation relation) {
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=?
    """;

    Long childId = insertChild(jdbc, relation, tenant, definitionId, "valid");
    assertThatThrownBy(() -> jdbc.update(deleteWorkflowDefinitionSql, definitionId))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
    final String deleteStatementSql = """
        delete from batch.%s where id=?\
        """.formatted(relation.table);
    int affectedStatementRows = jdbc.update(deleteStatementSql, childId);
    assertThat(affectedStatementRows).isEqualTo(1);
    jdbc.update(deleteTriggerRequestSql, tenant);
    int affectedWorkflowDefinitionRows = jdbc.update(deleteWorkflowDefinitionSql, definitionId);
    assertThat(affectedWorkflowDefinitionRows).isEqualTo(1);
  }

  @Test
  @DisplayName("实际目录只保留已验证的租户复合外键，子侧关联索引可用")
  void shouldInstallValidatedTenantForeignKeys() {
    final String selectConnameSql = """
          select c.conname, c.convalidated, c.confdeltype::text as delete_action,
                 pg_get_constraintdef(c.oid) as definition
          from pg_constraint c where c.conrelid=cast(? as regclass) and c.contype='f'
            and c.confrelid='batch.workflow_definition'::regclass
          """;
    final String selectCountSql = """
          select count(*) from pg_index i where i.indrelid=cast(? as regclass) and i.indisvalid
            and pg_get_indexdef(i.indexrelid) like '%(tenant_id, workflow_definition_id%'
          """;

    for (Relation relation : Relation.values()) {
      List<Map<String, Object>> constraints =
          jdbc.queryForList(selectConnameSql, "batch." + relation.table);
      assertThat(constraints).singleElement().satisfies(row -> {
        assertThat(row).containsEntry("convalidated", true).containsEntry("delete_action", "a");
        assertThat(row.get("definition").toString())
            .contains(
                "FOREIGN KEY (tenant_id, workflow_definition_id)",
                "REFERENCES batch.workflow_definition(tenant_id, id)");
      });
      Long selectedPgIndexCount =
          jdbc.queryForObject(selectCountSql, Long.class, "batch." + relation.table);
      assertThat(selectedPgIndexCount).isPositive();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"external_event_expectation_definition", "file_group_expectation_definition"})
  @DisplayName("预期定义必须匹配同一Workflow日历，显式删除后才能变更父日历")
  void shouldBindExpectationCalendarAndAllowChangeAfterExplicitDelete(String table) {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code='OTHER_CALENDAR' where tenant_id=? and id=?
    """;

    insertExpectation(jdbc, table, tenant, "CAL_FK", definitionId, "expectation-a");

    assertThatThrownBy(() -> insertExpectation(
            jdbc, table, tenant, "OTHER_CALENDAR", definitionId, "wrong-calendar"))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
    assertThatThrownBy(() -> jdbc.update(updateWorkflowDefinitionSql, tenant, definitionId))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

    final String deleteStatementSql = """
        delete from batch.%s where tenant_id=? and workflow_definition_id=?\
        """.formatted(table);
    int affectedStatementRows = jdbc.update(deleteStatementSql, tenant, definitionId);
    assertThat(affectedStatementRows).isEqualTo(1);
    int affectedWorkflowDefinitionRows =
        jdbc.update(updateWorkflowDefinitionSql, tenant, definitionId);
    assertThat(affectedWorkflowDefinitionRows).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"external_event_expectation_definition", "file_group_expectation_definition"})
  @DisplayName("迁移安装已验证的同租户三列日历外键")
  void shouldInstallValidatedCalendarIdentityForeignKey(String table) {
    final String selectConnameSql = """
        select conname, convalidated, confdeltype::text as delete_action,
               confupdtype::text as update_action, pg_get_constraintdef(oid) as definition
          from pg_constraint
         where conrelid=cast(? as regclass)
           and conname like 'fk_%_expectation_workflow_calendar'
        """;

    List<Map<String, Object>> constraints = jdbc.queryForList(selectConnameSql, "batch." + table);

    assertThat(constraints).singleElement().satisfies(row -> {
      assertThat(row)
          .containsEntry("convalidated", true)
          .containsEntry("delete_action", "a")
          .containsEntry("update_action", "a");
      assertThat(row.get("definition").toString())
          .contains(
              "FOREIGN KEY (tenant_id, workflow_definition_id, calendar_code)",
              "REFERENCES batch.workflow_definition(tenant_id, id, calendar_code)");
    });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"external_event_expectation_definition", "file_group_expectation_definition"})
  @DisplayName("预期子写与父日历更新并发时，父更新先提交后子写由复合FK拒绝")
  void shouldSerializeCalendarUpdateAgainstConcurrentExpectationInsert(String table)
      throws Exception {
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code='OTHER_CALENDAR' where tenant_id=? and id=?
        """;
    final String selectCoalesceSql = """
            select coalesce(? = any(pg_blocking_pids(?)), false)
        """;
    final String selectCountSql = """
            select count(*) from batch.external_event_expectation_definition where workflow_definition_id=?
        """;
    final String selectCountSql2 = """
            select count(*) from batch.file_group_expectation_definition where workflow_definition_id=?
        """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = POSTGRES.createConnection("");
        Connection child = POSTGRES.createConnection("")) {
      parent.setAutoCommit(false);
      child.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      JdbcTemplate childJdbc = new JdbcTemplate(new SingleConnectionDataSource(child, true));
      Integer parentPid = parentJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      Integer childPid = childJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      parentJdbc.update(updateWorkflowDefinitionSql, tenant, definitionId);
      Future<?> insert = executor.submit(
          () -> insertExpectation(childJdbc, table, tenant, "CAL_FK", definitionId, "racing"));
      try {
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> insert.isDone()
                || Boolean.TRUE.equals(
                    jdbc.queryForObject(selectCoalesceSql, Boolean.class, parentPid, childPid)));
        Boolean selectedCoalesce =
            jdbc.queryForObject(selectCoalesceSql, Boolean.class, parentPid, childPid);
        assertThat(selectedCoalesce).isTrue();
        assertThat(insert.isDone()).isFalse();
        parent.commit();
        assertThatThrownBy(() -> insert.get(10, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
      } finally {
        parent.rollback();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        child.rollback();
      }
    } finally {
      executor.shutdownNow();
    }
    Long selectedCount = jdbc.queryForObject(selectCountSql, Long.class, definitionId);
    Long selectedCount2 = jdbc.queryForObject(selectCountSql2, Long.class, definitionId);
    assertThat(selectedCount + selectedCount2).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"external_event_expectation_definition", "file_group_expectation_definition"})
  @DisplayName("预期定义先写入时父日历更新等待后被复合FK拒绝")
  void shouldRejectConcurrentCalendarUpdateAfterExpectationInsert(String table) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code='OTHER_CALENDAR' where tenant_id=? and id=?
    """;
    final String selectCoalesceSql = """
        select coalesce(? = any(pg_blocking_pids(?)), false)
    """;
    final String selectCalendarCodeSql = """
        select calendar_code from batch.workflow_definition where tenant_id=? and id=?
    """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = POSTGRES.createConnection("");
        Connection child = POSTGRES.createConnection("")) {
      parent.setAutoCommit(false);
      child.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      JdbcTemplate childJdbc = new JdbcTemplate(new SingleConnectionDataSource(child, true));
      Integer parentPid = parentJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      Integer childPid = childJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      insertExpectation(childJdbc, table, tenant, "CAL_FK", definitionId, "racing-first");
      Future<?> update = executor.submit(
          () -> parentJdbc.update(updateWorkflowDefinitionSql, tenant, definitionId));
      try {
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> update.isDone()
                || Boolean.TRUE.equals(
                    jdbc.queryForObject(selectCoalesceSql, Boolean.class, childPid, parentPid)));
        Boolean selectedCoalesce =
            jdbc.queryForObject(selectCoalesceSql, Boolean.class, childPid, parentPid);
        assertThat(selectedCoalesce).isTrue();
        assertThat(update.isDone()).isFalse();
        child.commit();
        assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
      } finally {
        parent.rollback();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        child.rollback();
      }
    } finally {
      executor.shutdownNow();
    }
    String selectedCalendarCode =
        jdbc.queryForObject(selectCalendarCodeSql, String.class, tenant, definitionId);
    assertThat(selectedCalendarCode).isEqualTo("CAL_FK");
  }

  private void insertExpectation(
      JdbcTemplate target,
      String table,
      String tenantId,
      String calendarCode,
      Long parentId,
      String code) {
    final String insertExternalEventExpectationDefinitionSql = """
          insert into batch.external_event_expectation_definition
            (tenant_id, calendar_code, workflow_definition_id, event_code, expected_local_time)
          values (?, ?, ?, ?, '09:00')
          """;
    final String insertFileGroupExpectationDefinitionSql = """
          insert into batch.file_group_expectation_definition
            (tenant_id, calendar_code, workflow_definition_id, file_group_code, expected_local_time)
          values (?, ?, ?, ?, '09:00')
          """;

    if ("external_event_expectation_definition".equals(table)) {
      target.update(
          insertExternalEventExpectationDefinitionSql, tenantId, calendarCode, parentId, code);
    } else if ("file_group_expectation_definition".equals(table)) {
      target.update(
          insertFileGroupExpectationDefinitionSql, tenantId, calendarCode, parentId, code);
    } else {
      throw new IllegalArgumentException("unsupported expectation table");
    }
  }

  @Test
  @DisplayName("父租户变更与旧租户子写入交错时，子事务等待后由 FK 拒绝")
  void shouldSerializeParentTenantChangeWithChildInsert() throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set tenant_id=? where id=?
    """;
    final String selectCoalesceSql = """
        select coalesce(? = any(pg_blocking_pids(?)), false)
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node where workflow_definition_id=?
    """;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = POSTGRES.createConnection("");
        Connection child = POSTGRES.createConnection("")) {
      parent.setAutoCommit(false);
      child.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      JdbcTemplate childJdbc = new JdbcTemplate(new SingleConnectionDataSource(child, true));
      Integer parentPid = parentJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      Integer childPid = childJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      parentJdbc.update(updateWorkflowDefinitionSql, tenant + "-moved", definitionId);
      Future<Long> inserted = executor.submit(
          () -> insertChild(childJdbc, Relation.NODE, tenant, definitionId, "racing"));
      try {
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> inserted.isDone()
                || Boolean.TRUE.equals(
                    jdbc.queryForObject(selectCoalesceSql, Boolean.class, parentPid, childPid)));
        Boolean selectedCoalesce =
            jdbc.queryForObject(selectCoalesceSql, Boolean.class, parentPid, childPid);
        assertThat(selectedCoalesce).isTrue();
        assertThat(inserted.isDone()).isFalse();
        parent.commit();
        assertThatThrownBy(() -> inserted.get(10, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
      } finally {
        parent.rollback();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        child.rollback();
      }
    } finally {
      executor.shutdownNow();
    }
    Long selectedWorkflowNodeCount = jdbc.queryForObject(selectCountSql, Long.class, definitionId);
    assertThat(selectedWorkflowNodeCount).isZero();
  }

  private Long insertDefinition(String tenantId) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,workflow_type,version,enabled,calendar_code)
        values (?,'FK_WORKFLOW','FK workflow','DAG',1,false,'CAL_FK') returning id
        """;

    return jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenantId);
  }

  private Long insertChild(
      JdbcTemplate target, Relation relation, String tenantId, Long parentId, String code) {
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request(tenant_id,request_id,trigger_type,workflow_definition_id,
            biz_date,dedup_key,request_status)
          values (?,?,'API',?,'2026-10-18',?,'ACCEPTED')
          """;

    if (relation == Relation.WAIT) {
      Long requestDefinition = tenantId.equals(tenant) ? definitionId : otherDefinitionId;
      target.update(insertTriggerRequestSql, tenantId, code, requestDefinition, code);
    }
    String sql =
        switch (relation) {
          case NODE -> """
          insert into batch.workflow_node(tenant_id,workflow_definition_id,node_code,node_name,node_type)
          values (?, ?, ?, 'FK node', 'START') returning id
          """;
          case EDGE -> """
          insert into batch.workflow_edge(tenant_id,workflow_definition_id,from_node_code,to_node_code)
          values (?, ?, ?, 'end') returning id
          """;
          case WAIT -> """
          insert into batch.workflow_launch_wait(tenant_id,workflow_definition_id,request_id,biz_date,launch_payload)
          values (?, ?, ?, '2026-10-18', '{}') returning id
          """;
        };
    return target.queryForObject(sql, Long.class, tenantId, parentId, code);
  }
}
