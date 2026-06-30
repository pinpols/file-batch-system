package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class OpenLineageDatasetMapperXmlTest {

  @Test
  void mapperXmlShouldParse() {
    Configuration configuration = new Configuration();
    String resource = "mapper/OpenLineageDatasetMapper.xml";

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
