package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成功实例归档映射器的 XML 配置,验证其可被解析且终态筛选口径正确")
class SuccessInstanceArchiveMapperXmlTest {

  @Test
  @DisplayName("从类路径加载映射器配置后应解析成功,并保留成功与失败干跑两种终态筛选条件")
  void shouldParseMapperXml_whenLoadedFromClasspath() {
    String resource = "mapper/SuccessInstanceArchiveMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      byte[] mapperBytes = inputStream.readAllBytes();
      String mapperXml = new String(mapperBytes, StandardCharsets.UTF_8);
      assertThat(mapperXml).contains("'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'");
      new XMLMapperBuilder(
              new ByteArrayInputStream(mapperBytes),
              configuration,
              resource,
              configuration.getSqlFragments())
          .parse();
    } catch (Exception ex) {
      throw new AssertionError("failed to parse " + resource, ex);
    }
  }
}
