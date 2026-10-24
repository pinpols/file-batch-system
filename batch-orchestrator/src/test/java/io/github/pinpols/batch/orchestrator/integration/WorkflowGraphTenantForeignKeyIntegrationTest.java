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
    NODE("workflow_node"), EDGE("workflow_edge"), WAIT("workflow_launch_wait");

    private final String table;

    Relation(String table) {
      this.table = table;
    }
  }

  @BeforeAll
  static void migrateFreshDatabase() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz").defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load().migrate();
  }

  @BeforeEach
  void seedOwnedDefinitions() {
    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    tenant = "graph-fk-" + UUID.randomUUID();
    otherTenant = "graph-fk-" + UUID.randomUUID();
    for (String calendarTenant : List.of(tenant, otherTenant, tenant + "-moved")) {
      jdbc.update("""
          insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
          values (?,'CAL_FK','FK calendar','UTC',true)
          """, calendarTenant);
    }
    definitionId = insertDefinition(tenant);
    otherDefinitionId = insertDefinition(otherTenant);
  }

  @AfterEach
  void removeOwnedFixture() {
    for (Relation relation : Relation.values()) {
      jdbc.update("delete from batch." + relation.table + " where workflow_definition_id in (?, ?)",
          definitionId, otherDefinitionId);
    }
    jdbc.update("delete from batch.trigger_request where tenant_id in (?, ?)", tenant, otherTenant);
    jdbc.update("delete from batch.workflow_definition where id in (?, ?)", definitionId, otherDefinitionId);
    jdbc.update("delete from batch.business_calendar where tenant_id in (?, ?, ?)",
        tenant, otherTenant, tenant + "-moved");
    dataSource.destroy();
  }

  @ParameterizedTest
  @EnumSource(Relation.class)
  @DisplayName("同租户插入合法，错租户插入和更换父定义均由数据库拒绝")
  void shouldRejectCrossTenantInsertAndReparent(Relation relation) {
    Long childId = insertChild(jdbc, relation, tenant, definitionId, "valid");

    assertThatThrownBy(() -> insertChild(jdbc, relation, otherTenant, definitionId, "foreign"))
        .rootCause().isInstanceOfSatisfying(SQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
    assertThatThrownBy(() -> jdbc.update(
        "update batch." + relation.table + " set workflow_definition_id=? where id=?",
        otherDefinitionId, childId))
        .rootCause().isInstanceOfSatisfying(SQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

    assertThat(jdbc.queryForObject(
        "select workflow_definition_id from batch." + relation.table + " where id=?", Long.class, childId))
        .isEqualTo(definitionId);
  }

  @ParameterizedTest
  @EnumSource(Relation.class)
  @DisplayName("父删除保持原有 NO ACTION，显式清理子记录和请求后才可删除父定义")
  void shouldPreserveExplicitChildCleanupBeforeParentDelete(Relation relation) {
    Long childId = insertChild(jdbc, relation, tenant, definitionId, "valid");
    assertThatThrownBy(() -> jdbc.update("delete from batch.workflow_definition where id=?", definitionId))
        .rootCause().isInstanceOfSatisfying(SQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
    assertThat(jdbc.update("delete from batch." + relation.table + " where id=?", childId)).isEqualTo(1);
    jdbc.update("delete from batch.trigger_request where tenant_id=?", tenant);
    assertThat(jdbc.update("delete from batch.workflow_definition where id=?", definitionId)).isEqualTo(1);
  }

  @Test
  @DisplayName("实际目录只保留已验证的租户复合外键，子侧关联索引可用")
  void shouldInstallValidatedTenantForeignKeys() {
    for (Relation relation : Relation.values()) {
      List<Map<String, Object>> constraints = jdbc.queryForList("""
          select c.conname, c.convalidated, c.confdeltype::text as delete_action,
                 pg_get_constraintdef(c.oid) as definition
          from pg_constraint c where c.conrelid=cast(? as regclass) and c.contype='f'
            and c.confrelid='batch.workflow_definition'::regclass
          """, "batch." + relation.table);
      assertThat(constraints).singleElement().satisfies(row -> {
        assertThat(row).containsEntry("convalidated", true).containsEntry("delete_action", "a");
        assertThat(row.get("definition").toString())
            .contains("FOREIGN KEY (tenant_id, workflow_definition_id)",
                "REFERENCES batch.workflow_definition(tenant_id, id)");
      });
      assertThat(jdbc.queryForObject("""
          select count(*) from pg_index i where i.indrelid=cast(? as regclass) and i.indisvalid
            and pg_get_indexdef(i.indexrelid) like '%(tenant_id, workflow_definition_id%'
          """, Long.class, "batch." + relation.table)).isPositive();
    }
  }

  @Test
  @DisplayName("父租户变更与旧租户子写入交错时，子事务等待后由 FK 拒绝")
  void shouldSerializeParentTenantChangeWithChildInsert() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = POSTGRES.createConnection("");
        Connection child = POSTGRES.createConnection("")) {
      parent.setAutoCommit(false);
      child.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      JdbcTemplate childJdbc = new JdbcTemplate(new SingleConnectionDataSource(child, true));
      Integer parentPid = parentJdbc.queryForObject("select pg_backend_pid()", Integer.class);
      Integer childPid = childJdbc.queryForObject("select pg_backend_pid()", Integer.class);
      parentJdbc.update("update batch.workflow_definition set tenant_id=? where id=?", tenant + "-moved", definitionId);
      Future<Long> inserted = executor.submit(() ->
          insertChild(childJdbc, Relation.NODE, tenant, definitionId, "racing"));
      try {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
            inserted.isDone() || Boolean.TRUE.equals(jdbc.queryForObject(
                "select coalesce(? = any(pg_blocking_pids(?)), false)",
                Boolean.class, parentPid, childPid)));
        assertThat(jdbc.queryForObject(
            "select coalesce(? = any(pg_blocking_pids(?)), false)",
            Boolean.class, parentPid, childPid)).isTrue();
        assertThat(inserted.isDone()).isFalse();
        parent.commit();
        assertThatThrownBy(() -> inserted.get(10, TimeUnit.SECONDS))
            .rootCause().isInstanceOfSatisfying(SQLException.class,
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
    assertThat(jdbc.queryForObject(
        "select count(*) from batch.workflow_node where workflow_definition_id=?", Long.class, definitionId))
        .isZero();
  }

  private Long insertDefinition(String tenantId) {
    return jdbc.queryForObject("""
        insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,workflow_type,version,enabled,calendar_code)
        values (?,'FK_WORKFLOW','FK workflow','DAG',1,false,'CAL_FK') returning id
        """, Long.class, tenantId);
  }

  private Long insertChild(JdbcTemplate target, Relation relation, String tenantId, Long parentId, String code) {
    if (relation == Relation.WAIT) {
      Long requestDefinition = tenantId.equals(tenant) ? definitionId : otherDefinitionId;
      target.update("""
          insert into batch.trigger_request(tenant_id,request_id,trigger_type,workflow_definition_id,
            biz_date,dedup_key,request_status)
          values (?,?,'API',?,'2026-10-18',?,'ACCEPTED')
          """, tenantId, code, requestDefinition, code);
    }
    String sql = switch (relation) {
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
