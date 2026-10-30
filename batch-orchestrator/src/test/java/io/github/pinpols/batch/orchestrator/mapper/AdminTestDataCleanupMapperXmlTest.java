package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.CleanupTarget;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.OutboxEventIdentity;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.PrefixCleanupScope;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("测试数据清理映射文件解析与冻结范围绑定,覆盖全部前缀删除目标和空集合安全")
class AdminTestDataCleanupMapperXmlTest {

  @Test
  @DisplayName("映射文件可解析,两种删除模式及运行请求Outbox身份查询均成功注册")
  void shouldRegisterBothCleanupStatements_whenMapperXmlParsed() {
    Configuration configuration = parseConfiguration();
    String namespace = AdminTestDataCleanupMapper.class.getName();
    assertThat(configuration.hasStatement(namespace + ".cleanupByPrefixTarget")).isTrue();
    assertThat(configuration.hasStatement(namespace + ".cleanupByTenantIdsTarget"))
        .isTrue();
    assertThat(configuration.hasStatement(namespace + ".findWorkflowRunIdsByPrefix"))
        .isTrue();
    assertThat(configuration.hasStatement(namespace + ".findTriggerRequestIdsByPrefix"))
        .isTrue();
    assertThat(configuration.hasStatement(namespace + ".findOutboxEventsByPrefix"))
        .isTrue();
    assertThat(configuration.hasStatement(namespace + ".hasAmbiguousJobInstanceIds"))
        .isTrue();
  }

  @Test
  @DisplayName("全部前缀目标均渲染真实SQL,空身份集合使用NULL而不是空IN列表或默认空操作")
  void shouldRenderEveryPrefixTarget_whenOwnedIdentityListsEmpty() {
    Configuration configuration = parseConfiguration();
    String statementId = AdminTestDataCleanupMapper.class.getName() + ".cleanupByPrefixTarget";
    MappedStatement statement = configuration.getMappedStatement(statementId);
    PrefixCleanupScope scope =
        new PrefixCleanupScope("owned-%", "op-owned-%", List.of(), List.of(), List.of());
    EnumSet<CleanupTarget> targets = EnumSet.allOf(CleanupTarget.class);
    targets.removeAll(EnumSet.of(
        CleanupTarget.FILE_ERROR_RECORD,
        CleanupTarget.FILE_DISPATCH_RECORD,
        CleanupTarget.FILE_RECORD));

    for (CleanupTarget target : targets) {
      Map<String, Object> parameters = Map.of("target", target.name(), "scope", scope);
      BoundSql boundSql = statement.getBoundSql(parameters);
      String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
      assertThat(sql)
          .as(target.name())
          .contains("batch.")
          .doesNotContain("select 1 where false", "in ()");
    }
  }

  @Test
  @DisplayName("Outbox子表使用租户主键及分区时间三个绑定字段,业务值不拼接进SQL")
  void shouldBindFullOutboxPartitionIdentity_whenDeletingDeliveryLog() {
    Configuration configuration = parseConfiguration();
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-24T00:00:00Z");
    OutboxEventIdentity event =
        new OutboxEventIdentity("owned-tenant", 301L, createdAt, "owned-event-key");
    List<OutboxEventIdentity> events = List.of(event);
    PrefixCleanupScope scope =
        new PrefixCleanupScope("owned-%", "op-owned-%", List.of(), List.of(), events);
    Map<String, Object> parameters = Map.of("target", "EVENT_DELIVERY_LOG", "scope", scope);
    String statementId = AdminTestDataCleanupMapper.class.getName() + ".cleanupByPrefixTarget";
    MappedStatement statement = configuration.getMappedStatement(statementId);

    BoundSql boundSql = statement.getBoundSql(parameters);
    String sql = boundSql.getSql();

    assertThat(sql)
        .contains("tenant_id, outbox_event_id, outbox_event_created_at")
        .doesNotContain("owned-tenant", "owned-event-key");
    assertThat(boundSql.getParameterMappings()).hasSize(3);
  }

  @Test
  @DisplayName("歧义守护保留NULL三值逻辑,父表删除直接约束本行而不是投回数字ID集合")
  void shouldGuardNullOwnershipAndDeleteOnlyOwnedRows() {
    Configuration configuration = parseConfiguration();
    PrefixCleanupScope scope =
        new PrefixCleanupScope("owned-%", "op-owned-%", List.of(), List.of(), List.of());
    String namespace = AdminTestDataCleanupMapper.class.getName();
    MappedStatement guard =
        configuration.getMappedStatement(namespace + ".hasAmbiguousJobInstanceIds");
    Map<String, Object> guardParameters = Map.of("scope", scope);
    BoundSql guardSql = guard.getBoundSql(guardParameters);
    assertThat(guardSql.getSql())
        .contains(
            "is not true",
            "archive.job_instance_archive cold",
            "instance.tenant_id = cold.tenant_id",
            "instance.id = cold.id",
            "instance.biz_date = cold.biz_date");
    MappedStatement deletion =
        configuration.getMappedStatement(namespace + ".cleanupByPrefixTarget");
    Map<String, Object> deleteParameters = Map.of("target", "JOB_INSTANCE", "scope", scope);
    BoundSql deleteSql = deletion.getBoundSql(deleteParameters);
    String sql = deleteSql.getSql();
    assertThat(sql)
        .contains("delete from batch.job_instance instance", "instance.job_code like")
        .doesNotContain("where id in");
  }

  private Configuration parseConfiguration() {
    String resource = "mapper/AdminTestDataCleanupMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      return configuration;
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
