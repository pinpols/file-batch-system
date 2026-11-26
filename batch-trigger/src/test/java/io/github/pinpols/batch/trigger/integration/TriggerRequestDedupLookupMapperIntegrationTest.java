package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等查找在结算栅栏内执行。直接键和别名必须各自走索引，不能因为 OR 退回 trigger_request 顺序扫描。
 * 计划取自 Mapper 实际 BoundSql，避免测试 SQL 与生产语句漂移。
 */
@SpringBootTest(classes = BatchTriggerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DisplayName("按租户和幂等键查找触发请求：直接键优先于别名，并走索引")
class TriggerRequestDedupLookupMapperIntegrationTest extends AbstractIntegrationTest {

    /** 超过单页后，等值查找在默认代价下应选择索引而不是顺序扫描。 */
    private static final int REPRESENTATIVE_REQUEST_ROWS = 8000;

    private static final int REPRESENTATIVE_ALIAS_ROWS = 2000;

    private static final String STATEMENT_ID = TriggerRequestMapper.class.getName() + ".selectByTenantAndDedupKey";

    private final TriggerRequestMapper mapper;

    private final JdbcTemplate jdbcTemplate;

    private final SqlSessionFactory sqlSessionFactory;

    @Autowired
    TriggerRequestDedupLookupMapperIntegrationTest(
            TriggerRequestMapper mapper, JdbcTemplate jdbcTemplate, SqlSessionFactory sqlSessionFactory) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Test
    @DisplayName("直接幂等键命中顶层请求")
    void shouldReturnRoot_whenDedupKeyMatches() {
        String tenantId = newTenant();
        long workflowId = newWorkflow(tenantId);
        insertRoot(tenantId, workflowId, "root-direct", "dedup-direct");

        TriggerRequestEntity found = mapper.selectByTenantAndDedupKey(tenantId, "dedup-direct");

        assertThat(found).isNotNull();
        assertThat(found.getTenantId()).isEqualTo(tenantId);
        assertThat(found.getRequestId()).isEqualTo("root-direct");
        assertThat(found.getDedupKey()).isEqualTo("dedup-direct");
    }

    @Test
    @DisplayName("只有幂等别名命中时返回别名指向的顶层请求")
    void shouldReturnAliasTarget_whenKeyOnlyMatchesAlias() {
        String tenantId = newTenant();
        long workflowId = newWorkflow(tenantId);
        insertRoot(tenantId, workflowId, "root-aliased", "dedup-stored");
        insertAlias(tenantId, "retry-key", "root-aliased");

        TriggerRequestEntity found = mapper.selectByTenantAndDedupKey(tenantId, "retry-key");

        assertThat(found).isNotNull();
        assertThat(found.getRequestId()).isEqualTo("root-aliased");
        assertThat(found.getDedupKey()).isEqualTo("dedup-stored");
    }

    @Test
    @DisplayName("直接键与别名指向不同请求时优先返回直接键")
    void shouldPreferDirectRoot_whenAliasPointsAtAnotherRequest() {
        String tenantId = newTenant();
        long workflowId = newWorkflow(tenantId);
        insertRoot(tenantId, workflowId, "root-direct", "shared-key");
        insertRoot(tenantId, workflowId, "root-other", "other-key");
        insertAlias(tenantId, "shared-key", "root-other");

        TriggerRequestEntity found = mapper.selectByTenantAndDedupKey(tenantId, "shared-key");

        assertThat(found).isNotNull();
        assertThat(found.getRequestId()).isEqualTo("root-direct");
    }

    @Test
    @DisplayName("相同幂等键的子工作流请求不参与查找")
    void shouldIgnoreChildRequest_whenDedupKeyMatchesWorkflowNode() {
        String tenantId = newTenant();
        long workflowId = newWorkflow(tenantId);
        insertRoot(tenantId, workflowId, "root-visible", "shared-key");
        long parentRunId = insertRunningRun(tenantId, workflowId);
        insertChild(tenantId, parentRunId, "child-hidden", "shared-key");
        insertChild(tenantId, parentRunId, "child-only", "child-key");
        insertAlias(tenantId, "alias-to-child", "child-only");

        TriggerRequestEntity shared = mapper.selectByTenantAndDedupKey(tenantId, "shared-key");
        TriggerRequestEntity childKey = mapper.selectByTenantAndDedupKey(tenantId, "child-key");
        TriggerRequestEntity childAlias = mapper.selectByTenantAndDedupKey(tenantId, "alias-to-child");

        assertThat(shared).isNotNull();
        assertThat(shared.getRequestId()).isEqualTo("root-visible");
        assertThat(childKey).isNull();
        assertThat(childAlias).isNull();
    }

    @Test
    @DisplayName("其他租户的直接键和别名不可见")
    void shouldReturnNull_whenSameKeyBelongsToAnotherTenant() {
        String owner = newTenant();
        String reader = newTenant();
        long workflowId = newWorkflow(owner);
        insertRoot(owner, workflowId, "owner-root", "shared-key");
        insertAlias(owner, "owner-alias", "owner-root");

        assertThat(mapper.selectByTenantAndDedupKey(reader, "shared-key")).isNull();
        assertThat(mapper.selectByTenantAndDedupKey(reader, "owner-alias")).isNull();
        TriggerRequestEntity ownerHit = mapper.selectByTenantAndDedupKey(owner, "shared-key");
        assertThat(ownerHit).isNotNull();
        assertThat(ownerHit.getRequestId()).isEqualTo("owner-root");
    }

    @Test
    @DisplayName("未知键和空键都不命中")
    void shouldReturnNull_whenKeyIsUnknownOrNull() {
        String tenantId = newTenant();
        long workflowId = newWorkflow(tenantId);
        insertRoot(tenantId, workflowId, "root-direct", "dedup-direct");

        assertThat(mapper.selectByTenantAndDedupKey(tenantId, "missing-key")).isNull();
        assertThat(mapper.selectByTenantAndDedupKey(tenantId, null)).isNull();
    }

    @Test
    @DisplayName("代表性行数下，未命中和别名命中在 generic 与 custom 计划中都走索引")
    void shouldUseDedupAndAliasIndexes_whenPlanningLookupOnPopulatedTable() {
        String tenantId = newTenant();
        long workflowId = newWorkflow(tenantId);
        insertRepresentativeRows(tenantId, workflowId);
        analyzeLookupTables();

        TriggerRequestEntity direct = mapper.selectByTenantAndDedupKey(tenantId, "filler-dedup-1");
        TriggerRequestEntity alias = mapper.selectByTenantAndDedupKey(tenantId, "filler-alias-1");
        assertThat(direct).isNotNull();
        assertThat(direct.getRequestId()).isEqualTo("filler-1");
        assertThat(alias).isNotNull();
        assertThat(alias.getRequestId()).isEqualTo("filler-1");
        assertThat(mapper.selectByTenantAndDedupKey(tenantId, "lookup-miss")).isNull();

        for (String planCacheMode : List.of("force_custom_plan", "force_generic_plan")) {
            assertIndexLookup(explain(tenantId, "lookup-miss", planCacheMode), planCacheMode);
            assertIndexLookup(explain(tenantId, "filler-alias-1", planCacheMode), planCacheMode);
        }
    }

    private void assertIndexLookup(String plan, String planCacheMode) {
        assertThat(plan)
                .as(planCacheMode)
                .contains("idx_trigger_request_tenant_dedup")
                .contains("pk_trigger_request_idempotency_alias")
                .doesNotContainPattern("Seq Scan on trigger_request\\b");
    }

    private String explain(String tenantId, String dedupKey, String planCacheMode) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("dedupKey", dedupKey);
        MappedStatement statement = sqlSessionFactory.getConfiguration().getMappedStatement(STATEMENT_ID);
        BoundSql bound = statement.getBoundSql(parameters);
        String preparedSql = toPositional(bound.getSql());
        String literals = String.join(", ", literals(statement, bound, parameters));
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource());
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement sql = connection.createStatement()) {
            sql.execute(planCacheSetting(planCacheMode));
            sql.execute("prepare dedup_lookup as " + preparedSql);
            try (ResultSet rows = sql.executeQuery("explain (format text) execute dedup_lookup(" + literals + ")")) {
                StringBuilder plan = new StringBuilder();
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
                if (plan.isEmpty()) {
                    throw new IllegalStateException("EXPLAIN returned no rows");
                }
                return plan.toString();
            } finally {
                sql.execute("deallocate dedup_lookup");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("dedup lookup plan failed", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static String planCacheSetting(String planCacheMode) {
        if ("force_custom_plan".equals(planCacheMode) || "force_generic_plan".equals(planCacheMode)) {
            return "set local plan_cache_mode = " + planCacheMode;
        }
        throw new IllegalArgumentException(planCacheMode);
    }

    private static String toPositional(String sql) {
        StringBuilder positional = new StringBuilder();
        int index = 0;
        for (char current : sql.toCharArray()) {
            if (current == '?') {
                index++;
                positional.append('$').append(index);
            } else {
                positional.append(current);
            }
        }
        return positional.toString();
    }

    private List<String> literals(MappedStatement statement, BoundSql bound, Map<String, Object> parameters) {
        MetaObject values = statement.getConfiguration().newMetaObject(parameters);
        List<String> literals = new ArrayList<>();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            String property = mapping.getProperty();
            Object value = bound.hasAdditionalParameter(property)
                    ? bound.getAdditionalParameter(property)
                    : values.getValue(property);
            literals.add(sqlLiteral(value));
        }
        return literals;
    }

    private static String sqlLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private void insertRepresentativeRows(String tenantId, long workflowId) {
        String insertRequestsSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status)
        select ?, 'filler-' || gs, 'MANUAL', ?, DATE '2026-01-15',
               'filler-dedup-' || gs, 'ACCEPTED'
        from generate_series(1, ?) gs
        """;
        jdbcTemplate.update(insertRequestsSql, tenantId, workflowId, REPRESENTATIVE_REQUEST_ROWS);
        String insertAliasesSql = """
        insert into batch.trigger_request_idempotency_alias
            (tenant_id, idempotency_key, request_id)
        select ?, 'filler-alias-' || gs, 'filler-' || gs
        from generate_series(1, ?) gs
        """;
        jdbcTemplate.update(insertAliasesSql, tenantId, REPRESENTATIVE_ALIAS_ROWS);
    }

    private void analyzeLookupTables() {
        String analyzeRequestsSql = "analyze batch.trigger_request";
        String analyzeAliasesSql = "analyze batch.trigger_request_idempotency_alias";
        jdbcTemplate.execute(analyzeRequestsSql);
        jdbcTemplate.execute(analyzeAliasesSql);
    }

    private String newTenant() {
        String tenantId = "dedup-it-" + System.nanoTime();
        String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status)
        values (?, ?, 'ACTIVE')
        """;
        jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
        return tenantId;
    }

    private long newWorkflow(String tenantId) {
        String insertWorkflowSql = """
        insert into batch.workflow_definition
            (tenant_id, workflow_code, workflow_name, calendar_code)
        values (?, ?, ?, 'TEST_CAL') returning id
        """;
        Long workflowId = jdbcTemplate.queryForObject(
                insertWorkflowSql, Long.class, tenantId, "wf-" + tenantId, "Workflow " + tenantId);
        return Objects.requireNonNull(workflowId);
    }

    private void insertRoot(String tenantId, long workflowId, String requestId, String dedupKey) {
        String insertRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status)
        values (?, ?, 'MANUAL', ?, DATE '2026-01-15', ?, 'ACCEPTED')
        """;
        jdbcTemplate.update(insertRequestSql, tenantId, requestId, workflowId, dedupKey);
    }

    private void insertAlias(String tenantId, String idempotencyKey, String requestId) {
        String insertAliasSql = """
        insert into batch.trigger_request_idempotency_alias
            (tenant_id, idempotency_key, request_id)
        values (?, ?, ?)
        """;
        jdbcTemplate.update(insertAliasSql, tenantId, idempotencyKey, requestId);
    }

    private long insertRunningRun(String tenantId, long workflowId) {
        String insertRunSql = """
        insert into batch.workflow_run
            (tenant_id, workflow_definition_id, biz_date, run_status)
        values (?, ?, DATE '2026-01-15', 'RUNNING') returning id
        """;
        Long runId = jdbcTemplate.queryForObject(insertRunSql, Long.class, tenantId, workflowId);
        return Objects.requireNonNull(runId);
    }

    private void insertChild(String tenantId, long parentRunId, String requestId, String dedupKey) {
        String insertChildSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, parent_workflow_run_id, parent_node_code)
        values (?, ?, 'EVENT', ?, DATE '2026-01-15', ?, 'ACCEPTED', ?, 'NODE_A')
        """;
        jdbcTemplate.update(insertChildSql, tenantId, requestId, "job-" + requestId, dedupKey, parentRunId);
    }
}
