package io.github.pinpols.batch.console.domain.workflow.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 插入 SQL 的调度开关列与参数绑定")
class WorkflowDefinitionMapperXmlTest {

  @Mock
  private PreparedStatement statement;

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {true, false})
  @DisplayName("显式开关绑定到调度列,缺省值保留普通创建的启用语义")
  void shouldBindScheduleSwitch_whenInsertingWorkflow(Boolean scheduleEnabled) throws Exception {
    Configuration configuration = new Configuration();
    String resource = "mapper/WorkflowDefinitionMapper.xml";
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertThat(input).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
    entity.setTenantId("tenant-a");
    entity.setWorkflowCode("WF_INSERT");
    entity.setScheduleEnabled(scheduleEnabled);
    MappedStatement mapped =
        configuration.getMappedStatement(WorkflowDefinitionMapper.class.getName() + ".insert");
    BoundSql bound = mapped.getBoundSql(entity);
    Matcher insert = Pattern.compile(
            "insert into batch\\.workflow_definition\\s*\\((.*?)\\)\\s*values\\s*\\((.*?)\\)",
            Pattern.DOTALL)
        .matcher(bound.getSql());
    assertThat(insert.find()).isTrue();
    List<String> columns =
        Arrays.stream(insert.group(1).split(",")).map(String::trim).toList();
    List<String> values =
        Arrays.stream(insert.group(2).split(",")).map(String::trim).toList();
    assertThat(values).hasSameSizeAs(columns);
    int scheduleColumn = columns.indexOf("schedule_enabled");
    assertThat(scheduleColumn).isNotNegative();
    if (scheduleEnabled == null) {
      assertThat(values.get(scheduleColumn)).isEqualTo("true");
    } else {
      assertThat(values.get(scheduleColumn)).isEqualTo("?");
      int parameterIndex = (int)
          values.subList(0, scheduleColumn + 1).stream().filter("?"::equals).count();
      new DefaultParameterHandler(mapped, entity, bound).setParameters(statement);
      verify(statement).setBoolean(parameterIndex, scheduleEnabled);
    }
  }
}
