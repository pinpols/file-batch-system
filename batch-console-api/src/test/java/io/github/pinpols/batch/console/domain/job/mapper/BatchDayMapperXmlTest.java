package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.model.PageRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class BatchDayMapperXmlTest {

  @Test
  void formalStatisticsStayDryRunIsolatedAndPausedAware() {
    List<String> statements = List.of("selectByQuery", "selectWindow", "selectJobSummaries");
    Configuration configuration = loadMapper("mapper/BatchDayMapper.xml");

    for (String statement : statements) {
      String sql = batchDaySql(configuration, statement);
      String successAlias = countAlias(statement, "success");
      String failedAlias = countAlias(statement, "failed");
      String inFlightAlias = countAlias(statement, "in_flight");
      assertThat(sql).contains("ji.dry_run = false");
      assertThat(statusesForCountAlias(sql, inFlightAlias))
          .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.activeCodes());
      assertThat(statusesForCountAlias(sql, failedAlias))
          .containsExactlyInAnyOrderElementsOf(
              formalCodes(JobInstanceStatus.unsuccessfulTerminalCodes()));
      assertThat(statusForCountAlias(sql, successAlias))
          .isEqualTo(formalCodes(JobInstanceStatus.successCodes()));
    }
  }

  private static String countAlias(String statement, String category) {
    if (statement.equals("selectJobSummaries")) {
      return switch (category) {
        case "success" -> "successJobCount";
        case "failed" -> "failedJobCount";
        case "in_flight" -> "inFlightJobCount";
        default -> throw new IllegalArgumentException("unsupported category: " + category);
      };
    }
    return category + "_job_count";
  }

  private static Configuration loadMapper(String resource) {
    Configuration configuration = new Configuration();
    try {
      parseMapper(configuration, "mapper/CommonFragments.xml");
      parseMapper(configuration, resource);
      return configuration;
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

  private static String batchDaySql(Configuration configuration, String statement) {
    Map<String, Object> parameters = Map.of(
        "tenantId",
        "tenant-a",
        "calendarCode",
        "CALENDAR",
        "bizDate",
        LocalDate.of(2026, 9, 30),
        "pageRequest",
        new PageRequest(1, 20));
    return configuration
        .getMappedStatement(BatchDayMapper.class.getName() + "." + statement)
        .getBoundSql(parameters)
        .getSql();
  }

  private static Set<String> formalCodes(Set<String> codes) {
    return codes.stream()
        .filter(code -> !JobInstanceStatus.fromCodeOrNull(code).isDryRunTerminal())
        .collect(Collectors.toSet());
  }

  private static Set<String> statusesForCountAlias(String sql, String alias) {
    String expression = "(?is)case\\s+when\\s+ji\\.instance_status\\s+in\\s*\\(([^)]*)\\)"
        + "\\s+then\\s+1\\s+else\\s+0\\s+end\\s*\\)\\s*,\\s*0\\)"
        + "\\s+as\\s+\\\"?"
        + Pattern.quote(alias)
        + "\\\"?";
    Matcher matcher = Pattern.compile(expression).matcher(sql);
    assertThat(matcher.find()).as("status set for %s", alias).isTrue();
    Set<String> statuses = parseStatusCodes(matcher.group(1));
    assertThat(matcher.find()).as("single status set for %s", alias).isFalse();
    return statuses;
  }

  private static Set<String> statusForCountAlias(String sql, String alias) {
    String expression = "(?is)case\\s+when\\s+ji\\.instance_status\\s*=\\s*'([^']+)'"
        + "\\s+then\\s+1\\s+else\\s+0\\s+end\\s*\\)\\s*,\\s*0\\)"
        + "\\s+as\\s+\\\"?"
        + Pattern.quote(alias)
        + "\\\"?";
    Matcher matcher = Pattern.compile(expression).matcher(sql);
    assertThat(matcher.find()).as("status for %s", alias).isTrue();
    Set<String> statuses = Set.of(matcher.group(1));
    assertThat(matcher.find()).as("single status for %s", alias).isFalse();
    return statuses;
  }

  private static Set<String> parseStatusCodes(String expression) {
    Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(expression);
    Set<String> statuses = new HashSet<>();
    while (matcher.find()) {
      statuses.add(matcher.group(1));
    }
    assertThat(statuses).isNotEmpty();
    return statuses;
  }
}
