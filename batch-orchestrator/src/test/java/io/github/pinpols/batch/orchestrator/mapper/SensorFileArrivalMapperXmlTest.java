package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件到达传感器 Mapper 契约:按业务日和完整到达组过滤")
class SensorFileArrivalMapperXmlTest {

  @Test
  @DisplayName("完整组查询同时约束租户、业务日、到达组和触发终态")
  void shouldFilterByBusinessDateAndTriggeredArrivalGroup() {
    Configuration configuration = parseMapper();
    var statement = configuration.getMappedStatement(
        "io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper.selectLatestArrival");
    var boundSql = statement.getBoundSql(Map.of(
        "tenantId",
        "tenant-a",
        "namePattern",
        "settle-%.csv",
        "sourceType",
        "SFTP",
        "since",
        OffsetDateTime.parse("2026-05-07T00:00:00Z"),
        "bizDate",
        LocalDate.parse("2026-05-07"),
        "fileGroupCode",
        "SETTLEMENT",
        "workflowDefinitionId",
        902L,
        "requireCompleteGroup",
        true));

    assertThat(boundSql.getSql())
        .contains("biz_date = ?")
        .contains("metadata_json ->> 'fileGroupCode' = ?")
        .contains("metadata_json ->> 'arrivalState' = 'TRIGGERED'")
        .contains("bundleWorkflowCode")
        .contains("target.id = ?");
  }

  @Test
  @DisplayName("未配置可选身份条件时保持旧式时间窗口匹配")
  void shouldKeepLegacyPatternMatchingWhenNoIdentityScopeProvided() {
    Configuration configuration = parseMapper();
    var statement = configuration.getMappedStatement(
        "io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper.selectLatestArrival");
    var boundSql = statement.getBoundSql(Map.of(
        "tenantId",
        "tenant-a",
        "namePattern",
        "settle-%.csv",
        "since",
        OffsetDateTime.parse("2026-05-07T00:00:00Z"),
        "requireCompleteGroup",
        false));

    assertThat(boundSql.getSql()).contains("file_name LIKE ?");
    assertThat(boundSql.getSql()).doesNotContain("biz_date = ?");
    assertThat(boundSql.getSql()).doesNotContain("metadata_json ->> 'arrivalState'");
    assertThat(boundSql.getSql())
        .contains("metadata_json ->> 'uploadMode' is distinct from 'APP_MANAGED'")
        .contains("jsonb_exists(file.metadata_json, 'arrivalConfirmedAt')");
  }

  @Test
  @DisplayName("未知渠道不能匹配无渠道的历史文件，已知来源仍可同源回退")
  void shouldRestrictLegacyFallbackToKnownSourceType() {
    var statement = parseMapper()
        .getMappedStatement(
            "io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper.selectLatestArrival");
    var unknownSql = statement
        .getBoundSql(Map.of(
            "tenantId",
            "tenant-a",
            "namePattern",
            "settle.csv",
            "channelCode",
            "BANK-SFTP",
            "since",
            OffsetDateTime.parse("2026-05-07T00:00:00Z"),
            "requireCompleteGroup",
            false))
        .getSql();
    assertThat(unknownSql)
        .contains("file.arrival_channel_code = ?")
        .doesNotContain("OR (file.legacy_channel_fallback");

    var knownSql = statement
        .getBoundSql(Map.of(
            "tenantId",
            "tenant-a",
            "namePattern",
            "settle.csv",
            "channelCode",
            "sftp_bank",
            "sourceType",
            "SFTP",
            "since",
            OffsetDateTime.parse("2026-05-07T00:00:00Z"),
            "requireCompleteGroup",
            false))
        .getSql();
    assertThat(knownSql)
        .contains("file.arrival_channel_code = ?")
        .contains("OR (file.legacy_channel_fallback")
        .contains("file.source_type = ?");
  }

  private static Configuration parseMapper() {
    Configuration configuration = new Configuration();
    for (String resource :
        new String[] {"mapper/FileGovernanceMapper.xml", "mapper/SensorFileArrivalMapper.xml"}) {
      try (InputStream inputStream =
          Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
        assertThat(inputStream).as(resource).isNotNull();
        new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
            .parse();
      } catch (Exception exception) {
        throw new AssertionError("failed to parse " + resource, exception);
      }
    }
    return configuration;
  }
}
