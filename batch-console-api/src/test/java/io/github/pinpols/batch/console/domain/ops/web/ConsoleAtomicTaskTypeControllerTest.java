package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleAtomicTaskTypeSchemaService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("原子任务类型接口:目录查询透传服务层结果, 并以任务类型字段返回")
class ConsoleAtomicTaskTypeControllerTest {

  @Mock
  private ConsoleAtomicTaskTypeSchemaService schemaService;

  @Mock
  private ConsoleResponseFactory responseFactory;

  @InjectMocks
  private ConsoleAtomicTaskTypeController controller;

  @Test
  @DisplayName("目录查询:返回的服务层目录原样透传, 任务类型字段与入参一致")
  void shouldReturnCatalog_whenSchemaQueried() {
    List<AtomicTaskTypeSchema> catalog =
        List.of(new AtomicTaskTypeSchema("sql", "SQL", true, List.of(), List.of()));
    when(schemaService.schema()).thenReturn(catalog);
    when(responseFactory.success(catalog)).thenReturn(CommonResponse.success(catalog));

    assertThat(controller.schema().data())
        .extracting(AtomicTaskTypeSchema::taskType)
        .containsExactly("sql");
  }
}
