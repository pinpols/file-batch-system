package io.github.pinpols.batch.console.domain.file.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class ConsolePipelineProgressDirtyMapperXmlTest {

  @Test
  void mapperParsesAndKeepsTenantBoundJoin() {
    String resource = "mapper/ConsolePipelineProgressDirtyMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      assertThat(configuration.hasStatement(
              ConsolePipelineProgressDirtyMapper.class.getName() + ".selectUpdatedSince"))
          .isTrue();
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
