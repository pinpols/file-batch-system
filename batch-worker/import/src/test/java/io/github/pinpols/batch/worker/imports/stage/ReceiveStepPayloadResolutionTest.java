package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.support.FrozenFileTemplateResolver;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.util.HashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("导入载荷解析:非法 JSON 与空对象使用具名空载荷")
class ReceiveStepPayloadResolutionTest {

  @Mock
  private ObjectMapper nullableMapper;

  @Mock
  private PlatformFileRecordRepository fileRecords;

  @Mock
  private FrozenFileTemplateResolver templates;

  @Mock
  private PlatformPipelineRunRepository pipelineRuns;

  @Mock
  private BatchSecurityProperties security;

  @Test
  @DisplayName("非 JSON 与损坏 JSON 均返回空载荷")
  void shouldReturnEmptyPayload_whenInputIsInvalid() {
    ReceiveStep step = stepWith(new ObjectMapper());

    assertEmpty(resolve(step, "plain-text"));
    assertEmpty(resolve(step, "{"));
  }

  @Test
  @DisplayName("反序列化返回空对象时使用空载荷")
  void shouldReturnEmptyPayload_whenMapperReturnsNull() throws Exception {
    when(nullableMapper.readValue(anyString(), eq(ImportPayload.class))).thenReturn(null);

    ReceiveStep step = stepWith(nullableMapper);
    ImportPayload payload = resolve(step, "{}");
    assertEmpty(payload);
  }

  private ReceiveStep stepWith(ObjectMapper mapper) {
    WorkerImportPayloadProperties properties = new WorkerImportPayloadProperties();
    properties.setMaxPayloadSizeMb(1);
    return new ReceiveStep(fileRecords, templates, pipelineRuns, security, mapper, properties);
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
