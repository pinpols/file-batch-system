package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 防回归：实例已派发但 worker 尚未 claim 时，不得被业务硬超时终止。 */
class JobInstanceTimeoutMapperXmlTest {

  private static final String RESOURCE = "mapper/JobInstanceMapper.xml";

  @Test
  void timedOutCandidatesMustRequireAnActivelyRunningTask() throws Exception {
    byte[] xml;
    try (InputStream inputStream = resource()) {
      assertThat(inputStream).as(RESOURCE).isNotNull();
      xml = inputStream.readAllBytes();
    }

    Configuration configuration = new Configuration();
    try (InputStream inputStream = resource()) {
      new XMLMapperBuilder(inputStream, configuration, RESOURCE, configuration.getSqlFragments())
          .parse();
    }

    String select = new String(xml, StandardCharsets.UTF_8)
        .replaceAll("(?s).*?<select id=\"selectTimedOutCandidates\".*?>(.*?)</select>.*", "$1");
    assertThat(select)
        .contains("from batch.job_task jt")
        .contains("jt.job_instance_id = ji.id")
        .contains("jt.task_status = 'RUNNING'");
  }

  private static InputStream resource() {
    return Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE);
  }
}
