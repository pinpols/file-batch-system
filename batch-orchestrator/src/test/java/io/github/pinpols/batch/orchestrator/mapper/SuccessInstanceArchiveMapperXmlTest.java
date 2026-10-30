package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成功实例归档映射器的 XML 配置,验证其可被解析且终态筛选口径正确")
class SuccessInstanceArchiveMapperXmlTest {

  @Test
  @DisplayName("归档候选和引用异常统计的实例终态集合与生命周期枚举一致")
  void shouldMatchTerminalLifecycle_whenSelectingArchiveCandidates() {
    String resource = "mapper/SuccessInstanceArchiveMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    } catch (Exception ex) {
      throw new AssertionError("failed to parse " + resource, ex);
    }
    for (String statement :
        new String[] {"selectInvalidArchiveReferenceSummaries", "selectArchivableInstanceIds"}) {
      String sql = configuration
          .getMappedStatement(SuccessInstanceArchiveMapper.class.getName() + "." + statement)
          .getBoundSql(Map.of("cutoff", Instant.EPOCH, "limit", 10))
          .getSql();
      Matcher clause =
          Pattern.compile("(?is)root\\.instance_status\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
      assertThat(clause.find()).as(statement + " terminal status predicate").isTrue();
      Matcher literals = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
      Set<String> statuses = new HashSet<>();
      while (literals.find()) {
        statuses.add(literals.group(1));
      }
      assertThat(statuses)
          .as(statement + " terminal statuses")
          .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.terminalCodes());
      assertThat(clause.find())
          .as(statement + " duplicate root status predicate")
          .isFalse();
    }
  }

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
