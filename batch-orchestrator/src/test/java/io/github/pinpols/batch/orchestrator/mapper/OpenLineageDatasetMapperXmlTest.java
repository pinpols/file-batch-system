package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("血缘数据集映射配置文件的加载与解析正确性")
class OpenLineageDatasetMapperXmlTest {

  @Test
  @DisplayName("血缘数据集映射配置文件能被加载并完成解析,不出现语法或结构错误")
  void shouldParseMapperXml_whenResourceExists() {
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
