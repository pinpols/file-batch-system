package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件到达扫描 SQL 契约")
class FileGovernanceMapperXmlTest {

  private static final String NAMESPACE = FileGovernanceMapper.class.getName();
  private static final String CONFIRMED_APP_UPLOAD_GUARD =
      "metadata_json ->> 'uploadMode' is distinct from 'APP_MANAGED'\n"
          + "               or jsonb_exists(metadata_json, 'arrivalConfirmedAt')";

  @Test
  @DisplayName("扫描候选只纳入已确认内容的托管上传")
  void shouldRequireArrivalConfirmationForApplicationManagedCandidates() {
    Configuration configuration = parseMapper();
    String sql = configuration
        .getMappedStatement(NAMESPACE + ".selectArrivalGovernanceCandidates")
        .getBoundSql(Map.of("limit", 25))
        .getSql();

    assertThat(sql).contains(CONFIRMED_APP_UPLOAD_GUARD);
  }

  @Test
  @DisplayName("完整性读取只纳入已确认内容的托管文件并约束所选业务日")
  void shouldExcludeUnconfirmedApplicationManagedFileWhenReadingGroupMembers() {
    Configuration configuration = parseMapper();
    String sql = configuration
        .getMappedStatement(NAMESPACE + ".selectArrivalGroupFiles")
        .getBoundSql(Map.of(
            "tenantId", "tenant-a",
            "fileGroupCode", "group-a",
            "bizDate", "2026-10-26"))
        .getSql();

    assertThat(sql).contains(CONFIRMED_APP_UPLOAD_GUARD).contains("biz_date = ?::date");
  }

  private static Configuration parseMapper() {
    Configuration configuration = new Configuration();
    String resource = "mapper/FileGovernanceMapper.xml";
    try (InputStream input =
        FileGovernanceMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
      return configuration;
    } catch (Exception failure) {
      throw new AssertionError("Failed to parse " + resource, failure);
    }
  }
}
