package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import com.example.batch.console.domain.ops.service.ConsoleAtomicTaskTypeSchemaService;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleAtomicTaskTypeControllerTest {

  @Mock private ConsoleAtomicTaskTypeSchemaService schemaService;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleAtomicTaskTypeController controller;

  @Test
  void schemaReturnsCatalog() {
    List<AtomicTaskTypeSchema> catalog =
        List.of(new AtomicTaskTypeSchema("sql", "SQL", true, List.of(), List.of()));
    when(schemaService.schema()).thenReturn(catalog);
    when(responseFactory.success(catalog)).thenReturn(CommonResponse.success(catalog));

    assertThat(controller.schema().data())
        .extracting(AtomicTaskTypeSchema::taskType)
        .containsExactly("sql");
  }
}
