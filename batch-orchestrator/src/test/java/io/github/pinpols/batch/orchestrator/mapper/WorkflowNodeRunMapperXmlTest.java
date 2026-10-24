package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workflow 节点运行 Mapper: 跳过原因持久化与 SQL 解析")
class WorkflowNodeRunMapperXmlTest {

  @Test
  @DisplayName("插入和状态更新 SQL 都写入可选的跳过原因")
  void shouldMapSkipReasonFields_whenBuildingNodeRunStatements() throws Exception {
    String resource = "mapper/WorkflowNodeRunMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
    }

    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setSkipReasonCode("UPSTREAM_FAILED");
    String insertSql = configuration
        .getMappedStatement(WorkflowNodeRunMapper.class.getName() + ".insert")
        .getBoundSql(nodeRun)
        .getSql();
    assertThat(insertSql).contains("skip_reason_code", "skip_reason");

    UpdateNodeRunStatusParam update = UpdateNodeRunStatusParam.builder()
        .id(1L)
        .nodeStatus("SKIPPED")
        .skipReasonCode("OPERATOR_SKIPPED")
        .skipReason("approved")
        .build();
    String updateSql = configuration
        .getMappedStatement(WorkflowNodeRunMapper.class.getName() + ".updateStatus")
        .getBoundSql(update)
        .getSql();
    assertThat(updateSql).contains("skip_reason_code = ?", "skip_reason = ?");
  }
}
