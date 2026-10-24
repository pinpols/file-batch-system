package io.github.pinpols.batch.console.domain.ops.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.model.PageRequest;
import java.io.InputStream;
import java.util.Objects;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("资源队列配置导出与交互分页的真实 Mapper SQL 边界")
class ResourceQueueQuerySqlTest {

  @Test
  @DisplayName("配置包导出读取完整租户队列且不附加分页上限")
  void shouldReadAllTenantQueues_whenBuildingConfigurationExport() throws Exception {
    ResourceQueueQuery query = ResourceQueueQuery.allForTenant("tenant-export");
    BoundSql statement = queueStatement(query);
    String sql = statement.getSql().replaceAll("\\s+", " ").trim();

    assertThat(sql).contains("where tenant_id = ?").contains("order by id desc");
    assertThat(sql).doesNotContain("limit", "offset");
    assertThat(statement.getParameterMappings()).extracting(ParameterMapping::getProperty)
        .containsExactly("tenantId");
    assertThat(query.tenantId()).isEqualTo("tenant-export");
  }

  @Test
  @DisplayName("交互列表仍按显式页码与页大小绑定分页")
  void shouldKeepBoundPagination_whenQueryingInteractiveList() throws Exception {
    PageRequest page = new PageRequest(2, 25);
    ResourceQueueQuery query = ResourceQueueQuery.forTenant("tenant-list", page);
    BoundSql statement = queueStatement(query);
    String sql = statement.getSql().replaceAll("\\s+", " ").trim();

    assertThat(sql).contains("where tenant_id = ?").contains("limit ?").contains("offset ?");
    assertThat(statement.getParameterMappings()).extracting(ParameterMapping::getProperty)
        .containsExactly("tenantId", "pageRequest.pageSize", "offset");
    assertThat(statement.getAdditionalParameter("offset")).isEqualTo(25);
    assertThat(query.pageRequest()).isSameAs(page);
  }

  @Test
  @DisplayName("导出仍要求租户身份，分页入口仍要求页条件")
  void shouldRejectIncompleteQueryIdentity_whenRequiredInputIsMissing() {
    assertThatThrownBy(() -> ResourceQueueQuery.allForTenant(null))
        .isInstanceOf(NullPointerException.class).hasMessage("tenantId");
    assertThatThrownBy(() -> ResourceQueueQuery.forTenant("tenant-list", null))
        .isInstanceOf(NullPointerException.class).hasMessage("pageRequest");
  }

  private BoundSql queueStatement(ResourceQueueQuery query) throws Exception {
    Configuration configuration = new Configuration();
    parseMapper(configuration, "mapper/CommonFragments.xml");
    parseMapper(configuration, "mapper/ResourceQueueMapper.xml");
    return configuration.getMappedStatement(
        "io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper.selectByQuery")
        .getBoundSql(query);
  }

  private void parseMapper(Configuration configuration, String resource) throws Exception {
    ClassLoader loader = getClass().getClassLoader();
    try (InputStream stream = Objects.requireNonNull(loader.getResourceAsStream(resource), resource)) {
      XMLMapperBuilder parser = new XMLMapperBuilder(
          stream, configuration, resource, configuration.getSqlFragments());
      parser.parse();
    }
  }
}
