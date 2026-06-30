package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class LineageEvidenceMapperXmlTest {

  @Test
  void mapperXmlShouldParse() {
    Configuration configuration = new Configuration();
    for (String resource :
        List.of("mapper/ResultVersionMapper.xml", "mapper/LineageEvidenceMapper.xml")) {
      try (InputStream inputStream =
          Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
        assertThat(inputStream).as(resource).isNotNull();
        new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
            .parse();
      } catch (Exception ex) {
        throw new AssertionError("failed to parse " + resource, ex);
      }
    }
  }
}
