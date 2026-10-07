package io.github.pinpols.batch.console.domain.rbac.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("用户批量操作映射文件: 语句解析与操作人范围查询")
class ConsoleUserBatchOperationMapperXmlTest {

  @Test
  @DisplayName("映射文件可解析且包含按操作人与请求标识查询的语句")
  void shouldParseOperationStatementsWithActorScopedQueries() {
    String resource = "mapper/ConsoleUserBatchOperationMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      String namespace = ConsoleUserBatchOperationMapper.class.getName() + ".";
      assertThat(configuration.hasStatement(namespace + "insertOperation")).isTrue();
      assertThat(configuration.hasStatement(namespace + "selectByOperationId")).isTrue();
      assertThat(configuration.hasStatement(namespace + "selectByRequestId")).isTrue();
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
