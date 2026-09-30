package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.job.query.JobInstanceQuery;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class JobInstanceMapperXmlTest {

  @Test
  void slaFilterKeepsItsBusinessStatusSetIncludingPartialFailure() {
    String resource = "mapper/JobInstanceMapper.xml";
    Configuration configuration = new Configuration();
    try {
      parseMapper(configuration, "mapper/CommonFragments.xml");
      parseMapper(configuration, resource);

      JobInstanceQuery query = JobInstanceQuery.builder()
          .tenantId("tenant-a")
          .slaBreached(true)
          .pageRequest(new PageRequest(1, 20))
          .build();
      String sql = configuration
          .getMappedStatement(JobInstanceMapper.class.getName() + ".selectByQuery")
          .getBoundSql(query)
          .getSql();

      assertThat(slaStatuses(sql))
          .containsExactlyInAnyOrder(
              JobInstanceStatus.CREATED.code(),
              JobInstanceStatus.WAITING.code(),
              JobInstanceStatus.READY.code(),
              JobInstanceStatus.RUNNING.code(),
              JobInstanceStatus.PARTIAL_FAILED.code());
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }

  private static void parseMapper(Configuration configuration, String resource) throws Exception {
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
    }
  }

  private static Set<String> slaStatuses(String sql) {
    Matcher matcher =
        Pattern.compile("(?is)instance_status\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
    assertThat(matcher.find()).as("SLA instance status filter").isTrue();
    Matcher codes = Pattern.compile("'([A-Z_]+)'").matcher(matcher.group(1));
    Set<String> statuses = new HashSet<>();
    while (codes.find()) {
      statuses.add(codes.group(1));
    }
    assertThat(statuses).isNotEmpty();
    assertThat(matcher.find()).as("single SLA instance status filter").isFalse();
    return statuses;
  }
}
