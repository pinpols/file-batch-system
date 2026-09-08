package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class JobInstanceStatusSqlContractTest {

  @Test
  void batchDayGateQueriesRecognizeEveryTerminalStatus() {
    Configuration configuration = loadJobInstanceMapper();

    assertContainsEveryTerminalStatus(configuration, "countNonTerminalByJobCodeAndBizDate");
    assertContainsEveryTerminalStatus(configuration, "countNonTerminalByJobGroupAndBizDate");
  }

  private static void assertContainsEveryTerminalStatus(
      Configuration configuration, String statementName) {
    String id = JobInstanceMapper.class.getName() + "." + statementName;
    MappedStatement statement = configuration.getMappedStatement(id);
    String sql = statement.getBoundSql(Map.of()).getSql();
    assertThat(sql).as(id).contains(JobInstanceStatus.terminalCodes().toArray(String[]::new));
  }

  private static Configuration loadJobInstanceMapper() {
    String resource = "mapper/JobInstanceMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      return configuration;
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}
