package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("血缘证据及关联映射文件的解析,验证类路径下的映射资源均存在且可被解析器成功加载")
class LineageEvidenceMapperXmlTest {

  @Test
  @DisplayName("类路径下的映射资源均存在且可成功解析,任一资源缺失或语法错误都会导致用例失败")
  void shouldParseMapperXmlResources_whenLoadedFromClasspath() {
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
