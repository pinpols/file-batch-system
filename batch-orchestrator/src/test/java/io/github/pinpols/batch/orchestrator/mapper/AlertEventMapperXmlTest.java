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

@DisplayName("告警恢复映射中的作业生命周期分类")
class AlertEventMapperXmlTest {

  @Test
  @DisplayName("直接作业与工作流子作业的三处终态条件均与枚举一致")
  void shouldMatchEveryJobTerminalStatusInRecoveryQueries() {
    String resource = "mapper/AlertEventMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    } catch (Exception exception) {
      throw new AssertionError("failed to parse " + resource, exception);
    }
    String sql = configuration
        .getMappedStatement(AlertEventMapper.class.getName() + ".closeEligibleMonitoringAlert")
        .getBoundSql(Map.of())
        .getSql();
    Matcher clauses = Pattern.compile(
            "(?is)(source|child_job)\\.instance_status\\s+in\\s*\\(([^)]*)\\)")
        .matcher(sql);
    int sourceCount = 0;
    int childCount = 0;
    while (clauses.find()) {
      String alias = clauses.group(1);
      if ("source".equals(alias)) {
        sourceCount++;
      } else {
        childCount++;
      }
      assertThat(statuses(clauses.group(2)))
          .as(alias + " terminal statuses")
          .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.terminalCodes());
    }
    assertThat(sourceCount).isEqualTo(1);
    assertThat(childCount).isEqualTo(2);
  }

  private static Set<String> statuses(String clause) {
    Set<String> result = new HashSet<>();
    Matcher matcher = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause);
    while (matcher.find()) {
      result.add(matcher.group(1));
    }
    return result;
  }
}
