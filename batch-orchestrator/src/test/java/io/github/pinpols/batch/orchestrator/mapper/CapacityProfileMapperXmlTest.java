package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
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

@DisplayName("容量档位映射配置:验证映射文件可从类路径加载并解析通过")
class CapacityProfileMapperXmlTest {

  @Test
  @DisplayName("映射文件能从类路径加载且解析不报错")
  void shouldParseMapperXml_whenResourceIsPresent() {
    loadMapper();
  }

  @Test
  @DisplayName("租户与作业容量统计的终态分类与作业实例生命周期一致")
  void shouldMatchJobLifecycle_whenClassifyingCapacityProfiles() {
    Configuration configuration = loadMapper();
    for (String statement : new String[] {"selectTenantProfile", "selectJobProfile"}) {
      String sql = configuration
          .getMappedStatement(CapacityProfileMapper.class.getName() + "." + statement)
          .getBoundSql(Map.of())
          .getSql();
      Matcher clauses =
          Pattern.compile("(?is)instance_status\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
      assertThat(clauses.find()).as(statement + " success statuses").isTrue();
      assertThat(statuses(clauses.group(1)))
          .as(statement + " success statuses")
          .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.successCodes());
      assertThat(clauses.find())
          .as(statement + " unsuccessful terminal statuses")
          .isTrue();
      assertThat(statuses(clauses.group(1)))
          .as(statement + " unsuccessful terminal statuses")
          .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.unsuccessfulTerminalCodes());
      assertThat(clauses.find()).as(statement + " extra status clauses").isFalse();
    }
  }

  private static Set<String> statuses(String clause) {
    Set<String> values = new HashSet<>();
    Matcher matcher = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause);
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }

  private static Configuration loadMapper() {
    Configuration configuration = new Configuration();
    String resource = "mapper/CapacityProfileMapper.xml";

    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      return configuration;
    } catch (Exception ex) {
      throw new AssertionError("failed to parse " + resource, ex);
    }
  }
}
