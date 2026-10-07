package io.github.pinpols.batch.console.domain.ops.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("集群诊断终态查询:SQL 中过滤的状态集合须与作业实例生命周期枚举一致, 不得硬编码漂移")
class ConsoleClusterDiagnosticMapperXmlTest {

  @Test
  @DisplayName("终态过滤:解析出的状态码集合与枚举派生的终态集合完全一致")
  void shouldMatchLifecycleEnum_whenParsingTerminalStatuses() {
    String resource = "mapper/ConsoleClusterDiagnosticMapper.xml";
    Configuration configuration = new Configuration();
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

      String sql = configuration
          .getMappedStatement(ConsoleClusterDiagnosticMapper.class.getName()
              + ".countTerminalInstancesWithActiveChildren")
          .getBoundSql(Map.of("tenantId", "tenant-a"))
          .getSql();

      assertThat(terminalStatuses(sql))
          .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.terminalCodes());
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }

  private static Set<String> terminalStatuses(String sql) {
    Matcher matcher =
        Pattern.compile("(?is)ji\\.instance_status\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
    assertThat(matcher.find()).as("terminal instance status filter").isTrue();
    Set<String> statuses = new HashSet<>();
    Matcher codes = Pattern.compile("'([A-Z_]+)'").matcher(matcher.group(1));
    while (codes.find()) {
      statuses.add(codes.group(1));
    }
    assertThat(statuses).isNotEmpty();
    assertThat(matcher.find()).as("single terminal instance status filter").isFalse();
    return statuses;
  }
}
