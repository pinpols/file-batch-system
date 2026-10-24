package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作流运行映射文件:归档候选查询对试运行终态的覆盖,以及映射文件可正常解析")
class WorkflowRunMapperXmlTest {

  @Test
  @DisplayName("归档候选查询包含试运行成功与试运行失败两类终态,且映射文件可被解析加载")
  void shouldDeclareDryRunTerminalStatesInArchiveCandidates_whenMapperXmlLoads() {
    String resource = "mapper/WorkflowRunMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      byte[] mapperBytes = inputStream.readAllBytes();
      String mapperXml = new String(mapperBytes, StandardCharsets.UTF_8);

      assertThat(statusesForClause(
              mapperXml,
              "(?is)<select id=\"selectArchivableIds\".*?run_status\\s+in\\s*\\(([^)]*)\\)"))
          .containsExactlyInAnyOrderElementsOf(WorkflowRunStatus.terminalCodes());
      assertThat(statusesForClause(
              mapperXml,
              "(?is)<update id=\"updateStatus\".*?run_status\\s+not\\s+in\\s*\\(([^)]*)\\)"))
          .containsExactlyInAnyOrderElementsOf(WorkflowRunStatus.terminalCodes());
      assertThat(mapperXml)
          .contains("<result column=\"trigger_request_id\" property=\"triggerRequestId\"/>")
          .contains(
              "<result column=\"workflow_version_snapshot\" property=\"workflowVersionSnapshot\"/>")
          .contains("<result column=\"execution_snapshot\" property=\"executionSnapshot\"/>")
          .contains("<select id=\"selectByTenantAndTriggerRequestId\"")
          .contains("CAST(#{executionSnapshot} AS jsonb)");
      assertArchivedColumnsAreExplicit(mapperXml);
      new XMLMapperBuilder(
              new ByteArrayInputStream(mapperBytes),
              configuration,
              resource,
              configuration.getSqlFragments())
          .parse();
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }

  private static void assertArchivedColumnsAreExplicit(String mapperXml) {
    Matcher archive = Pattern.compile(
            "(?is)<insert id=\"archiveWorkflowRunsByIds\".*?insert into archive\\.workflow_run_archive\\s*\\((.*?)\\)\\s*select\\s*(.*?)\\s*from batch\\.workflow_run")
        .matcher(mapperXml);
    assertThat(archive.find()).isTrue();
    assertThat(normalizeColumns(archive.group(1)))
        .contains("trigger_request_id", "workflow_version_snapshot", "execution_snapshot");
    assertThat(normalizeColumns(archive.group(2)))
        .contains("trigger_request_id", "workflow_version_snapshot", "execution_snapshot");
    Matcher nodeRuns = Pattern.compile(
            "(?is)<insert id=\"archiveWorkflowNodeRunsByWorkflowRunIds\".*?insert into archive\\.workflow_node_run_archive\\s*\\((.*?)\\)\\s*select\\s*(.*?)\\s*from batch\\.workflow_node_run")
        .matcher(mapperXml);
    assertThat(nodeRuns.find()).isTrue();
    assertThat(normalizeColumns(nodeRuns.group(1))).contains("skip_reason_code", "skip_reason");
    assertThat(normalizeColumns(nodeRuns.group(2))).contains("skip_reason_code", "skip_reason");
  }

  private static Set<String> normalizeColumns(String columns) {
    Set<String> result = new HashSet<>();
    for (String column : columns.split(",")) {
      result.add(column.trim());
    }
    return result;
  }

  private static Set<String> statusesForClause(String mapperXml, String clausePattern) {
    Matcher clause = Pattern.compile(clausePattern).matcher(mapperXml);
    assertThat(clause.find()).as(clausePattern).isTrue();
    Set<String> statuses = new HashSet<>();
    Matcher status = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
    while (status.find()) {
      statuses.add(status.group(1));
    }
    return statuses;
  }
}
