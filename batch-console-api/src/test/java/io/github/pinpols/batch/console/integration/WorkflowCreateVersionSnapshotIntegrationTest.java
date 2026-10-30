package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkflowCreateVersionSnapshotIntegrationTest.VersionFaultConfiguration.class)
@DisplayName("普通创建 Workflow 的 v1 快照 PostgreSQL 提交与回滚合同")
class WorkflowCreateVersionSnapshotIntegrationTest extends AbstractMutationIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private WorkflowDefinitionMapper definitionMapper;

  @Autowired
  private WorkflowDefinitionVersionMapper versionMapper;

  @Autowired
  private WorkflowNodeMapper nodeMapper;

  @Autowired
  private WorkflowEdgeMapper edgeMapper;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private VersionInsertFault insertFault;

  @Autowired
  private SqlSessionFactory sessions;

  private final Set<Long> ownedDefinitionIds = ConcurrentHashMap.newKeySet();
  private String workflowCode;
  private String calendarCode;

  @BeforeEach
  void seedOwnedCalendar() {
    String insertCalendarSql = """
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
            catch_up_policy, catch_up_max_days, enabled
        ) values (?, ?, 'Create v1 contract', 'UTC', 'SKIP', 'NONE', 0, true)
        """;
    List<Interceptor> configuredInterceptors = sessions.getConfiguration().getInterceptors();
    assertThat(configuredInterceptors).contains(insertFault);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    workflowCode = "b192_v1_" + suffix;
    calendarCode = "b192_v1_cal_" + suffix;
    jdbcTemplate.update(insertCalendarSql, TENANT, calendarCode);
  }

  @AfterEach
  void cleanOwnedRows() {
    insertFault.disarm();
    if (EmptyChecks.isNull(workflowCode)) {
      return;
    }
    String selectOwnedIdsSql = """
        select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    String deleteVersionSql = """
        delete from batch.workflow_definition_version where tenant_id = ? and workflow_code = ?
        """;
    String deleteEdgesSql = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
        """;
    String deleteNodesSql = """
        delete from batch.workflow_node where tenant_id = ? and workflow_definition_id = ?
        """;
    String deleteDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    String deleteCalendarSql = """
        delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
        """;
    List<Long> committedIds =
        jdbcTemplate.queryForList(selectOwnedIdsSql, Long.class, TENANT, workflowCode);
    ownedDefinitionIds.addAll(committedIds);
    jdbcTemplate.update(deleteVersionSql, TENANT, workflowCode);
    for (Long id : ownedDefinitionIds) {
      jdbcTemplate.update(deleteEdgesSql, TENANT, id);
      jdbcTemplate.update(deleteNodesSql, TENANT, id);
    }
    jdbcTemplate.update(deleteDefinitionSql, TENANT, workflowCode);
    jdbcTemplate.update(deleteCalendarSql, TENANT, calendarCode);
    ownedDefinitionIds.clear();
  }

  @Test
  @DisplayName("真实 HTTP 创建后，v1 元数据与已提交节点边是同一份事实")
  void shouldCommitInitialSnapshotWithCreatedDefinitionAndGraph() throws Exception {
    String body = createBody();
    String intentKey = "v1-create-" + workflowCode;

    postCreate(body, intentKey).expectStatus().isOk();

    assertCommittedSnapshot();
  }

  @Test
  @DisplayName("版本真实写入后故障，整个创建回滚，原意图重试可完整创建")
  void shouldRollbackAllCreationRowsAndAllowRetryAfterSnapshotWasWritten() throws Exception {
    insertFault.arm(TENANT, workflowCode);
    String body = createBody();
    String intentKey = "v1-retry-" + workflowCode;

    postCreate(body, intentKey).expectStatus().is5xxServerError();

    int visibleVersionsBeforeFailure = insertFault.visibleVersionsBeforeFailure();
    assertThat(visibleVersionsBeforeFailure).isEqualTo(1);
    Long attemptedId = insertFault.attemptedDefinitionId();
    assertThat(attemptedId).isNotNull();
    ownedDefinitionIds.add(attemptedId);
    assertNoCreationRows(attemptedId);

    postCreate(body, intentKey).expectStatus().isOk();

    assertCommittedSnapshot();
  }

  private void assertCommittedSnapshot() throws Exception {
    String selectIdSql = """
        select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    Long id = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, workflowCode);
    ownedDefinitionIds.add(id);
    WorkflowDefinitionEntity definition = definitionMapper.selectById(TENANT, id);
    assertThat(definition.getVersion()).isEqualTo(1);
    List<WorkflowDefinitionVersionEntity> versions = versionMapper.listByDefinitionId(TENANT, id);
    assertThat(versions).hasSize(1);
    WorkflowDefinitionVersionEntity snapshot = versions.getFirst();
    assertThat(snapshot.getTenantId()).isEqualTo(TENANT);
    assertThat(snapshot.getWorkflowDefinitionId()).isEqualTo(id);
    assertThat(snapshot.getWorkflowCode()).isEqualTo(workflowCode);
    assertThat(snapshot.getVersion()).isEqualTo(1);
    assertThat(snapshot.getWorkflowName()).isEqualTo(definition.getWorkflowName());
    assertThat(snapshot.getWorkflowType()).isEqualTo("DAG");
    assertThat(snapshot.getWorkflowType()).isEqualTo(definition.getWorkflowType());
    assertThat(snapshot.getEnabled()).isTrue();
    assertThat(snapshot.getEnabled()).isEqualTo(definition.getEnabled());
    assertThat(snapshot.getScheduleEnabled()).isTrue();
    assertThat(snapshot.getScheduleEnabled()).isEqualTo(definition.getScheduleEnabled());
    assertThat(snapshot.getScheduleType()).isEqualTo("CRON");
    assertThat(snapshot.getScheduleExpr()).isEqualTo("0 0 1 * * ?");
    assertThat(snapshot.getTimezone()).isEqualTo("UTC");
    assertThat(snapshot.getTriggerMode()).isEqualTo("SCHEDULED");
    assertThat(snapshot.getCalendarCode()).isEqualTo(calendarCode);
    assertThat(snapshot.getScheduleType()).isEqualTo(definition.getScheduleType());
    assertThat(snapshot.getScheduleExpr()).isEqualTo(definition.getScheduleExpr());
    assertThat(snapshot.getTimezone()).isEqualTo(definition.getTimezone());
    assertThat(snapshot.getTriggerMode()).isEqualTo(definition.getTriggerMode());
    assertThat(snapshot.getCalendarCode()).isEqualTo(definition.getCalendarCode());
    assertThat(snapshot.getDependsOnJobCode()).isEqualTo(definition.getDependsOnJobCode());
    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(TENANT, id, null);
    WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.ofDefinition(TENANT, id, null);
    List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(nodeQuery);
    List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(edgeQuery);
    assertThat(nodes)
        .extracting(WorkflowNodeEntity::getNodeCode)
        .containsExactlyInAnyOrder("start", "end");
    assertThat(edges).hasSize(1);
    // JSONB 不区分 Java 的 Long/Integer 节点，双方使用同一 JSON 解码边界比较全部字段。
    String actualNodesJson = objectMapper.writeValueAsString(nodes);
    String actualEdgesJson = objectMapper.writeValueAsString(edges);
    JsonNode actualNodes = objectMapper.readTree(actualNodesJson);
    JsonNode actualEdges = objectMapper.readTree(actualEdgesJson);
    JsonNode frozenNodes = objectMapper.readTree(snapshot.getNodesJson());
    JsonNode frozenEdges = objectMapper.readTree(snapshot.getEdgesJson());
    assertThat(frozenNodes).isEqualTo(actualNodes);
    assertThat(frozenEdges).isEqualTo(actualEdges);
  }

  private void assertNoCreationRows(Long attemptedId) {
    String definitionCountSql = """
        select count(*) from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    String versionCountSql = """
        select count(*) from batch.workflow_definition_version where tenant_id = ? and workflow_code = ?
        """;
    String nodeCountSql = """
        select count(*) from batch.workflow_node where tenant_id = ? and workflow_definition_id = ?
        """;
    String edgeCountSql = """
        select count(*) from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
        """;
    Long definitions =
        jdbcTemplate.queryForObject(definitionCountSql, Long.class, TENANT, workflowCode);
    Long versions = jdbcTemplate.queryForObject(versionCountSql, Long.class, TENANT, workflowCode);
    Long nodes = jdbcTemplate.queryForObject(nodeCountSql, Long.class, TENANT, attemptedId);
    Long edges = jdbcTemplate.queryForObject(edgeCountSql, Long.class, TENANT, attemptedId);
    assertThat(definitions).isZero();
    assertThat(versions).isZero();
    assertThat(nodes).isZero();
    assertThat(edges).isZero();
  }

  private WebTestClient.ResponseSpec postCreate(String body, String intentKey) {
    return client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, intentKey)
        .header("X-Console-User", "b192-v1-contract")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private String createBody() {
    return """
        {
          "tenantId": "%s",
          "workflowCode": "%s",
          "workflowName": "Create v1 contract",
          "scheduleType": "CRON",
          "scheduleExpr": "0 0 1 * * ?",
          "timezone": "UTC",
          "calendarCode": "%s",
          "nodes": [
            {"nodeCode": "start", "nodeName": "Start", "nodeType": "START"},
            {"nodeCode": "end", "nodeName": "End", "nodeType": "END"}
          ],
          "edges": [{"fromNodeCode": "start", "toNodeCode": "end"}]
        }
        """.formatted(TENANT, workflowCode, calendarCode);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class VersionFaultConfiguration {
    @Bean
    VersionInsertFault initialWorkflowVersionInsertFault() {
      return new VersionInsertFault();
    }
  }

  /** 只在本例版本语句真实执行后制造故障，连接仍归原 MyBatis 事务所有。 */
  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "update",
          args = {MappedStatement.class, Object.class}))
  static final class VersionInsertFault implements Interceptor {
    private static final String INSERT_ID =
        WorkflowDefinitionVersionMapper.class.getName() + ".insertVersionSnapshot";
    private final AtomicReference<FaultTarget> active = new AtomicReference<>();
    private final AtomicBoolean failNext = new AtomicBoolean();
    private final AtomicInteger visibleVersions = new AtomicInteger();
    private final AtomicReference<Long> attemptedId = new AtomicReference<>();

    private record FaultTarget(String tenantId, String workflowCode) {}

    void arm(String tenantId, String workflowCode) {
      visibleVersions.set(0);
      attemptedId.set(null);
      failNext.set(true);
      active.set(new FaultTarget(tenantId, workflowCode));
    }

    void disarm() {
      active.set(null);
      failNext.set(false);
    }

    int visibleVersionsBeforeFailure() {
      return visibleVersions.get();
    }

    Long attemptedDefinitionId() {
      return attemptedId.get();
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      MappedStatement mapped = (MappedStatement) invocation.getArgs()[0];
      FaultTarget target = active.get();
      if (!INSERT_ID.equals(mapped.getId()) || EmptyChecks.isNull(target)) {
        return invocation.proceed();
      }
      Object parameter = invocation.getArgs()[1];
      if (!(parameter instanceof Map<?, ?> named)
          || !(named.get("p") instanceof WorkflowDefinitionVersionInsertParam version)
          || !Objects.equals(target.tenantId(), version.getTenantId())
          || !Objects.equals(target.workflowCode(), version.getWorkflowCode())) {
        return invocation.proceed();
      }
      Object result = invocation.proceed();
      if (failNext.compareAndSet(true, false)) {
        attemptedId.set(version.getWorkflowDefinitionId());
        Executor executor = (Executor) invocation.getTarget();
        int rows = countInsertedVersion(executor, version);
        visibleVersions.set(rows);
        throw new IllegalStateException("B192 injected failure after real version INSERT");
      }
      return result;
    }

    private int countInsertedVersion(
        Executor executor, WorkflowDefinitionVersionInsertParam version) throws SQLException {
      String countInsertedVersionSql = """
          select count(*) from batch.workflow_definition_version
          where tenant_id = ? and workflow_definition_id = ? and version = ?
          """;
      Transaction transaction = executor.getTransaction();
      Connection connection = transaction.getConnection();
      String tenantId = version.getTenantId();
      Long definitionId = version.getWorkflowDefinitionId();
      Integer versionNo = version.getVersion();
      try (PreparedStatement statement = connection.prepareStatement(countInsertedVersionSql)) {
        statement.setString(1, tenantId);
        statement.setLong(2, definitionId);
        statement.setInt(3, versionNo);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw new IllegalStateException("version INSERT visibility query returned no row");
          }
          return rows.getInt(1);
        }
      }
    }
  }
}
