package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("测试数据清理映射文件解析:按前缀与按租户标识两种清理模式的语句注册")
class AdminTestDataCleanupMapperXmlTest {

  @Test
  @DisplayName("映射文件可解析,按前缀与按租户标识两种清理语句均成功注册")
  void shouldRegisterBothCleanupStatements_whenMapperXmlParsed() {
    String resource = "mapper/AdminTestDataCleanupMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      String namespace = AdminTestDataCleanupMapper.class.getName();
      assertThat(configuration.hasStatement(namespace + ".cleanupByPrefixTarget"))
          .isTrue();
      assertThat(configuration.hasStatement(namespace + ".cleanupByTenantIdsTarget"))
          .isTrue();
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
