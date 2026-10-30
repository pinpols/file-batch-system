package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作业定义维护 Mapper SQL")
class JobDefinitionMapperXmlTest {

  @Test
  @DisplayName("更新参数 schema 时显式转换为 jsonb")
  void shouldCastParamSchemaToJsonb_whenUpdatingJobDefinition() {
    Configuration configuration = new Configuration();
    parseMapper(configuration, "mapper/CommonFragments.xml");
    parseMapper(configuration, "mapper/JobDefinitionMapper.xml");

    String sql = configuration
        .getMappedStatement(
            "io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper.updateJobDefinitionMaintenance")
        .getBoundSql(Map.of(
            "defaultParamsSpecified", false,
            "enabled", true))
        .getSql();

    assertThat(sql).contains("param_schema = ?::jsonb");
  }

  private static void parseMapper(Configuration configuration, String resource) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      byte[] mapperBytes = inputStream.readAllBytes();
      new XMLMapperBuilder(
              new ByteArrayInputStream(mapperBytes),
              configuration,
              resource,
              configuration.getSqlFragments())
          .parse();
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
