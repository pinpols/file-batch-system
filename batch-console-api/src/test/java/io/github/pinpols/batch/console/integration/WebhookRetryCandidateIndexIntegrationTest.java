package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * IDX-02：Webhook 旧式重投扫描同时领取 PENDING 与 EXHAUSTED，状态列表以绑定参数传入。原 partial 索引只含 EXHAUSTED，
 * PENDING 重投不能走索引；且 pgjdbc 转为服务端预编译后的 generic plan 无法证明绑定的 {@code IN ($1,$2)} 蕴含字面量状态谓词。
 *
 * <p>用 MyBatis 实际生成的 SQL 做 PREPARE，分别强制 generic 与 custom plan 并关闭顺序扫描，验证规划器能够使用重投候选索引。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("Webhook 旧式重投扫描的候选索引:PENDING 与 EXHAUSTED 在 generic/custom 计划下都可走索引")
class WebhookRetryCandidateIndexIntegrationTest extends AbstractIntegrationTest {

  private static final String RETRY_INDEX = "idx_webhook_delivery_log_legacy_retry";

  @Autowired
  private SqlSessionFactory sqlSessionFactory;

  @Autowired
  private DataSource dataSource;

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"force_generic_plan", "force_custom_plan"})
  @DisplayName("重投候选扫描在 generic 与 custom 计划下都使用重投候选索引")
  void shouldUseRetryCandidateIndex_whenPlanningRelayScan(String planCacheMode) throws Exception {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("now", Instant.parse("2026-11-05T00:00:00Z"));
    parameters.put("limit", 50);
    parameters.put("eligibleStatuses", List.of("PENDING", "EXHAUSTED"));

    String plan = explainPrepared("findEligibleRetriesByStatuses", parameters, planCacheMode);

    assertThat(plan).contains(RETRY_INDEX);
  }

  private String explainPrepared(
      String statementId, Map<String, Object> parameters, String planCacheMode) throws Exception {
    MappedStatement statement = sqlSessionFactory
        .getConfiguration()
        .getMappedStatement(ConsoleWebhookDeliveryLogMapper.class.getName() + "." + statementId);
    BoundSql bound = statement.getBoundSql(parameters);
    StringBuilder positional = new StringBuilder();
    int index = 0;
    for (char c : bound.getSql().toCharArray()) {
      positional.append(c == '?' ? "$" + ++index : String.valueOf(c));
    }
    MetaObject values = statement.getConfiguration().newMetaObject(parameters);
    List<String> literals = new ArrayList<>();
    for (ParameterMapping mapping : bound.getParameterMappings()) {
      String property = mapping.getProperty();
      Object value = bound.hasAdditionalParameter(property)
          ? bound.getAdditionalParameter(property)
          : values.getValue(property);
      literals.add(value instanceof Number ? value.toString() : "'" + value + "'");
    }
    StringBuilder plan = new StringBuilder();
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.execute("set local plan_cache_mode = " + planCacheMode);
      sql.execute("set local enable_seqscan = off");
      sql.execute("prepare relay_scan as " + positional);
      try (ResultSet rows =
          sql.executeQuery("explain execute relay_scan(" + String.join(", ", literals) + ")")) {
        while (rows.next()) {
          plan.append(rows.getString(1)).append('\n');
        }
      } finally {
        sql.execute("deallocate relay_scan");
        connection.rollback();
      }
    }
    return plan.toString();
  }
}
