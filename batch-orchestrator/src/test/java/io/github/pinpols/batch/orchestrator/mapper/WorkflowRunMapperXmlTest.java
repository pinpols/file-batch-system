package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作流运行映射文件:归档候选查询对试运行终态的覆盖,以及映射文件可正常解析")
class WorkflowRunMapperXmlTest {

  @Test
  @DisplayName("归档候选查询包含试运行成功与试运行失败两类终态,且映射文件可被解析加载")
  void shouldDeclareDryRunTerminalStatesInArchiveCandidates_whenMapperXmlLoads() {
    String resource = "mapper/WorkflowRunMapper.xml";
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
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
