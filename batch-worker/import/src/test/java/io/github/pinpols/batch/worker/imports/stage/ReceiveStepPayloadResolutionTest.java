package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.util.HashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("导入载荷解析:空载荷具名工厂的兼容分支")
class ReceiveStepPayloadResolutionTest {

  @Test
  @DisplayName("非 JSON 与损坏 JSON 均返回空载荷")
  void shouldReturnEmptyPayloadForInvalidInput() {
    ReceiveStep step = stepWith(new ObjectMapper());

    assertEmpty(resolve(step, "plain-text"));
    assertEmpty(resolve(step, "{"));
  }

  @Test
  @DisplayName("反序列化返回空对象时使用空载荷")
  void shouldReturnEmptyPayloadWhenMapperReturnsNull() throws Exception {
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.readValue(anyString(), eq(ImportPayload.class))).thenReturn(null);

    assertEmpty(resolve(stepWith(mapper), "{}"));
  }

  private static ReceiveStep stepWith(ObjectMapper mapper) {
    WorkerImportPayloadProperties properties = new WorkerImportPayloadProperties();
    properties.setMaxPayloadSizeMb(1);
    return new ReceiveStep(
        mock(PlatformFileRecordRepository.class),
        mock(PlatformPipelineDefinitionRepository.class),
        mock(PlatformPipelineRunRepository.class),
        mock(BatchSecurityProperties.class),
        mapper,
        properties);
  }

  private static ImportPayload resolve(ReceiveStep step, String rawPayload) {
    ImportJobContext context = new ImportJobContext();
    context.setRawPayload(rawPayload);
    context.setAttributes(new HashMap<>());
    return ReflectionTestUtils.invokeMethod(step, "resolvePayload", context);
  }

  private static void assertEmpty(ImportPayload payload) {
    assertThat(payload).isNotNull();
    assertThat(payload.content()).isNull();
    assertThat(payload.metadata()).isEmpty();
  }
}
