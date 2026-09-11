package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AdminTestDataCleanupMapperXmlTest {

  @Test
  void mapperParsesAndRegistersBothCleanupModes() {
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
