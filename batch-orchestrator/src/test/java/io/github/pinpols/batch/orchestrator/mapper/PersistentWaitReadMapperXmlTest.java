package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("持久等待查询 MyBatis 映射")
class PersistentWaitReadMapperXmlTest {

  @Test
  @DisplayName("加载显式投影映射及分页和计数语句")
  void shouldLoadSafeProjectionStatements() throws Exception {
    Configuration configuration = new Configuration();
    try (InputStream input = PersistentWaitReadMapperXmlTest.class.getResourceAsStream(
        "/mapper/PersistentWaitReadMapper.xml")) {
      assertThat(input).isNotNull();
      XMLMapperBuilder parser = new XMLMapperBuilder(
          input,
          configuration,
          "mapper/PersistentWaitReadMapper.xml",
          configuration.getSqlFragments());
      parser.parse();
    }

    String namespace = "io.github.pinpols.batch.orchestrator.mapper.PersistentWaitReadMapper";
    assertThat(configuration.hasStatement(namespace + ".setTenantContext")).isTrue();
    assertThat(configuration.hasStatement(namespace + ".selectPage")).isTrue();
    assertThat(configuration.hasStatement(namespace + ".count")).isTrue();
    assertThat(configuration.hasResultMap(namespace + ".persistentWaitReadRow")).isTrue();
  }
}
