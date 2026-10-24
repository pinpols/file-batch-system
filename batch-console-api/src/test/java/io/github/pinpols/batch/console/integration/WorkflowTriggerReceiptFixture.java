package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowTriggerStatusResponse;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowTriggerReceiptMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** CI 与已有本地平台库复用同一真实 Mapper 断言，全部夹具在单连接事务中回滚。 */
public final class WorkflowTriggerReceiptFixture {
  private WorkflowTriggerReceiptFixture() {}

  public static void verify(DataSource dataSource) throws Exception {
    Configuration configuration = new Configuration(
        new Environment("receipt-test", new JdbcTransactionFactory(), dataSource));
    String resource = "mapper/WorkflowTriggerReceiptMapper.xml";
    try (InputStream xml = Resources.getResourceAsStream(resource)) {
      XMLMapperBuilder builder =
          new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments());
      builder.parse();
    }
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    try (SqlSession session = factory.openSession(false)) {
      try {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        String suffix = UUID.randomUUID().toString();
        String tenant = "receipt-" + suffix;
        Long workflowId = insertWorkflow(jdbc, tenant, suffix);
        String requestId = "receipt-request-" + suffix;
        LocalDate bizDate = LocalDate.of(2026, 10, 24);
        String insertRequestSql = """
            insert into batch.trigger_request
              (tenant_id, request_id, workflow_definition_id, trigger_type, biz_date,
               dedup_key, request_status, trace_id)
            values (?, ?, ?, 'MANUAL', ?, ?, 'WAITING', ?)
            """;
        jdbc.update(insertRequestSql, tenant, requestId, workflowId, bizDate, "original-key", "trace-receipt");
        String insertAliasSql = """
            insert into batch.trigger_request_idempotency_alias
              (tenant_id, request_id, idempotency_key) values (?, ?, ?)
            """;
        jdbc.update(insertAliasSql, tenant, requestId, "alias-key");
        WorkflowTriggerReceiptMapper mapper = session.getMapper(WorkflowTriggerReceiptMapper.class);
        WorkflowTriggerStatusResponse direct = mapper.selectByIdentity(tenant, workflowId, "original-key");
        assertThat(direct.requestId()).isEqualTo(requestId);
        assertThat(direct.bizDate()).isEqualTo(bizDate);
        assertThat(direct.requestStatus()).isEqualTo("WAITING");
        WorkflowTriggerStatusResponse alias = mapper.selectByIdentity(tenant, workflowId, "alias-key");
        assertThat(alias).isEqualTo(direct);
        WorkflowTriggerStatusResponse wrongTenant =
            mapper.selectByIdentity("other-tenant", workflowId, "original-key");
        WorkflowTriggerStatusResponse wrongWorkflow =
            mapper.selectByIdentity(tenant, -1L, "original-key");
        WorkflowTriggerStatusResponse wrongAliasWorkflow =
            mapper.selectByIdentity(tenant, -1L, "alias-key");
        WorkflowTriggerStatusResponse missingKey =
            mapper.selectByIdentity(tenant, workflowId, "missing-key");
        assertThat(wrongTenant).isNull();
        assertThat(wrongWorkflow).isNull();
        assertThat(wrongAliasWorkflow).isNull();
        assertThat(missingKey).isNull();
        String disableSql = "update batch.workflow_definition set enabled = false where id = ?";
        jdbc.update(disableSql, workflowId);
        session.clearCache();
        WorkflowTriggerStatusResponse afterDisable =
            mapper.selectByIdentity(tenant, workflowId, "original-key");
        assertThat(afterDisable).isEqualTo(direct);
      } finally {
        // JdbcTemplate 写入不经过 MyBatis dirty 标记，必须显式强制回滚。
        session.rollback(true);
      }
    }
  }

  private static Long insertWorkflow(JdbcTemplate jdbc, String tenant, String suffix) {
    String insertWorkflowSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code)
        values (?, ?, 'Receipt identity fixture', 'DAG', 1, true, 'RECEIPT_CAL') returning id
        """;
    return jdbc.queryForObject(insertWorkflowSql, Long.class, tenant, "RECEIPT_" + suffix);
  }
}
