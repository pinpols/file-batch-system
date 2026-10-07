package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("导入预处理生产装配：只使用注入的配置、序列化器与对象读取协作者")
class PreprocessStepWiringTest {

  @Test
  @DisplayName("容器使用唯一构造器且不自行创建第二套配置或对象源")
  void shouldUseInjectedCollaborators_whenContextStarts() {
    WorkerImportPayloadProperties properties = new WorkerImportPayloadProperties();
    ObjectMapper mapper = JsonUtils.newDefaultMapper();
    new ApplicationContextRunner()
        .withUserConfiguration(PreprocessStep.class, ImportPreprocessObjectSource.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
        .withBean(
            PlatformFileRecordRepository.class, () -> mock(PlatformFileRecordRepository.class))
        .withBean(
            PlatformPipelineDefinitionRepository.class,
            () -> mock(PlatformPipelineDefinitionRepository.class))
        .withBean(BatchSecurityProperties.class, BatchSecurityProperties::new)
        .withBean(BatchObjectCryptoService.class, () -> mock(BatchObjectCryptoService.class))
        .withBean(WorkerImportPayloadProperties.class, () -> properties)
        .withBean(ObjectMapper.class, () -> mapper)
        .withBean(S3StorageProperties.class, S3StorageProperties::new)
        .withBean(BatchObjectStore.class, () -> mock(BatchObjectStore.class))
        .run(context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(PreprocessStep.class)
              .hasSingleBean(ImportPreprocessObjectSource.class);
          ImportPreprocessObjectSource source = context.getBean(ImportPreprocessObjectSource.class);
          assertThat(PreprocessStep.class.getConstructors()).hasSize(1);
          assertThat(context.getBean(PreprocessStep.class))
              .extracting("payloadProperties", "objectMapper", "objectSource")
              .containsExactly(properties, mapper, source);
          assertThat(source)
              .extracting("payloadProperties", "fileRecords", "objectStore")
              .containsExactly(
                  properties,
                  context.getBean(PlatformFileRecordRepository.class),
                  context.getBean(BatchObjectStore.class));
        });
  }
}
