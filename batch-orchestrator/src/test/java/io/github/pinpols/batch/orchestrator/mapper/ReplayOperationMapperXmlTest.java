package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Replay operation MyBatis 映射定义")
class ReplayOperationMapperXmlTest {

  @Test
  @DisplayName("operation、preview token 和日 session 映射可完整加载")
  void shouldLoadAllReplayOperationStatements() throws Exception {
    Configuration configuration = new Configuration();
    parse(configuration, "/mapper/ReplayOperationMapper.xml");
    parse(configuration, "/mapper/ReplayOperationPreviewTokenMapper.xml");
    parse(configuration, "/mapper/BatchDayReplaySessionMapper.xml");

    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.insert")).isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.refreshFromChildSessions"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.selectByTargetRequest"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.attachTargetWorkflowRun"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.updateWorkflowTargetOutcome"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.rejectPendingApproval"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.startApprovedWorkflowNodeReplay"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper.startApprovedWorkflowRunReplay"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper.selectForUpdate"))
        .isTrue();
    assertThat(configuration.hasStatement(
        "io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper.insert")).isTrue();
  }

  private static void parse(Configuration configuration, String resource) throws Exception {
    try (InputStream input = ReplayOperationMapperXmlTest.class.getResourceAsStream(resource)) {
      assertThat(input).as("mapper resource %s exists", resource).isNotNull();
      XMLMapperBuilder parser = new XMLMapperBuilder(
          input, configuration, resource, configuration.getSqlFragments());
      parser.parse();
    }
  }
}
